package com.cherret.zaprett

import android.content.ActivityNotFoundException
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.StringRes
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.cherret.zaprett.byedpi.ByeDpiVpnService
import com.cherret.zaprett.data.ServiceStatus
import com.cherret.zaprett.ui.screen.TvScreen
import com.cherret.zaprett.ui.theme.ZaprettTheme
import com.cherret.zaprett.ui.viewmodel.TvViewModel

class TvActivity : ComponentActivity() {
    private val viewModel: TvViewModel by viewModels()
    @get:StringRes
    private var vpnError by mutableIntStateOf(0)
    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            startVpn()
        } else {
            vpnError = R.string.tv_vpn_permission_denied
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ZaprettTheme(darkTheme = true, dynamicColor = false) {
                val status by ByeDpiVpnService.statusFlow.collectAsState()
                TvScreen(viewModel, status, vpnError, onToggle = ::toggleVpn)
            }
        }
    }

    private fun toggleVpn() {
        vpnError = 0
        if (ByeDpiVpnService.status == ServiceStatus.Connected) {
            ByeDpiVpnService.stop(this)
            return
        }
        try {
            val request = VpnService.prepare(this)
            if (request == null) startVpn() else vpnPermission.launch(request)
        } catch (exception: ActivityNotFoundException) {
            vpnError = R.string.tv_vpn_permission_unavailable
        } catch (exception: SecurityException) {
            vpnError = R.string.tv_vpn_permission_unavailable
        }
    }

    private fun startVpn() {
        viewModel.prepareServiceMode()
        try {
            ByeDpiVpnService.start(this)
        } catch (exception: IllegalStateException) {
            vpnError = R.string.tv_vpn_failed
        } catch (exception: SecurityException) {
            vpnError = R.string.tv_vpn_failed
        }
    }
}
