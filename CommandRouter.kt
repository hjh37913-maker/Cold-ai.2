package com.coldai.assistant.cmd

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Settings
import android.telephony.SmsManager
import android.view.KeyEvent
import com.coldai.assistant.R
import java.text.DateFormat
import java.util.Date

sealed interface Cmd
data class Reply(val text: String) : Cmd
data class Remember(val fact: String) : Cmd
/** Действие, требующее подтверждения. perms — разрешения, которые нужно получить перед выполнением. */
class Confirm(val prompt: String, val perms: List<String>, val run: () -> String) : Cmd
object PassToAi : Cmd

/**
 * Локальный разбор команд. Всё, что не распознано, уходит в Gemini.
 * Звонки и SMS выполняются только после подтверждения и наличия разрешений.
 */
object CommandRouter {
    private const val OPEN = "открой|открыть|відкрий|відкрити|запусти|запустити"

    private fun r(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    fun handle(ctx: Context, raw: String): Cmd {
        val c = raw.trim().replace(r("^колд[,!.:\\s]+"), "").trim().trimEnd('.', '!', '?').trim()
        val t = c.lowercase()
        val locale = ctx.resources.configuration.locales[0]

        r("^(запомни|запам['’ʼ]?ятай)[,:]?\\s+(.+)$").find(c)?.let { return Remember(it.groupValues[2].trim()) }

        if (r("который час|сколько времени|скільки часу|котра година|яка зараз година").containsMatchIn(t))
            return Reply(ctx.getString(R.string.cmd_time, DateFormat.getTimeInstance(DateFormat.SHORT, locale).format(Date())))
        if (r("какое сегодня число|какая сегодня дата|какое число|какой сегодня день|яка сьогодні дата|яке сьогодні число|який сьогодні день").containsMatchIn(t))
            return Reply(ctx.getString(R.string.cmd_date, DateFormat.getDateInstance(DateFormat.FULL, locale).format(Date())))

        // Музыка через системные медиа-клавиши
        val opt = "( музыку| музику| трек| песню| пісню)?"
        when {
            r("^(поставь на паузу|постав на паузу|пауза|стоп|останови|зупини)$opt$").matches(t) -> return media(ctx, KeyEvent.KEYCODE_MEDIA_PAUSE)
            r("^(играй|продолжи|воспроизведи|грай|продовжи|відтвори)$opt$").matches(t) -> return media(ctx, KeyEvent.KEYCODE_MEDIA_PLAY)
            r("^(следующий трек|следующая песня|наступний трек|наступна пісня|дальше)$").matches(t) -> return media(ctx, KeyEvent.KEYCODE_MEDIA_NEXT)
            r("^(предыдущий трек|предыдущая песня|попередній трек|попередня пісня)$").matches(t) -> return media(ctx, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        }

        // Открыть сайт
        r("^(?:$OPEN)\\s+(?:сайт\\s+)?((?:https?://)?[\\p{L}\\d-]+(?:\\.[\\p{L}\\d-]+)+\\S*)$").find(c)?.let {
            val u = it.groupValues[1].let { s -> if (s.startsWith("http", true)) s else "https://$s" }
            return startSafely(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(u)))
        }

        // Системные настройки
        r("^(?:$OPEN)\\s+(?:системные\\s+)?(?:настройки|налаштування)\\s*(.*)$").find(c)?.let {
            val k = it.groupValues[1].lowercase()
            val action = when {
                k.contains("wi") || k.contains("вай") -> Settings.ACTION_WIFI_SETTINGS
                k.contains("bluetooth") || k.contains("блютуз") -> Settings.ACTION_BLUETOOTH_SETTINGS
                k.contains("звук") -> Settings.ACTION_SOUND_SETTINGS
                k.contains("экран") || k.contains("екран") -> Settings.ACTION_DISPLAY_SETTINGS
                k.contains("приложен") || k.contains("застосун") || k.contains("додат") -> Settings.ACTION_APPLICATION_SETTINGS
                else -> Settings.ACTION_SETTINGS
            }
            return startSafely(ctx, Intent(action))
        }

        // Открыть приложение
        r("^(?:$OPEN)\\s+(?:приложение\\s+|додаток\\s+|застосунок\\s+)?(.+)$").find(c)?.let {
            val name = it.groupValues[1].trim().lowercase()
            val pm = ctx.packageManager
            val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            val hit = apps.firstOrNull { a -> a.loadLabel(pm).toString().lowercase() == name }
                ?: apps.firstOrNull { a -> a.loadLabel(pm).toString().lowercase().contains(name) }
            val launch = hit?.let { h -> pm.getLaunchIntentForPackage(h.activityInfo.packageName) }
            return if (launch == null) Reply(ctx.getString(R.string.cmd_app_not_found, it.groupValues[1].trim()))
            else startSafely(ctx, launch)
        }

        // Звонок (с подтверждением)
        r("^(?:позвони|набери|зателефонуй|подзвони|телефонуй)\\s+(.+)$").find(c)?.let {
            val target = it.groupValues[1].trim()
            val num = digits(target)
            val perms = mutableListOf(Manifest.permission.CALL_PHONE)
            if (num == null) perms += Manifest.permission.READ_CONTACTS
            val run: () -> String = {
                val number = num ?: lookup(ctx, target)
                if (number == null) ctx.getString(R.string.contact_not_found, target)
                else {
                    ctx.startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(number))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    ctx.getString(R.string.cmd_ok)
                }
            }
            return Confirm(ctx.getString(R.string.confirm_call, target), perms, run)
        }

        // SMS (с подтверждением)
        r("^(?:отправь смс|отправь сообщение|надішли смс|надішли повідомлення|смс)\\s+(\\S+)\\s+(.+)$").find(c)?.let {
            val target = it.groupValues[1].trim()
            val body = it.groupValues[2].trim()
            val num = digits(target)
            val perms = mutableListOf(Manifest.permission.SEND_SMS)
            if (num == null) perms += Manifest.permission.READ_CONTACTS
            val run: () -> String = {
                val number = num ?: lookup(ctx, target)
                if (number == null) ctx.getString(R.string.contact_not_found, target)
                else {
                    @Suppress("DEPRECATION")
                    val sms = SmsManager.getDefault()
                    sms.sendMultipartTextMessage(number, null, sms.divideMessage(body), null, null)
                    ctx.getString(R.string.cmd_ok)
                }
            }
            return Confirm(ctx.getString(R.string.confirm_sms, target, body), perms, run)
        }

        return PassToAi
    }

    private fun digits(s: String): String? {
        val d = s.replace(Regex("[\\s()-]"), "")
        return if (d.matches(Regex("\\+?\\d{3,}"))) d else null
    }

    private fun media(ctx: Context, key: Int): Cmd {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
        return Reply(ctx.getString(R.string.cmd_ok))
    }

    private fun startSafely(ctx: Context, i: Intent): Cmd = try {
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Reply(ctx.getString(R.string.cmd_ok))
    } catch (e: ActivityNotFoundException) {
        Reply(ctx.getString(R.string.cmd_open_fail))
    }

    private fun lookup(ctx: Context, name: String): String? {
        val p = ContactsContract.CommonDataKinds.Phone
        ctx.contentResolver.query(
            p.CONTENT_URI, arrayOf(p.NUMBER), "${p.DISPLAY_NAME} LIKE ?", arrayOf("%$name%"), null
        )?.use { if (it.moveToFirst()) return it.getString(0) }
        return null
    }
}
