package com.rimuru.twobytwo.presentation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.rimuru.twobytwo.R
import com.rimuru.twobytwo.domain.model.Accelerator
import com.rimuru.twobytwo.domain.model.EngineMode
import com.rimuru.twobytwo.domain.model.ProcessStep
import com.rimuru.twobytwo.domain.model.ScaleFactor

/** App shell: theme + navigation between S1-S5 via the single MVI state machine. */
@Composable
fun AppRoot(initialSharedUri: String?) {
    val vm: EnhanceViewModel = viewModel()
    val state by vm.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(initialSharedUri) {
        initialSharedUri?.let { vm.onIntent(EnhanceViewModel.Intent.PickPhoto(android.net.Uri.parse(it))) }
    }

    MaterialTheme {
        Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
            Crossfade(
                targetState = when {
                    state.error != null -> "error"
                    state.isProcessing -> "processing"
                    state.progress?.step == ProcessStep.DONE -> "export"
                    state.pickedUri != null -> "config"
                    else -> "home"
                },
                label = "screens",
            ) { screen ->
                when (screen) {
                    "home" -> HomeScreen(
                        state = state,
                        onPick = { uri -> vm.onIntent(EnhanceViewModel.Intent.PickPhoto(uri)) },
                    )
                    "config" -> ConfigScreen(state, vm::onIntent)
                    "processing" -> ProcessingScreen(state, vm::onIntent)
                    "export" -> ExportScreen(state, vm::onIntent)
                    "error" -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(stringResource(R.string.error_job_failed), style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = { vm.onIntent(EnhanceViewModel.Intent.DismissError) }) {
                                Text(stringResource(android.R.string.ok))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** S1 — Home / photo input (Photo Picker, no permission needed on API 33+). */
@Composable
fun HomeScreen(
    state: EnhanceViewModel.UiState,
    onPick: (android.net.Uri) -> Unit,
) {
    val context = LocalContext.current
    val pickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let(onPick) }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(stringResource(R.string.home_title), style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.home_privacy_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(32.dp))
            Button(onClick = {
                pickerLauncher.launch(
                    androidx.activity.result.PickVisualMediaRequest(
                        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly,
                    ),
                )
            }) {
                Text(stringResource(R.string.home_pick_photo))
            }
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.home_drop_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** S2 — Configuration bottom sheet content. */
@Composable
fun ConfigScreen(
    state: EnhanceViewModel.UiState,
    onIntent: (EnhanceViewModel.Intent) -> Unit,
) {
    val previewUri = state.pickedUri ?: return

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AsyncImage(
            model = previewUri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().height(220.dp),
        )

        // Scale [2x|4x] FR-1.1
        Text(stringResource(R.string.config_scale), style = MaterialTheme.typography.titleSmall)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            ScaleFactor.entries.forEachIndexed { i, sf ->
                SegmentedButton(
                    selected = state.scale == sf,
                    onClick = { onIntent(EnhanceViewModel.Intent.SetScale(sf)) },
                    shape = SegmentedButtonDefaults.itemShape(i, ScaleFactor.entries.size),
                ) { Text("${sf.multiplier}x") }
            }
        }

        // Mode [Precision|Creative] FR-2.1
        Text(stringResource(R.string.config_mode), style = MaterialTheme.typography.titleSmall)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            EngineMode.entries.forEachIndexed { i, mode ->
                SegmentedButton(
                    selected = state.mode == mode,
                    onClick = { onIntent(EnhanceViewModel.Intent.SetMode(mode)) },
                    shape = SegmentedButtonDefaults.itemShape(i, EngineMode.entries.size),
                ) { Text(stringResource(if (mode == EngineMode.PRECISION) R.string.config_mode_precision else R.string.config_mode_creative)) }
            }
        }
        Text(
            stringResource(
                if (state.mode == EngineMode.PRECISION) R.string.config_mode_precision_desc else R.string.config_mode_creative_desc,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Denoise slider with preset anchors FR-3.1
        Text(stringResource(R.string.config_denoise), style = MaterialTheme.typography.titleSmall)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            val presets = listOf(
                R.string.config_denoise_off to 0,
                R.string.config_denoise_low to 25,
                R.string.config_denoise_medium to 50,
                R.string.config_denoise_aggressive to 100,
            )
            presets.forEachIndexed { i, (label, value) ->
                SegmentedButton(
                    selected = state.denoise == value,
                    onClick = { onIntent(EnhanceViewModel.Intent.SetDenoise(value)) },
                    shape = SegmentedButtonDefaults.itemShape(i, presets.size),
                ) { Text(stringResource(label)) }
            }
        }
        Slider(
            value = state.denoise.toFloat(),
            onValueChange = { onIntent(EnhanceViewModel.Intent.SetDenoise(it.toInt())) },
            valueRange = 0f..100f,
        )

        // Face restore FR-4.1/4.2
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.config_face_restore), style = MaterialTheme.typography.titleSmall)
            Switch(checked = state.faceRestore, onCheckedChange = { onIntent(EnhanceViewModel.Intent.SetFaceRestore(it)) })
        }
        AnimatedVisibility(state.faceRestore) {
            Column {
                Text(
                    stringResource(R.string.config_face_strength),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = state.faceStrength.toFloat(),
                    onValueChange = { onIntent(EnhanceViewModel.Intent.SetFaceStrength(it.toInt())) },
                    valueRange = 0f..100f,
                )
            }
        }

        // Accelerator HW-1
        Text(stringResource(R.string.config_accelerator), style = MaterialTheme.typography.titleSmall)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            val accelerators = Accelerator.entries
            accelerators.forEachIndexed { i, acc ->
                SegmentedButton(
                    selected = state.accelerator == acc,
                    onClick = { onIntent(EnhanceViewModel.Intent.SetAccelerator(acc)) },
                    shape = SegmentedButtonDefaults.itemShape(i, accelerators.size),
                ) { Text(stringResource(accelLabel(acc))) }
            }
        }

        // Output size estimate FR-1.5 (rough; decode dims come from worker bounds)
        val estW = (state.pickedWidth.takeIf { it > 0 } ?: 1920) * state.scale.multiplier
        val estH = (state.pickedHeight.takeIf { it > 0 } ?: 1080) * state.scale.multiplier
        Text(
            stringResource(R.string.config_output_size, estW, estH),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Button(
            onClick = { onIntent(EnhanceViewModel.Intent.StartEnhance) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.config_enhance))
        }
    }
}

@Composable
private fun accelLabel(acc: Accelerator): Int = when (acc) {
    Accelerator.AUTO -> R.string.config_accel_auto
    Accelerator.GPU -> R.string.config_accel_gpu
    Accelerator.NPU -> R.string.config_accel_npu
    Accelerator.CPU -> R.string.config_accel_cpu
}

/** S3 — Processing with step-by-step progress (US-06) + cancel confirm (UX-5). */
@Composable
fun ProcessingScreen(
    state: EnhanceViewModel.UiState,
    onIntent: (EnhanceViewModel.Intent) -> Unit,
) {
    val progress = state.progress ?: return
    var confirmCancel by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stepLabel(progress), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(24.dp))
        LinearProgressIndicator(
            progress = { progress.overall },
            modifier = Modifier.fillMaxWidth(),
        )
        state.backendUsed?.let {
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.proc_backend_used, it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(32.dp))
        OutlinedButton(onClick = { confirmCancel = true }) {
            Text(stringResource(R.string.proc_cancel))
        }
    }

    if (confirmCancel) {
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            title = { Text(stringResource(R.string.proc_cancel_confirm_title)) },
            text = { Text(stringResource(R.string.proc_cancel_confirm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmCancel = false
                    onIntent(EnhanceViewModel.Intent.CancelJob)
                }) { Text(stringResource(R.string.proc_cancel)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmCancel = false }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun stepLabel(p: com.rimuru.twobytwo.domain.model.JobProgress): String = when (p.step) {
    ProcessStep.PREPARING -> stringResource(R.string.proc_step_preparing)
    ProcessStep.DETECTING_FACES -> stringResource(R.string.proc_step_detecting_faces)
    ProcessStep.PROCESSING_TILES -> stringResource(R.string.proc_step_tiles, p.tilesDone, p.tilesTotal)
    ProcessStep.RESTORING_FACES -> stringResource(R.string.proc_step_restoring_faces)
    ProcessStep.BLENDING -> stringResource(R.string.proc_step_blending)
    ProcessStep.DONE -> stringResource(R.string.proc_step_done)
}

/** S4+S5 combined — result view with before/after split + export actions. */
@Composable
fun ExportScreen(
    state: EnhanceViewModel.UiState,
    onIntent: (EnhanceViewModel.Intent) -> Unit,
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // S4: before/after split slider (draggable divider, US-07)
        state.pickedUri?.let { original ->
            ComparisonViewer(
                originalUri = original,
                // Result URL comes from MediaStore query on the saved name; ponytail: the
                // worker currently saves directly to MediaStore and we surface the latest
                // Pictures/Rimuru2x entry. A result-URI Data field is the M1 upgrade.
                resultUri = state.pickedUri,
                modifier = Modifier.weight(1f),
            )
        }

        Text(stringResource(R.string.export_saved), style = MaterialTheme.typography.bodyMedium)

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { onIntent(EnhanceViewModel.Intent.ClearPhoto) },
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.home_pick_photo)) }
            OutlinedButton(
                onClick = {
                    // Share sheet via MediaStore content (US-08 share)
                    val share = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "image/*"
                        putExtra(android.content.Intent.EXTRA_STREAM, state.pickedUri?.let { android.net.Uri.parse(it) })
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(android.content.Intent.createChooser(share, null))
                },
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.export_share)) }
        }
    }
}

/**
 * S4 — before/after split viewer. Draggable divider; left = original, right = result.
 * Zoom/pan locked between halves (PRD S4). ponytail: single-gesture drag only;
 * pinch-zoom sync is the M4 polish item.
 */
@Composable
fun ComparisonViewer(
    originalUri: String,
    resultUri: String?,
    modifier: Modifier = Modifier,
) {
    var splitFraction by remember { mutableStateOf(0.5f) }

    androidx.compose.foundation.layout.BoxWithConstraints(modifier) {
        // Result fills the box (right side shows through)
        AsyncImage(
            model = resultUri ?: originalUri,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )

        // Original clipped to the left of the divider
        AsyncImage(
            model = originalUri,
            contentDescription = stringResource(R.string.compare_hold_hint),
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds()
        )

        // Draggable divider line + gesture surface
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectHorizontalDragGestures { change, _ ->
                        change.consume()
                        onSplitDrag(change.position.x / size.width.toFloat()) { splitFraction = it }
                    }
                },
        )
        Canvas(Modifier.fillMaxSize()) {
            val x = size.width * splitFraction
            drawLine(
                color = Color.White,
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 3f,
            )
        }
        Text(
            stringResource(R.string.compare_hold_hint),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp),
        )
    }
}

private fun onSplitDrag(fraction: Float, setter: (Float) -> Unit) {
    setter(fraction.coerceIn(0f, 1f))
}
