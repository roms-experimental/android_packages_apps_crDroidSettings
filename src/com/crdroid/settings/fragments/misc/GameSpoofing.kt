/*
 * SPDX-FileCopyrightText: crDroid Android Project
 * SPDX-License-Identifier: Apache-2.0
 */

@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.crdroid.settings.fragments.misc

import android.app.ActivityManager
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.internal.logging.nano.MetricsProto
import com.android.settings.R
import com.android.settings.SettingsPreferenceFragment
import com.android.settingslib.spa.framework.theme.SettingsTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale

/**
 * Per-app identity spoofing, stored as JSON in spoof_gameprops_config. Each app picks one of two
 * modes:
 *
 *  - Device identity: Build field overrides, applied by GamePropsSpoofService.
 *  - CPU / GPU model: renderer, Vulkan device and CPU info, applied by
 *    com.android.internal.util.AdvancedAppSpoofUtils (adapted from DerpFest, author kenway214).
 *
 *     {
 *       "enabled": true,
 *       "debug": false,
 *       "games":    { "<pkg>": { "MODEL": "...", ... } },
 *       "hardware": { "<pkg>": { "gpu": "adreno830", "cpu": "sd8elite" } }
 *     }
 *
 * Both services load the config once per process, so every change force-stops the apps it touches.
 */
class GameSpoofing : SettingsPreferenceFragment() {

    internal data class GameEntry(
        val packageName: String,
        val label: String,
        val icon: Drawable?,
        val props: Map<String, String> = emptyMap(),
        val gpu: String = "",
        val cpu: String = "",
    ) {
        /** An app is in exactly one mode: it has Build overrides, or a CPU/GPU rule, never both. */
        val hardwareMode: Boolean get() = gpu.isNotEmpty() || cpu.isNotEmpty()
    }

    internal sealed interface GsDialog {
        object AddGame : GsDialog
        class Mode(val packageName: String, val label: String) : GsDialog
        class Profile(val packageName: String, val label: String) : GsDialog
        class Gpu(
            val packageName: String,
            val label: String,
            val currentGpu: String,
            val currentCpu: String,
        ) : GsDialog
        class Cpu(
            val packageName: String,
            val label: String,
            val gpu: String,
            val currentCpu: String,
        ) : GsDialog
        class EditProps(val packageName: String, val label: String, val props: Map<String, String>) : GsDialog
        class Remove(val packageName: String, val label: String) : GsDialog
    }

    internal class GsUiState {
        var loading by mutableStateOf(true)
        var enabled by mutableStateOf(false)
        val games = mutableStateListOf<GameEntry>()
        var dialog by mutableStateOf<GsDialog?>(null)

        var appsLoading by mutableStateOf(false)
        var installedApps by mutableStateOf<List<AppListEntry>>(emptyList())
    }

    private val ui = GsUiState()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Config keys the screen does not own (e.g. "debug"), written back unchanged. */
    private var extraKeys: JSONObject = JSONObject()

    /** Bumped on every save, so a load that started before the save doesn't overwrite it. */
    private var saveGeneration = 0

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requireActivity().title = getString(R.string.game_spoofing_title)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = spoofComposeView(requireContext()).apply {
        val fragment = this@GameSpoofing
        setContent {
            SettingsTheme {
                GameSpoofingContent(
                    state = ui,
                    onEnabledChange = fragment::setSpoofingEnabled,
                    onAddGame = fragment::openAddGame,
                    onPickApp = { app ->
                        ui.dialog = GsDialog.Mode(app.packageName, app.label)
                    },
                    onPickProfile = fragment::applyProfile,
                    onSaveHardware = fragment::saveHardware,
                    onSaveProps = fragment::saveProps,
                    onRemove = fragment::removeGame,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        loadConfig()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    override fun getMetricsCategory(): Int = MetricsProto.MetricsEvent.CRDROID_SETTINGS

    // ---- Config I/O ------------------------------------------------------------------------

    private fun loadConfig() {
        val startedAt = saveGeneration
        scope.launch {
            val pm = requireContext().packageManager
            val (enabled, rules, extras) = withContext(Dispatchers.IO) {
                val content = Settings.Secure.getString(
                    requireContext().contentResolver, GAMEPROPS_CONFIG_KEY)
                val parsed = parseConfig(content)
                Triple(
                    parsed.first,
                    parsed.second.map { rule ->
                        val info = runCatching { pm.getApplicationInfo(rule.pkg, 0) }.getOrNull()
                        GameEntry(
                            packageName = rule.pkg,
                            label = info?.let { pm.getApplicationLabel(it).toString() } ?: rule.pkg,
                            icon = info?.let { runCatching { pm.getApplicationIcon(it) }.getOrNull() },
                            props = rule.props,
                            gpu = rule.gpu,
                            cpu = rule.cpu,
                        )
                    }.sortedBy { it.label.lowercase(Locale.getDefault()) },
                    parsed.third,
                )
            }
            // The user changed something while this was reading; the screen is newer.
            if (saveGeneration != startedAt) {
                ui.loading = false
                return@launch
            }
            ui.enabled = enabled
            extraKeys = extras
            ui.games.clear()
            ui.games.addAll(rules)
            ui.loading = false
        }
    }

    private fun saveConfig() {
        saveGeneration++
        try {
            val json = JSONObject(extraKeys.toString())
            json.put("enabled", ui.enabled)
            val gamesObj = JSONObject()
            val hardwareObj = JSONObject()
            ui.games.forEach { game ->
                if (game.props.isEmpty() && !game.hardwareMode) return@forEach
                if (game.hardwareMode) {
                    hardwareObj.put(game.packageName, JSONObject().apply {
                        if (game.gpu.isNotEmpty()) put("gpu", game.gpu)
                        if (game.cpu.isNotEmpty()) put("cpu", game.cpu)
                    })
                } else {
                    val propsObj = JSONObject()
                    game.props.forEach { (k, v) -> propsObj.put(k, v) }
                    gamesObj.put(game.packageName, propsObj)
                }
            }
            json.put("games", gamesObj)
            if (hardwareObj.length() > 0) {
                json.put("hardware", hardwareObj)
            } else {
                json.remove("hardware")
            }
            Settings.Secure.putString(
                requireContext().contentResolver, GAMEPROPS_CONFIG_KEY, json.toString(2))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save config", e)
        }
    }

    private fun restart(packages: Collection<String>) {
        val am = requireContext().getSystemService(ActivityManager::class.java)
        killPackages(am, packages.toSet())
    }

    // ---- Actions ---------------------------------------------------------------------------

    private fun setSpoofingEnabled(enabled: Boolean) {
        ui.enabled = enabled
        saveConfig()
        restart(ui.games.map { it.packageName })
    }

    private fun openAddGame() {
        ui.dialog = GsDialog.AddGame
        if (ui.installedApps.isNotEmpty() || ui.appsLoading) return
        ui.appsLoading = true
        scope.launch {
            val pm = requireContext().packageManager
            ui.installedApps = withContext(Dispatchers.IO) {
                filterInstalledApps(pm = pm, showSystem = true, targeted = emptySet())
                    .map { app ->
                        AppListEntry(
                            packageName = app.packageName,
                            label = pm.getApplicationLabel(app).toString(),
                            icon = runCatching { pm.getApplicationIcon(app) }.getOrNull(),
                            isSystem = app.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                        )
                    }
                    .sortedBy { it.label.lowercase(Locale.getDefault()) }
            }
            ui.appsLoading = false
        }
    }

    private fun applyProfile(packageName: String, label: String, props: Map<String, String>?) {
        // null means "Custom": open the property editor, seeded with what is configured.
        if (props == null) {
            val existing = ui.games.firstOrNull { it.packageName == packageName }?.props.orEmpty()
            ui.dialog = GsDialog.EditProps(packageName, label, existing)
            return
        }
        saveProps(packageName, label, props)
    }

    private fun saveProps(packageName: String, label: String, props: Map<String, String>) {
        // Saving an empty identity drops the rule: an app is only listed while it spoofs something.
        if (props.isEmpty()) {
            removeGame(packageName)
            return
        }
        upsertGame(packageName, label, props = props)
    }

    private fun saveHardware(
        packageName: String,
        label: String,
        gpu: String = "",
        cpu: String = "",
    ) {
        if (gpu.isEmpty() && cpu.isEmpty()) {
            removeGame(packageName)
            return
        }
        upsertGame(packageName, label, props = emptyMap(), gpu = gpu, cpu = cpu)
    }

    private fun upsertGame(
        packageName: String,
        label: String,
        props: Map<String, String> = emptyMap(),
        gpu: String = "",
        cpu: String = "",
    ) {
        val index = ui.games.indexOfFirst { it.packageName == packageName }
        if (index >= 0) {
            ui.games[index] = ui.games[index].copy(props = props, gpu = gpu, cpu = cpu)
        } else {
            val icon = ui.installedApps.firstOrNull { it.packageName == packageName }?.icon
            ui.games.add(GameEntry(packageName, label, icon, props, gpu, cpu))
            ui.games.sortBy { it.label.lowercase(Locale.getDefault()) }
        }
        saveConfig()
        restart(listOf(packageName))
    }

    private fun removeGame(packageName: String) {
        ui.games.removeAll { it.packageName == packageName }
        saveConfig()
        restart(listOf(packageName))
    }

    companion object {
        private const val TAG = "GameSpoofing"
        private const val GAMEPROPS_CONFIG_KEY = "spoof_gameprops_config"

        internal val PRESET_PROFILES = listOf(
            "ROG Phone 8 Pro" to mapOf("MODEL" to "ASUS_AI2401_A", "MANUFACTURER" to "asus"),
            "Galaxy S24 Ultra" to mapOf("MODEL" to "SM-S928B", "MANUFACTURER" to "samsung"),
            "Xiaomi 13 Pro" to mapOf("MODEL" to "2210132C", "MANUFACTURER" to "Xiaomi"),
            "OnePlus 9 Pro" to mapOf("MODEL" to "LE2101", "MANUFACTURER" to "OnePlus"),
            "Black Shark 4" to mapOf("MODEL" to "2SM-X706B", "MANUFACTURER" to "blackshark"),
            "Lenovo Y700" to mapOf("MODEL" to "Lenovo TB-9707F", "MANUFACTURER" to "Lenovo"),
        )

        /** Build / Build.VERSION field names, as GamePropsSpoofService looks them up. */
        internal val FIELD_NAME_REGEX = Regex("""[A-Za-z_][A-Za-z0-9_]*""")

        internal fun profileName(props: Map<String, String>): String? =
            PRESET_PROFILES.firstOrNull { it.second == props }?.first

        /**
         * CPU / GPU presets, mirroring AdvancedAppSpoofUtils. Kept here because the fragment cannot
         * reach that @hide class from the app process.
         */
        internal val GPU_PRESETS: LinkedHashMap<String, String> = linkedMapOf(
            "adreno840"    to "Adreno 840  \u00b7  Snapdragon 8 Elite Gen 5",
            "adreno830"    to "Adreno 830  \u00b7  Snapdragon 8 Elite",
            "adreno750"    to "Adreno 750  \u00b7  Snapdragon 8 Gen 3",
            "adreno740"    to "Adreno 740  \u00b7  Snapdragon 8 Gen 2",
            "adreno735"    to "Adreno 735  \u00b7  Snapdragon 8s Gen 3",
            "adreno730"    to "Adreno 730  \u00b7  Snapdragon 8 Gen 1",
            "adreno720"    to "Adreno 720  \u00b7  Snapdragon 7s Gen 3",
            "mali_g925"    to "Mali-G925 Immortalis  \u00b7  Dimensity 9400",
            "mali_g920"    to "Mali-G920 Immortalis  \u00b7  Dimensity 9400+",
            "mali_g720"    to "Mali-G720 Immortalis  \u00b7  Dimensity 9300",
            "mali_g715"    to "Mali-G715 Immortalis  \u00b7  Dimensity 9200+",
            "mali_g615"    to "Mali-G615  \u00b7  Dimensity 8300 / 8350",
            "mali_g78"     to "Mali-G78 MP24  \u00b7  Kirin 9000",
            "maleoon930"   to "Maleoon 930  \u00b7  Kirin 9030 Pro / 9030S",
            "maleoon920"   to "Maleoon 920  \u00b7  Kirin 9020",
            "maleoon910"   to "Maleoon 910  \u00b7  Kirin 9010",
            "xclipse940"   to "Samsung Xclipse 940  \u00b7  Exynos 2400",
            "xclipse920"   to "Samsung Xclipse 920  \u00b7  Exynos 2200",
            "apple_a18pro" to "Apple A18 Pro GPU",
            "apple_a17pro" to "Apple A17 Pro GPU",
        )

        internal val CPU_PRESETS: LinkedHashMap<String, String> = linkedMapOf(
            "sd8elitegen5"      to "Snapdragon 8 Elite Gen 5 (SM8850)",
            "sd8elite"          to "Snapdragon 8 Elite (SM8750)",
            "sd8gen3"           to "Snapdragon 8 Gen 3 (SM8650)",
            "sd8gen2"           to "Snapdragon 8 Gen 2 (SM8550)",
            "dimensity9400plus" to "Dimensity 9400+ (MT6991)",
            "dimensity9400"     to "Dimensity 9400 (MT6989)",
            "dimensity8350"     to "Dimensity 8350 (MT6897)",
            "kirin9030pro"      to "Kirin 9030 Pro",
            "kirin9030s"        to "Kirin 9030S",
            "kirin9020"         to "Kirin 9020",
            "kirin9020a"        to "Kirin 9020A",
            "kirin9000s"        to "Kirin 9000S",
            "kirin9000"         to "Kirin 9000",
            "xuanjie_o1"        to "Xiaomi Xring O1",
            "xuanjie_o3"        to "Xiaomi Xring O3",
        )

        internal fun gpuName(key: String): String? = GPU_PRESETS[key]
        internal fun cpuName(key: String): String? = CPU_PRESETS[key]

        /** One entry of the stored config, before it is resolved to an app label and icon. */
        internal data class Rule(
            val pkg: String,
            val props: Map<String, String> = emptyMap(),
            val gpu: String = "",
            val cpu: String = "",
        ) {
            val hardwareMode: Boolean get() = gpu.isNotEmpty() || cpu.isNotEmpty()
        }

        /** (enabled, rules, other top-level keys) from the stored JSON. */
        private fun parseConfig(
            content: String?,
        ): Triple<Boolean, List<Rule>, JSONObject> {
            if (content.isNullOrBlank()) return Triple(false, emptyList(), JSONObject())
            return try {
                val json = JSONObject(content)
                val rules = linkedMapOf<String, Rule>()
                json.optJSONObject("games")?.let { gamesObj ->
                    gamesObj.keys().forEach { pkg ->
                        val propsObj = gamesObj.optJSONObject(pkg) ?: return@forEach
                        val props = linkedMapOf<String, String>()
                        propsObj.keys().forEach { k -> props[k] = propsObj.optString(k) }
                        rules[pkg] = Rule(pkg = pkg, props = props)
                    }
                }
                json.optJSONObject("hardware")?.let { hardwareObj ->
                    hardwareObj.keys().forEach { pkg ->
                        val ruleObj = hardwareObj.optJSONObject(pkg) ?: return@forEach
                        rules[pkg] = Rule(
                            pkg = pkg,
                            gpu = ruleObj.optString("gpu"),
                            cpu = ruleObj.optString("cpu"),
                        )
                    }
                }
                val extras = JSONObject()
                json.keys().forEach { k ->
                    if (k != "enabled" && k != "games" && k != "hardware") {
                        extras.put(k, json.get(k))
                    }
                }
                Triple(json.optBoolean("enabled", false), rules.values.toList(), extras)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load config", e)
                Triple(false, emptyList(), JSONObject())
            }
        }
    }
}

// ---------------------------------------------------------------------------
// UI
// ---------------------------------------------------------------------------

@Composable
private fun GameSpoofingContent(
    state: GameSpoofing.GsUiState,
    onEnabledChange: (Boolean) -> Unit,
    onAddGame: () -> Unit,
    onPickApp: (AppListEntry) -> Unit,
    onPickProfile: (String, String, Map<String, String>?) -> Unit,
    onSaveHardware: (String, String, String, String) -> Unit,
    onSaveProps: (String, String, Map<String, String>) -> Unit,
    onRemove: (String) -> Unit,
) {
    GsDialogs(state, onPickApp, onPickProfile, onSaveHardware, onSaveProps, onRemove)

    SpoofScreen {
        SpoofingHeaderCard(
            title = stringResource(R.string.game_spoofing_title),
            subtitle = when {
                !state.enabled -> stringResource(R.string.gs_disabled)
                state.games.isEmpty() -> stringResource(R.string.gs_no_apps)
                else -> stringResource(R.string.gs_configured_count, state.games.size)
            },
            checked = state.enabled,
            onCheckedChange = onEnabledChange,
        ) {
            Icon(
                imageVector = Icons.Rounded.SportsEsports,
                contentDescription = null,
                tint = spoofHeaderIconTint(state.enabled),
            )
        }

        Spacer(Modifier.height(16.dp))
        SpoofPrimaryAction(
            text = stringResource(R.string.gs_add_app),
            icon = Icons.Rounded.Add,
            onClick = onAddGame,
        )

        when {
            state.loading -> SpoofingLoadingBox(Modifier.padding(vertical = 32.dp))
            state.games.isEmpty() -> {
                Spacer(Modifier.height(16.dp))
                SpoofingEmptyState(
                    icon = Icons.Rounded.SportsEsports,
                    title = stringResource(R.string.gs_no_apps),
                    description = stringResource(R.string.gs_no_apps_summary),
                )
            }
            else -> {
                Spacer(Modifier.height(8.dp))
                SectionLabel(
                    text = stringResource(R.string.gs_configured_apps),
                    modifier = Modifier.padding(top = 8.dp),
                )
                Column(verticalArrangement = Arrangement.spacedBy(SpoofDimens.RowGap)) {
                    state.games.forEachIndexed { index, game ->
                        GameCard(
                            game = game,
                            dimmed = !state.enabled,
                            shape = SpoofShapes.forIndex(index, state.games.size),
                            onEdit = {
                                state.dialog = if (game.hardwareMode) {
                                    GameSpoofing.GsDialog.Gpu(
                                        game.packageName, game.label, game.gpu, game.cpu)
                                } else {
                                    GameSpoofing.GsDialog.EditProps(
                                        game.packageName, game.label, game.props)
                                }
                            },
                            onMode = {
                                state.dialog = GameSpoofing.GsDialog.Mode(
                                    game.packageName, game.label)
                            },
                            onRemove = {
                                state.dialog = GameSpoofing.GsDialog.Remove(
                                    game.packageName, game.label)
                            },
                        )
                    }
                }
            }
        }

        SpoofFooter(stringResource(R.string.gs_footer_body))
    }
}

@Composable
private fun GameCard(
    game: GameSpoofing.GameEntry,
    dimmed: Boolean,
    shape: androidx.compose.ui.graphics.Shape,
    onEdit: () -> Unit,
    onMode: () -> Unit,
    onRemove: () -> Unit,
) {
    Surface(
        onClick = onEdit,
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceBright,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIconOrPlaceholder(game.packageName, game.icon, APP_ICON_SIZE_CARD)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        game.label,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        game.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                SpoofStatusChip(
                    text = if (game.hardwareMode) {
                        stringResource(R.string.gs_mode_hardware_short)
                    } else {
                        GameSpoofing.profileName(game.props)
                            ?: stringResource(R.string.gs_profile_custom)
                    },
                    tone = if (dimmed) SpoofTone.Neutral else SpoofTone.Positive,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = when {
                    game.hardwareMode -> listOfNotNull(
                        game.gpu.takeIf { it.isNotEmpty() }
                            ?.let { GameSpoofing.gpuName(it) ?: it },
                        game.cpu.takeIf { it.isNotEmpty() }
                            ?.let { GameSpoofing.cpuName(it) ?: it },
                    ).joinToString("  \u00b7  ")
                    else -> game.props.entries.joinToString("  \u00b7  ") { "${it.key}=${it.value}" }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            SpoofActionRow(
                listOf(
                    SpoofAction(stringResource(R.string.gs_edit_props_short), Icons.Rounded.Edit,
                        onClick = onEdit),
                    SpoofAction(stringResource(R.string.gs_mode_short), Icons.Rounded.SwapHoriz,
                        onClick = onMode),
                    SpoofAction(stringResource(R.string.gs_remove), Icons.Rounded.DeleteOutline,
                        onClick = onRemove),
                ),
            )
        }
    }
}

@Composable
private fun GsDialogs(
    state: GameSpoofing.GsUiState,
    onPickApp: (AppListEntry) -> Unit,
    onPickProfile: (String, String, Map<String, String>?) -> Unit,
    onSaveHardware: (String, String, String, String) -> Unit,
    onSaveProps: (String, String, Map<String, String>) -> Unit,
    onRemove: (String) -> Unit,
) {
    val dismiss = { state.dialog = null }
    when (val dialog = state.dialog) {
        null -> Unit
        GameSpoofing.GsDialog.AddGame -> AddGameDialog(
            loading = state.appsLoading,
            apps = state.installedApps,
            configured = state.games.map { it.packageName }.toSet(),
            onPick = { app -> dismiss(); onPickApp(app) },
            onDismiss = dismiss,
        )
        is GameSpoofing.GsDialog.Mode -> {
            val current = state.games.firstOrNull { it.packageName == dialog.packageName }
            SpoofChoiceDialog(
                title = stringResource(R.string.gs_spoof_mode),
                options = listOf(
                    stringResource(R.string.gs_mode_device),
                    stringResource(R.string.gs_mode_hardware),
                ),
                summaries = listOf(
                    stringResource(R.string.gs_mode_device_summary),
                    stringResource(R.string.gs_mode_hardware_summary),
                ),
                selected = if (current?.hardwareMode == true) 1 else 0,
                onPick = { index ->
                    state.dialog = if (index == 1) {
                        GameSpoofing.GsDialog.Gpu(
                            dialog.packageName, dialog.label,
                            current?.gpu.orEmpty(), current?.cpu.orEmpty(),
                        )
                    } else {
                        GameSpoofing.GsDialog.Profile(dialog.packageName, dialog.label)
                    }
                },
                onDismiss = dismiss,
            )
        }
        // Hardware mode is a two-step pick: GPU first, then CPU. Dismissing either step leaves the
        // stored rule untouched, so a half-configured app never reaches the service.
        is GameSpoofing.GsDialog.Gpu -> SpoofChoiceDialog(
            title = stringResource(R.string.gs_select_gpu),
            options = listOf(stringResource(R.string.gs_hardware_none)) +
                GameSpoofing.GPU_PRESETS.values.toList(),
            selected = GameSpoofing.GPU_PRESETS.keys.indexOf(dialog.currentGpu) + 1,
            onPick = { index ->
                state.dialog = GameSpoofing.GsDialog.Cpu(
                    dialog.packageName,
                    dialog.label,
                    GameSpoofing.GPU_PRESETS.keys.elementAtOrNull(index - 1).orEmpty(),
                    dialog.currentCpu,
                )
            },
            onDismiss = dismiss,
        )
        is GameSpoofing.GsDialog.Cpu -> SpoofChoiceDialog(
            title = stringResource(R.string.gs_select_cpu),
            options = listOf(stringResource(R.string.gs_hardware_none)) +
                GameSpoofing.CPU_PRESETS.values.toList(),
            selected = GameSpoofing.CPU_PRESETS.keys.indexOf(dialog.currentCpu) + 1,
            onPick = { index ->
                val cpu = GameSpoofing.CPU_PRESETS.keys.elementAtOrNull(index - 1).orEmpty()
                if (cpu.isEmpty() && dialog.gpu.isEmpty()) {
                    onRemove(dialog.packageName)
                } else {
                    onSaveHardware(dialog.packageName, dialog.label, dialog.gpu, cpu)
                }
            },
            onDismiss = dismiss,
        )
        is GameSpoofing.GsDialog.Profile -> {
            val current = state.games.firstOrNull { it.packageName == dialog.packageName }?.props
            val names = GameSpoofing.PRESET_PROFILES.map { it.first } +
                stringResource(R.string.gs_profile_custom_ellipsis)
            SpoofChoiceDialog(
                title = stringResource(R.string.gs_select_profile),
                options = names,
                summaries = GameSpoofing.PRESET_PROFILES.map { (_, props) ->
                    props.entries.joinToString(" \u00b7 ") { it.value }
                } + null,
                selected = current?.let { props ->
                    GameSpoofing.PRESET_PROFILES.indexOfFirst { it.second == props }
                        .takeIf { it >= 0 } ?: GameSpoofing.PRESET_PROFILES.size
                } ?: -1,
                onPick = { index ->
                    onPickProfile(
                        dialog.packageName, dialog.label,
                        GameSpoofing.PRESET_PROFILES.getOrNull(index)?.second,
                    )
                },
                onDismiss = dismiss,
            )
        }
        is GameSpoofing.GsDialog.EditProps -> EditPropsDialog(
            label = dialog.label,
            initial = dialog.props,
            onSave = { props -> dismiss(); onSaveProps(dialog.packageName, dialog.label, props) },
            onDismiss = dismiss,
        )
        is GameSpoofing.GsDialog.Remove -> SpoofConfirmDialog(
            title = stringResource(R.string.gs_remove_title),
            message = stringResource(R.string.gs_remove_message, dialog.label),
            confirmText = stringResource(R.string.gs_remove),
            onConfirm = { onRemove(dialog.packageName) },
            onDismiss = dismiss,
            destructive = true,
        )
    }
}

@Composable
private fun AddGameDialog(
    loading: Boolean,
    apps: List<AppListEntry>,
    configured: Set<String>,
    onPick: (AppListEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(false) }
    val filtered = remember(apps, configured, query, showSystem) {
        val q = query.trim().lowercase()
        apps.filter { app ->
            app.packageName !in configured &&
                (showSystem || !app.isSystem) &&
                (q.isEmpty() || app.label.lowercase().contains(q) ||
                    app.packageName.lowercase().contains(q))
        }
    }
    SpoofDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.gs_select_app),
        text = {
            Column {
                AppPickerSearchField(query = query, onQueryChange = { query = it })
                Spacer(Modifier.height(8.dp))
                FilterChip(
                    selected = showSystem,
                    onClick = { showSystem = !showSystem },
                    label = { Text(stringResource(R.string.show_system_apps)) },
                    leadingIcon = {
                        Icon(
                            Icons.Rounded.PhoneAndroid,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )
                Spacer(Modifier.height(8.dp))
                Box(Modifier.heightIn(min = 120.dp, max = 400.dp)) {
                    when {
                        loading -> SpoofingLoadingBox(Modifier.padding(vertical = 32.dp))
                        filtered.isEmpty() -> Text(
                            text = stringResource(R.string.gs_no_matching_apps),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 24.dp),
                        )
                        else -> LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(SpoofDimens.RowGap),
                        ) {
                            itemsIndexed(filtered, key = { _, a -> a.packageName }) { index, app ->
                                Surface(
                                    onClick = { onPick(app) },
                                    shape = SpoofShapes.forIndex(index, filtered.size),
                                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        AppIconOrPlaceholder(app.packageName, app.icon, APP_ICON_SIZE_LIST)
                                        Spacer(Modifier.width(12.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                app.label,
                                                style = MaterialTheme.typography.bodyLarge,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                app.packageName,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

/** Key/value editor for one game. Blank rows are ignored; an empty result removes the game. */
@Composable
private fun EditPropsDialog(
    label: String,
    initial: Map<String, String>,
    onSave: (Map<String, String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val rows = remember {
        mutableStateListOf<Pair<String, String>>().apply {
            addAll(initial.entries.map { it.key to it.value })
            if (isEmpty()) add("MODEL" to "")
        }
    }
    var error by remember { mutableStateOf<String?>(null) }
    val invalidKey = stringResource(R.string.gs_invalid_key)
    val duplicateKey = stringResource(R.string.gs_duplicate_key)

    SpoofDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.gs_edit_app, label),
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.gs_edit_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                rows.forEachIndexed { index, (key, value) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            SpoofTextField(
                                value = key,
                                onValueChange = { rows[index] = it.trim().uppercase() to value; error = null },
                                label = stringResource(R.string.gs_key),
                            )
                            SpoofTextField(
                                value = value,
                                onValueChange = { rows[index] = key to it; error = null },
                                label = stringResource(R.string.gs_value),
                            )
                        }
                        IconButton(onClick = { rows.removeAt(index) }) {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.gs_remove),
                            )
                        }
                    }
                }
                TextButton(onClick = { rows.add("" to "") }) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.gs_add_property))
                }
                error?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val filled = rows.map { (k, v) -> k.trim() to v.trim() }
                    .filter { (k, v) -> k.isNotEmpty() && v.isNotEmpty() }
                val bad = filled.firstOrNull { !GameSpoofing.FIELD_NAME_REGEX.matches(it.first) }
                when {
                    bad != null -> error = invalidKey.format(bad.first)
                    filled.map { it.first }.toSet().size != filled.size -> error = duplicateKey
                    else -> onSave(linkedMapOf(*filled.toTypedArray()))
                }
            }) { Text(stringResource(R.string.gs_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}
