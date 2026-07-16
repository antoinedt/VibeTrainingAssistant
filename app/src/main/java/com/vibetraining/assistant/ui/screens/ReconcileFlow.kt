package com.vibetraining.assistant.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.vibetraining.assistant.data.StravaActivity
import com.vibetraining.assistant.data.SyncReconciler
import org.json.JSONArray

private enum class Step { Match, Type, Intensity, Effort, Injury, Recap }

/** Sentinel selection in the match picker for "no matching plan". */
private const val OTHER = -1

/**
 * Walks the user through each newly-synced activity one at a time: confirm which
 * planned activity it matches, then (for runs only) capture perceived difficulty,
 * injury status and a recap. Each answered activity is written into [weeks]
 * immediately via [SyncReconciler], so later activities see the updated plan.
 * [onFinished] fires once every activity has been processed.
 */
@Composable
fun SyncReconcileDialog(
    activities: List<StravaActivity>,
    weeks: JSONArray,
    onFinished: () -> Unit
) {
    var index by remember { mutableIntStateOf(0) }
    var step by remember { mutableStateOf(Step.Match) }
    var matchedIndex by remember { mutableStateOf<Int?>(null) }
    var cls by remember { mutableStateOf<String?>(null) }
    var matchSel by remember { mutableIntStateOf(Int.MIN_VALUE) }
    var typeSel by remember { mutableStateOf<String?>(null) }
    var intensity by remember { mutableIntStateOf(0) }
    var effort by remember { mutableIntStateOf(0) }
    var injury by remember { mutableIntStateOf(0) }
    var injuryComment by remember { mutableStateOf("") }
    var recap by remember { mutableStateOf("") }

    if (index >= activities.size) {
        LaunchedEffect(Unit) { onFinished() }
        return
    }

    val activity = activities[index]
    val isRun = SyncReconciler.isRun(activity.type)
    val pending = remember(index) { SyncReconciler.pendingOptionsForActivity(weeks, activity) }

    fun resetDraft() {
        step = Step.Match; matchedIndex = null; cls = null
        matchSel = Int.MIN_VALUE; typeSel = null
        intensity = 0; effort = 0; injury = 0; injuryComment = ""; recap = ""
    }

    fun commitAndNext(feedback: SyncReconciler.RunFeedback?) {
        SyncReconciler.applyActivity(weeks, activity, cls, matchedIndex, feedback)
        index += 1
        resetDraft()
    }

    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
    ) {
        Card(shape = RoundedCornerShape(20.dp)) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .heightIn(max = 600.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Fixed header.
                Text(
                    "Activity ${index + 1} of ${activities.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                ActivityHeader(activity, isRun)
                HorizontalDivider()

                // Only this middle area scrolls, so the action buttons in the
                // fixed footer below stay visible no matter how many options there are.
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    when (step) {
                        Step.Match -> {
                            Text("Which planned activity does this correspond to?",
                                style = MaterialTheme.typography.titleSmall)
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                pending.forEachIndexed { i, opt ->
                                    ChoiceRow(opt.label, matchSel == i) { matchSel = i }
                                }
                                ChoiceRow("Other — no matching plan", matchSel == OTHER) { matchSel = OTHER }
                            }
                        }
                        Step.Type -> {
                            Text("What kind of run was it?", style = MaterialTheme.typography.titleSmall)
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                SyncReconciler.RUN_TYPES.forEach { (key, label) ->
                                    ChoiceRow(label, typeSel == key) { typeSel = key }
                                }
                            }
                        }
                        Step.Intensity -> {
                            Text("Intensity — how hard was the breathing? (1–10)",
                                style = MaterialTheme.typography.titleSmall)
                            Text("Cardio effort only, ignore the legs.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            ScaleList(SyncReconciler.INTENSITY, intensity) { intensity = it }
                        }
                        Step.Effort -> {
                            Text("Effort — how hard overall? (1–10)",
                                style = MaterialTheme.typography.titleSmall)
                            Text("Legs, fatigue, how tough it felt given how you came in — " +
                                "e.g. an easy-breathing long run can still be high effort.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            ScaleList(SyncReconciler.EFFORT, effort) { effort = it }
                        }
                        Step.Injury -> {
                            Text("Injury / pain status (1–10)", style = MaterialTheme.typography.titleSmall)
                            Text("1 = fully healthy · 10 = badly hurt",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            ScaleList(SyncReconciler.INJURY, injury) { injury = it }
                            OutlinedTextField(
                                value = injuryComment,
                                onValueChange = { injuryComment = it },
                                label = { Text("Details (optional)") },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 2
                            )
                        }
                        Step.Recap -> {
                            Text("Training recap", style = MaterialTheme.typography.titleSmall)
                            OutlinedTextField(
                                value = recap,
                                onValueChange = { recap = it },
                                label = { Text("How did it go?") },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 4
                            )
                        }
                    }
                }

                // Pinned footer — the step's actions, always visible.
                when (step) {
                    Step.Match -> StepButtons(
                        primaryLabel = "Continue",
                        primaryEnabled = matchSel != Int.MIN_VALUE,
                        onPrimary = {
                            if (matchSel == OTHER) {
                                matchedIndex = null
                                if (isRun) step = Step.Type
                                else { cls = SyncReconciler.nonRunClass(activity.type); commitAndNext(null) }
                            } else {
                                val opt = pending[matchSel]
                                matchedIndex = opt.index
                                cls = opt.cls
                                if (isRun) step = Step.Intensity else commitAndNext(null)
                            }
                        },
                        secondaryLabel = "Skip",
                        onSecondary = {
                            matchedIndex = null
                            cls = SyncReconciler.nonRunClass(activity.type)
                                ?: if (isRun) SyncReconciler.distanceClass(activity) else null
                            commitAndNext(null)
                        }
                    )
                    Step.Type -> StepButtons(
                        primaryLabel = "Continue",
                        primaryEnabled = typeSel != null,
                        onPrimary = { cls = typeSel; step = Step.Intensity },
                        secondaryLabel = "Back",
                        onSecondary = { step = Step.Match }
                    )
                    Step.Intensity -> StepButtons(
                        primaryLabel = "Continue",
                        primaryEnabled = intensity > 0,
                        onPrimary = { step = Step.Effort },
                        secondaryLabel = "Back",
                        onSecondary = { step = if (matchedIndex == null) Step.Type else Step.Match }
                    )
                    Step.Effort -> StepButtons(
                        primaryLabel = "Continue",
                        primaryEnabled = effort > 0,
                        onPrimary = { step = Step.Injury },
                        secondaryLabel = "Back",
                        onSecondary = { step = Step.Intensity }
                    )
                    Step.Injury -> StepButtons(
                        primaryLabel = "Continue",
                        primaryEnabled = injury > 0,
                        onPrimary = { step = Step.Recap },
                        secondaryLabel = "Back",
                        onSecondary = { step = Step.Effort }
                    )
                    Step.Recap -> StepButtons(
                        primaryLabel = if (index + 1 < activities.size) "Save & next" else "Save & finish",
                        primaryEnabled = true,
                        onPrimary = {
                            commitAndNext(
                                SyncReconciler.RunFeedback(intensity, effort, injury, injuryComment, recap)
                            )
                        },
                        secondaryLabel = "Back",
                        onSecondary = { step = Step.Injury }
                    )
                }
            }
        }
    }
}

private enum class EditStep { Intensity, Effort, Injury, Recap }

/**
 * Edits an already-logged activity's ratings and notes. Unlike the sync wizard
 * there's no match/type step — the activity already exists; this walks the four
 * feedback axes pre-filled with [initial] and hands the result to [onSave].
 */
@Composable
fun EditActivityDialog(
    title: String,
    subtitle: String,
    initial: SyncReconciler.RunFeedback,
    saving: Boolean,
    onSave: (SyncReconciler.RunFeedback) -> Unit,
    onCancel: () -> Unit
) {
    var step by remember { mutableStateOf(EditStep.Intensity) }
    var intensity by remember { mutableIntStateOf(initial.intensity) }
    var effort by remember { mutableIntStateOf(initial.effort) }
    var injury by remember { mutableIntStateOf(initial.injury) }
    var injuryComment by remember { mutableStateOf(initial.injuryComment) }
    var recap by remember { mutableStateOf(initial.recap) }

    Dialog(
        onDismissRequest = { if (!saving) onCancel() },
        properties = DialogProperties(dismissOnBackPress = !saving, dismissOnClickOutside = false)
    ) {
        Card(shape = RoundedCornerShape(20.dp)) {
            Column(
                modifier = Modifier.padding(20.dp).heightIn(max = 600.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Edit ratings & notes", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()

                Column(
                    modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    when (step) {
                        EditStep.Intensity -> {
                            Text("Intensity — how hard was the breathing? (1–10)",
                                style = MaterialTheme.typography.titleSmall)
                            Text("Cardio effort only, ignore the legs.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            ScaleList(SyncReconciler.INTENSITY, intensity) { intensity = it }
                        }
                        EditStep.Effort -> {
                            Text("Effort — how hard overall? (1–10)",
                                style = MaterialTheme.typography.titleSmall)
                            Text("Legs, fatigue, how tough it felt given how you came in.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            ScaleList(SyncReconciler.EFFORT, effort) { effort = it }
                        }
                        EditStep.Injury -> {
                            Text("Injury / pain status (1–10)", style = MaterialTheme.typography.titleSmall)
                            Text("1 = fully healthy · 10 = badly hurt",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            ScaleList(SyncReconciler.INJURY, injury) { injury = it }
                            OutlinedTextField(
                                value = injuryComment,
                                onValueChange = { injuryComment = it },
                                label = { Text("Details (optional)") },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 2
                            )
                        }
                        EditStep.Recap -> {
                            Text("Training recap", style = MaterialTheme.typography.titleSmall)
                            OutlinedTextField(
                                value = recap,
                                onValueChange = { recap = it },
                                label = { Text("How did it go?") },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 4
                            )
                        }
                    }
                }

                if (saving) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Saving…", style = MaterialTheme.typography.bodySmall)
                    }
                } else when (step) {
                    EditStep.Intensity -> StepButtons(
                        primaryLabel = "Continue", primaryEnabled = intensity > 0,
                        onPrimary = { step = EditStep.Effort },
                        secondaryLabel = "Cancel", onSecondary = onCancel
                    )
                    EditStep.Effort -> StepButtons(
                        primaryLabel = "Continue", primaryEnabled = effort > 0,
                        onPrimary = { step = EditStep.Injury },
                        secondaryLabel = "Back", onSecondary = { step = EditStep.Intensity }
                    )
                    EditStep.Injury -> StepButtons(
                        primaryLabel = "Continue", primaryEnabled = injury > 0,
                        onPrimary = { step = EditStep.Recap },
                        secondaryLabel = "Back", onSecondary = { step = EditStep.Effort }
                    )
                    EditStep.Recap -> StepButtons(
                        primaryLabel = "Save", primaryEnabled = true,
                        onPrimary = {
                            onSave(SyncReconciler.RunFeedback(intensity, effort, injury, injuryComment, recap))
                        },
                        secondaryLabel = "Back", onSecondary = { step = EditStep.Injury }
                    )
                }
            }
        }
    }
}

@Composable
private fun ActivityHeader(activity: StravaActivity, isRun: Boolean) {
    val km = activity.distanceMeters / 1000.0
    val minutes = Math.max(1, Math.round(activity.movingTimeSec / 60.0).toInt())
    val detail = if (isRun && km > 0) "%.1f km · %d min".format(km, minutes) else "$minutes min"
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(activity.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "${SyncReconciler.dayLabelFor(activity)} · ${activity.type} · $detail",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ScaleList(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Column {
        labels.forEachIndexed { i, label ->
            val n = i + 1
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(selected = selected == n, onClick = { onSelect(n) })
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = selected == n, onClick = { onSelect(n) })
                Text("$n", fontWeight = FontWeight.Bold, modifier = Modifier.width(26.dp))
                Text(label, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun StepButtons(
    primaryLabel: String,
    primaryEnabled: Boolean,
    onPrimary: () -> Unit,
    secondaryLabel: String,
    onSecondary: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onSecondary) { Text(secondaryLabel) }
        Button(onClick = onPrimary, enabled = primaryEnabled) { Text(primaryLabel) }
    }
}
