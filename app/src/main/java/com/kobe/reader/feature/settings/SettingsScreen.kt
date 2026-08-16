package com.kobe.reader.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kobe.reader.BuildConfig
import com.kobe.reader.R
import com.kobe.reader.data.prefs.KobeSettings
import com.kobe.reader.data.prefs.ThemePreference
import com.kobe.reader.ui.components.ProBadge

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: KobeSettings,
    onBack: () -> Unit,
    onUpgrade: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var themeDialogOpen by remember { mutableStateOf(false) }

    if (themeDialogOpen) {
        ThemeDialog(
            current = settings.theme,
            onSelect = {
                viewModel.setTheme(it)
                themeDialogOpen = false
            },
            onDismiss = { themeDialogOpen = false },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
        ) {
            if (!state.isPro) {
                item { UpgradeRow(onUpgrade = onUpgrade) }
                item { HorizontalDivider() }
            }

            item { SectionTitle(R.string.settings_appearance) }
            item {
                SettingRow(
                    titleRes = R.string.settings_theme,
                    subtitle = stringResource(settings.theme.labelRes()),
                    onClick = { themeDialogOpen = true },
                )
            }
            item {
                SwitchRow(
                    titleRes = R.string.settings_dynamic_color,
                    checked = settings.useDynamicColor,
                    onChange = viewModel::setDynamicColor,
                )
            }

            item { SectionTitle(R.string.settings_reading) }
            item {
                SwitchRow(
                    titleRes = R.string.settings_keep_screen_on,
                    checked = settings.keepScreenOn,
                    onChange = viewModel::setKeepScreenOn,
                )
            }
            item {
                SwitchRow(
                    titleRes = R.string.settings_remember_position,
                    checked = settings.rememberPosition,
                    onChange = viewModel::setRememberPosition,
                )
            }
            item {
                SwitchRow(
                    titleRes = R.string.settings_scroll_horizontal,
                    checked = settings.horizontalPaging,
                    onChange = viewModel::setHorizontalPaging,
                )
            }

            item { SectionTitle(R.string.settings_storage) }
            item {
                SettingRow(
                    titleRes = R.string.settings_clear_recents,
                    subtitle = null,
                    onClick = viewModel::clearHistory,
                )
            }
            item {
                SettingRow(
                    titleRes = R.string.settings_clear_cache,
                    subtitle = state.cacheSizeLabel,
                    onClick = viewModel::clearCache,
                )
            }

            item { SectionTitle(R.string.settings_privacy) }
            item {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_shield),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.settings_privacy_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item { SectionTitle(R.string.settings_about) }
            item {
                SettingRow(
                    titleRes = R.string.settings_version,
                    subtitle = BuildConfig.VERSION_NAME,
                    onClick = {},
                    titleArg = BuildConfig.VERSION_NAME,
                )
            }
        }
    }
}

@Composable
private fun UpgradeRow(onUpgrade: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onUpgrade)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.pro_name),
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.width(8.dp))
                ProBadge()
            }
            Text(
                text = stringResource(R.string.pro_headline),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            painter = painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
        )
    }
}

@Composable
private fun SectionTitle(titleRes: Int) {
    Text(
        text = stringResource(titleRes),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun SettingRow(
    titleRes: Int,
    subtitle: String?,
    onClick: () -> Unit,
    titleArg: String? = null,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = if (titleArg != null) {
                stringResource(titleRes, titleArg)
            } else {
                stringResource(titleRes)
            },
            style = MaterialTheme.typography.bodyLarge,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SwitchRow(titleRes: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ThemeDialog(
    current: ThemePreference,
    onSelect: (ThemePreference) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_theme)) },
        text = {
            Column {
                ThemePreference.entries.forEach { theme ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(theme) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = theme == current, onClick = { onSelect(theme) })
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(theme.labelRes()))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) }
        },
    )
}

private fun ThemePreference.labelRes(): Int = when (this) {
    ThemePreference.System -> R.string.settings_theme_system
    ThemePreference.Light -> R.string.settings_theme_light
    ThemePreference.Dark -> R.string.settings_theme_dark
}
