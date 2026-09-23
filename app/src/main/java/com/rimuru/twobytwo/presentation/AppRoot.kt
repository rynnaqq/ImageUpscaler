package com.rimuru.twobytwo.presentation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.rimuru.twobytwo.R
import com.rimuru.twobytwo.domain.model.Accelerator
import com.rimuru.twobytwo.domain.model.EngineMode
import com.rimuru.twobytwo.domain.model.ProcessStep
import com.rimuru.twobytwo.domain.model.ScaleFactor
import com.rimuru.twobytwo.ui.RimuruTheme

/** App shell: theme + navigation between S1-S5 via the single MVI state machine. */
@Composable
fun AppRoot(initialSharedUri: String?) {
    val vm: EnhanceViewModel = viewModel()
    val state by vm.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(initialSharedUri) {
        initialSharedUri?.let { vm.onIntent(EnhanceViewModel.Intent.PickPhoto(android.net.Uri.parse(it))) }
    }

    RimuruTheme {
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
                    "home" -> HomeScreen(state) { uri -> vm.onIntent(EnhanceViewModel.Intent.PickPhoto(uri)) }
                    "config" -> ConfigScreen(state, vm::onIntent)
                    "processing" -> ProcessingScreen(state, vm::onIntent)
                    "export" -> ExportScreen(state, vm::onIntent)
                    "error" -> Box(
                        Modifier.fillMaxSize().padding(padding),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                stringResource(R.string.error_job_failed),
                                style = MaterialTheme.typography.titleMedium,
                            )
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: EnhanceViewModel.UiState,
    onPick: (android.net.Uri) -> Unit,
) {
    val pickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let(onPick) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1.1f))

            // App mark — the launcher art, circular
            AsyncImage(
                model = "android.resource://com.rimuru.twobytwo/mipmap-xxxhdpi/ic_launcher_round",
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(112.dp).clip(CircleShape),
            )
            Spacer(Modifier.height(24.dp))
            Text(
                stringResource(R.string.home_title),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.home_privacy_note),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.weight(1f))

            Button(
                onClick = {
                    pickerLauncher.launch(
                        androidx.activity.result.PickVisualMediaRequest(
                            androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly,
                        ),
                    )
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            ) {
                Text(stringResource(R.string.home_pick_photo), style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.height(14.dp))
            Text(
                stringResource(R.string.home_drop_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.weight(0.7f))
        }
    }
}

/** S2 — Configuration screen with grouped setting cards. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigScreen(
    state: EnhanceViewModel.UiState,
    onIntent: (EnhanceViewModel.Intent) -> Unit,
) {
    val previewUri = state.pickedUri ?: return

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                navigationIcon = {
                    IconButton(onClick = { onIntent(EnhanceViewModel.Intent.ClearPhoto) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(android.R.string.cancel))
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            AsyncImage(
                model = previewUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .clip(RoundedCornerShape(20.dp)),
            )

            SettingsCard(title = stringResource(R.string.config_scale)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    ScaleFactor.entries.forEachIndexed { i, sf ->
                        SegmentedButton(
                            selected = state.scale == sf,
                            onClick = { onIntent(EnhanceViewModel.Intent.SetScale(sf)) },
                            shape = SegmentedButtonDefaults.itemShape(i, ScaleFactor.entries.size),
                        ) { Text("${sf.multiplier}x") }
                    }
                }
            }

            SettingsCard(title = stringResource(R.string.config_mode)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    EngineMode.entries.forEachIndexed { i, mode ->
                        SegmentedButton(
                            selected = state.mode == mode,
                            onClick = { onIntent(EnhanceViewModel.Intent.SetMode(mode)) },
                            shape = SegmentedButtonDefaults.itemShape(i, EngineMode.entries.size),
                        ) {
                            Text(
                                stringResource(
                                    if (mode == EngineMode.PRECISION) R.string.config_mode_precision else R.string.config_mode_creative,
                                ),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(
                        if (state.mode == EngineMode.PRECISION) R.string.config_mode_precision_desc else R.string.config_mode_creative_desc,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SettingsCard(title = stringResource(R.string.config_denoise)) {
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
                Spacer(Modifier.height(4.dp))
                Slider(
                    value = state.denoise.toFloat(),
                    onValueChange = { onIntent(EnhanceViewModel.Intent.SetDenoise(it.toInt())) },
                    valueRange = 0f..100f,
                )
            }

            SettingsCard(title = stringResource(R.string.config_face_restore)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(R.string.config_face_restore),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Switch(
                        checked = state.faceRestore,
                        onCheckedChange = { onIntent(EnhanceViewModel.Intent.SetFaceRestore(it)) },
                    )
                }
                AnimatedVisibility(state.faceRestore) {
                    Column {
                        Text(
                            stringResource(R.string.config_face_strength),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Slider(
                            value = state.faceStrength.toFloat(),
                            onValueChange = { onIntent(EnhanceViewModel.Intent.SetFaceStrength(it.toInt())) },
                            valueRange = 0f..100f,
                        )
                    }
                }
            }

            SettingsCard(title = stringResource(R.string.config_accelerator)) {
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
            }

            val estW = (state.pickedWidth.takeIf { it > 0 } ?: 1920) * state.scale.multiplier
            val estH = (state.pickedHeight.takeIf { it > 0 } ?: 1080) * state.scale.multiplier
            Text(
                stringResource(R.string.config_output_size, estW, estH),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )

            Button(
                onClick = { onIntent(EnhanceViewModel.Intent.StartEnhance) },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text(stringResource(R.string.config_enhance), style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** Elevated card wrapping one settings group. */
@Composable
private fun SettingsCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            content()
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

    Scaffold(containerColor = MaterialTheme.colorScheme.surface) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                stepLabel(progress),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(28.dp))
            LinearProgressIndicator(
                progress = { progress.overall },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
            )
            state.backendUsed?.let {
                Spacer(Modifier.height(14.dp))
                Text(
                    stringResource(R.string.proc_backend_used, it),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(40.dp))
            OutlinedButton(onClick = { confirmCancel = true }, shape = RoundedCornerShape(12.dp)) {
                Text(stringResource(R.string.proc_cancel))
            }
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportScreen(
    state: EnhanceViewModel.UiState,
    onIntent: (EnhanceViewModel.Intent) -> Unit,
) {
    val context = LocalContext.current
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.compare_title)) },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            state.pickedUri?.let { original ->
                ComparisonViewer(
                    originalUri = original,
                    // ponytail: worker saves directly to MediaStore; result-URI Data
                    // field is the M1 upgrade so the right half shows the real output.
                    resultUri = state.pickedUri,
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(20.dp)),
                )
            }

            Text(
                stringResource(R.string.export_saved),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { onIntent(EnhanceViewModel.Intent.ClearPhoto) },
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                ) { Text(stringResource(R.string.home_pick_photo)) }
                OutlinedButton(
                    onClick = {
                        val share = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "image/*"
                            putExtra(android.content.Intent.EXTRA_STREAM, state.pickedUri?.let { android.net.Uri.parse(it) })
                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(android.content.Intent.createChooser(share, null))
                    },
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                ) { Text(stringResource(R.string.export_share)) }
            }
        }
    }
}

/**
 * S4 — before/after split viewer. Draggable divider; left = original, right = result.
 * ponytail: single-gesture drag only; pinch-zoom sync is the M4 polish item.
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

        // Draggable divider gesture surface
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectHorizontalDragGestures { change, _ ->
                        change.consume()
                        val f = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                        splitFraction = f
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
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(12.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}
