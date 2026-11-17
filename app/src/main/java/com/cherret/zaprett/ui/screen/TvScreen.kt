package com.cherret.zaprett.ui.screen

import androidx.annotation.StringRes
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cherret.zaprett.R
import com.cherret.zaprett.data.ServiceStatus
import com.cherret.zaprett.ui.viewmodel.TvViewModel

@Composable
fun TvScreen(
    viewModel: TvViewModel,
    status: ServiceStatus,
    @StringRes vpnError: Int,
    onToggle: () -> Unit,
) {
    val connected = status == ServiceStatus.Connected
    val isDownloading = viewModel.downloadingId != null
    val activeStrategy = viewModel.activeStrategy
    val installed = viewModel.installed.associateBy { it.id }
    val available = viewModel.available.associateBy { it.manifest.id }
    val strategyIds = (installed.keys + available.keys).sortedBy { id ->
        installed[id]?.name ?: available.getValue(id).manifest.name
    }
    val initialFocus = remember { FocusRequester() }
    val vpnStatus = when (status) {
        ServiceStatus.Connected -> R.string.tv_vpn_connected
        ServiceStatus.Disconnected -> R.string.tv_vpn_disconnected
        ServiceStatus.Failed -> R.string.tv_vpn_failed
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Row(
            modifier = Modifier.padding(horizontal = 48.dp, vertical = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(48.dp),
        ) {
            Column(modifier = Modifier.weight(2f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall)
                Text(
                    stringResource(vpnStatus),
                    style = MaterialTheme.typography.headlineSmall,
                    color = if (status == ServiceStatus.Failed) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                )
                Text(
                    activeStrategy?.let { stringResource(R.string.tv_active_strategy, it.name) }
                        ?: stringResource(R.string.tv_choose_strategy),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                TvButton(
                    label = stringResource(if (connected) R.string.btn_stop_service else R.string.btn_start_service),
                    onClick = onToggle,
                    enabled = connected || (activeStrategy != null && !isDownloading),
                    prominent = true,
                )
                if (vpnError != 0) TvMessage(vpnError, error = true)
            }

            Column(modifier = Modifier.weight(3f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.title_strategies), style = MaterialTheme.typography.headlineSmall)
                TvButton(
                    label = stringResource(R.string.tv_refresh_catalog),
                    onClick = viewModel::refreshCatalog,
                    modifier = Modifier.focusRequester(initialFocus),
                )
                when {
                    viewModel.loading -> TvMessage(R.string.tv_catalog_loading)
                    viewModel.catalogFailed -> TvMessage(R.string.error_server_data, error = true)
                    strategyIds.isEmpty() -> TvMessage(R.string.tv_catalog_empty)
                }
                if (viewModel.downloadFailed) TvMessage(R.string.tv_strategy_download_failed, error = true)

                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(strategyIds, key = { it }) { id ->
                        val local = installed[id]
                        val remote = available[id]
                        val name = local?.name ?: requireNotNull(remote).manifest.name
                        val isSelected = id == activeStrategy?.id
                        val label = when {
                            id == viewModel.downloadingId -> stringResource(R.string.tv_strategy_downloading, name)
                            local == null -> stringResource(R.string.tv_download_strategy, name)
                            else -> name
                        }
                        TvButton(
                            label = label,
                            onClick = {
                                if (!isDownloading && !connected) {
                                    if (local != null) viewModel.selectStrategy(local)
                                    else viewModel.downloadStrategy(requireNotNull(remote))
                                }
                            },
                            enabled = !connected,
                            isSelected = isSelected,
                        )
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) { initialFocus.requestFocus() }
}

@Composable
private fun TvMessage(@StringRes message: Int, error: Boolean = false) {
    Text(
        stringResource(message),
        style = MaterialTheme.typography.bodyLarge,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun TvButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    prominent: Boolean = false,
    isSelected: Boolean? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .onFocusChanged { focused = it.isFocused }
            .border(2.dp, if (focused) colors.onSurface else Color.Transparent, shape)
            .semantics { isSelected?.let { selected = it } },
        enabled = enabled,
        shape = shape,
        colors = if (prominent) ButtonDefaults.buttonColors() else ButtonDefaults.buttonColors(
            containerColor = if (isSelected == true) colors.secondaryContainer else colors.surfaceVariant,
            contentColor = if (isSelected == true) colors.onSecondaryContainer else colors.onSurfaceVariant,
        ),
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
