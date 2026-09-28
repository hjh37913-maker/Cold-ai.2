package com.coldai.assistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.coldai.assistant.ai.Kind
import com.coldai.assistant.ai.UiError
import com.coldai.assistant.cmd.Confirm
import com.coldai.assistant.ui.ColdApp
import com.coldai.assistant.ui.ColdVm
import com.coldai.assistant.ui.Status
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val vm: ColdVm by viewModels()
    private var permCb: ((Boolean) -> Unit)? = null
    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
            val cb = permCb
            permCb = null
            cb?.invoke(res.values.all { it })
        }

    override fun attachBaseContext(base: Context) {
        val lang = base.getSharedPreferences("cold", Context.MODE_PRIVATE).getString("lang", "ru") ?: "ru"
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocale(Locale(lang))
        super.attachBaseContext(base.createConfigurationContext(cfg))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ColdApp(vm, onMic = ::onMic, onConfirm = ::onConfirm, onLang = ::onLang) }
    }

    override fun onStop() {
        // Микрофон не слушается в фоне и при выключенном экране.
        if (vm.status.value == Status.LISTENING) vm.cancel()
        super.onStop()
    }

    private fun ensure(perms: List<String>, done: (Boolean) -> Unit) {
        val missing = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) done(true) else { permCb = done; permLauncher.launch(missing.toTypedArray()) }
    }

    private fun onMic() {
        if (vm.status.value == Status.LISTENING) { vm.toggleMic(this); return }
        ensure(listOf(Manifest.permission.RECORD_AUDIO)) { ok ->
            if (ok) vm.toggleMic(this) else vm.error.value = UiError(Kind.PERM)
        }
    }

    private fun onConfirm(p: Confirm) {
        ensure(p.perms) { ok -> if (ok) vm.resolvePending(this) else vm.cancelPending(this, true) }
    }

    private fun onLang(l: String) {
        vm.prefs.update { it.copy(lang = l) }
        recreate()
    }
}
