@file:OptIn(ExperimentalMaterial3Api::class)

package sh.zeron.android.design.lab

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.zeron.android.design.ActionSheetStyle
import sh.zeron.android.design.ZeronTheme

/**
 * Variant 3 of every row: stock Material 3 1.4.0 (the BOM pin) in the
 * default light/dark scheme, as the behaviour reference — what the platform
 * components do for role, focus, keyboard, back and IME out of the box.
 * Debug-only: material3 is a `debugImplementation`, and `src/main` stays
 * Material-free (NoMaterialImportsTest).
 */
@Composable
internal fun MaterialVariant(component: LabComponent) {
    MaterialTheme(colorScheme = if (ZeronTheme.isDark) darkColorScheme() else lightColorScheme()) {
        Surface(Modifier.fillMaxSize()) {
            when (component) {
                LabComponent.Alert -> MaterialAlert()
                LabComponent.ActionSheet -> MaterialActionSheet()
                LabComponent.Menu -> MaterialMenu()
                LabComponent.BottomSheet -> MaterialBottomSheet()
                LabComponent.Switch -> MaterialSwitch()
                LabComponent.Toast -> MaterialToast()
                LabComponent.Segmented -> MaterialSegmented()
                LabComponent.ProviderTabs -> MaterialProviderTabs()
                LabComponent.PullToRefresh -> MaterialPullToRefresh()
                LabComponent.Search -> MaterialSearch()
            }
        }
    }
}

@Composable
private fun MaterialControls(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}

@Composable
private fun MaterialAlert() {
    var delete by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("Fix flaky test") }
    MaterialControls {
        Button(onClick = { delete = true }) { Text("Delete session?") }
        Button(onClick = { rename = true }) { Text("Rename Session (field)") }
    }
    if (delete) {
        AlertDialog(
            onDismissRequest = { delete = false },
            title = { Text("Delete session?") },
            text = { Text("This deletes the session on Studio.") },
            confirmButton = {
                TextButton(onClick = { delete = false }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { delete = false }) { Text("Cancel") } },
        )
    }
    if (rename) {
        AlertDialog(
            onDismissRequest = { rename = false },
            title = { Text("Rename Session") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = { rename = false }, enabled = name.isNotBlank()) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { rename = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun MaterialActionSheet() {
    var open by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    MaterialControls { Button(onClick = { open = true }) { Text("Show action sheet") } }
    if (open) {
        val close: () -> Unit = {
            scope.launch { sheetState.hide() }.invokeOnCompletion { if (!sheetState.isVisible) open = false }
        }
        ModalBottomSheet(onDismissRequest = { open = false }, sheetState = sheetState) {
            Text(
                SampleActionSheet.title.orEmpty(),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            SampleActionSheet.actions.forEach { action ->
                ListItem(
                    headlineContent = {
                        Text(
                            action.title,
                            color = if (action.style == ActionSheetStyle.Destructive) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    },
                    modifier = Modifier.clickable(role = Role.Button, onClick = close),
                )
            }
            ListItem(
                headlineContent = { Text("Cancel") },
                modifier = Modifier.navigationBarsPadding().clickable(role = Role.Button, onClick = close),
            )
        }
    }
}

@Composable
private fun MaterialMenu() {
    var expanded by remember { mutableStateOf(false) }
    var pinned by remember { mutableStateOf(false) }
    MaterialControls {
        Box {
            Button(onClick = { expanded = true }) { Text("Session ⋯") }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text(if (pinned) "Unpin" else "Pin") },
                    onClick = {
                        pinned = !pinned
                        expanded = false
                    },
                )
                DropdownMenuItem(text = { Text("Copy Transcript") }, onClick = { expanded = false })
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Archive", color = MaterialTheme.colorScheme.error) },
                    onClick = { expanded = false },
                )
            }
        }
    }
}

@Composable
private fun MaterialBottomSheet() {
    var open by remember { mutableStateOf(false) }
    MaterialControls { Button(onClick = { open = true }) { Text("Show sheet") } }
    if (open) {
        ModalBottomSheet(onDismissRequest = { open = false }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).imePadding()) {
                OutlinedTextField(
                    value = "",
                    onValueChange = {},
                    placeholder = { Text("Type to raise the keyboard") },
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.fillMaxWidth().height(480.dp)) {
                    items(24) { i -> Text("Output line ${i + 1}", modifier = Modifier.padding(vertical = 6.dp)) }
                }
            }
        }
    }
}

@Composable
private fun MaterialSwitch() {
    var math by remember { mutableStateOf(true) }
    var grouped by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        listOf(
            Triple("Render Math", math, true),
            Triple("Group by Project (disabled)", grouped, false),
        ).forEachIndexed { index, (label, checked, enabled) ->
            val onChange: (Boolean) -> Unit = { value ->
                if (index == 0) {
                    math = value
                } else {
                    grouped = value
                }
            }
            // The recommended pattern: the row is the toggleable, the
            // Switch only draws (onCheckedChange = null).
            ListItem(
                headlineContent = { Text(label) },
                trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
                modifier = Modifier.toggleable(
                    value = checked,
                    enabled = enabled,
                    role = Role.Switch,
                    onValueChange = onChange,
                ),
            )
        }
    }
}

@Composable
private fun MaterialToast() {
    val host = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var field by remember { mutableStateOf("") }
    Box(Modifier.fillMaxSize()) {
        MaterialControls {
            Button(onClick = { scope.launch { host.showSnackbar("Session archived", actionLabel = "Undo") } }) {
                Text("Show snackbar")
            }
            OutlinedTextField(
                value = field,
                onValueChange = { field = it },
                placeholder = { Text("Raise the keyboard, then show it") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        SnackbarHost(
            host,
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding(),
        ) { data -> Snackbar(data) }
    }
}

@Composable
private fun MaterialSegmented() {
    var selected by remember { mutableIntStateOf(0) }
    MaterialControls {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SchemeOptions.forEachIndexed { index, label ->
                SegmentedButton(
                    selected = index == selected,
                    onClick = { selected = index },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = SchemeOptions.size),
                ) { Text(label) }
            }
        }
    }
}

@Composable
private fun MaterialProviderTabs() {
    val pager = rememberPagerState { ProviderOptions.size }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        SecondaryScrollableTabRow(selectedTabIndex = pager.currentPage) {
            ProviderOptions.forEachIndexed { index, (_, label) ->
                Tab(
                    selected = index == pager.currentPage,
                    onClick = { scope.launch { pager.animateScrollToPage(index) } },
                    text = { Text(label) },
                )
            }
        }
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { page -> ProviderPage(ProviderOptions[page].second) }
    }
}

@Composable
private fun MaterialPullToRefresh() {
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            refreshing = true
            scope.launch {
                delay(RefreshMillis)
                refreshing = false
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(Modifier.fillMaxSize()) {
            items(RefreshRows) { index ->
                ListItem(headlineContent = { Text("Session ${index + 1}") })
            }
        }
    }
}

@Composable
private fun MaterialSearch() {
    var query by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        SearchBar(
            inputField = {
                SearchBarDefaults.InputField(
                    query = query,
                    onQueryChange = { query = it },
                    onSearch = { expanded = false },
                    expanded = expanded,
                    onExpandedChange = { expanded = it },
                    placeholder = { Text("Search") },
                )
            },
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            val hits = SearchCorpus.filter { query.isBlank() || it.contains(query.trim(), ignoreCase = true) }
            hits.forEach { hit ->
                ListItem(
                    headlineContent = { Text(hit) },
                    modifier = Modifier.clickable {
                        query = hit
                        expanded = false
                    },
                )
            }
        }
        Row(Modifier.align(Alignment.Center)) {
            Text("Collapsed: tap the bar. Expanded: try predictive back.")
        }
    }
}
