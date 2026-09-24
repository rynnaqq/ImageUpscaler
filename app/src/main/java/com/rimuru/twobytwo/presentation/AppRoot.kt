package com.rimuru.twobytwo.presentation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Texture
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.abs
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
        initialSharedUri?.let { vm.onIntent(EnhanceViewModel.Intent.PickPhotos(listOf(android.net.Uri.parse(it)))) }
    }

    RimuruTheme {
        Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
            Crossfade(
                targetState = when {
                    state.error != null -> "error"
                    state.isProcessing -> "processing"
                    state.progress?.step == ProcessStep.DONE && !state.outputUri.isNullOrBlank() -> "export"
                    state.pickedUris.isNotEmpty() -> "config"
                    else -> "home"
                },
                label = "screens",
            ) { screen ->
                when (screen) {
                    "home" -> HomeScreen(state) { uris -> vm.onIntent(EnhanceViewModel.Intent.PickPhotos(uris)) }
                    "config" -> ConfigScreen(state, vm::onIntent)
                    "processing" -> ProcessingScreen(state, vm::onIntent)
                    "export" -> ExportScreen(state, vm::onIntent)
                    "error" -> Box(
                        Modifier.fillMaxSize().padding(padding),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                state.error ?: stringResource(R.string.error_job_failed),
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

/** S1 — Home / photo input (Photo Picker, multi-select up to 20 for batches). */
@Composable
fun HomeScreen(
    state: EnhanceViewModel.UiState,
    onPick: (List<android.net.Uri>) -> Unit,
) {
    val pickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia(maxItems = 20),
    ) { uris -> if (uris.isNotEmpty()) onPick(uris) }

    Scaffold(containerColor = MaterialTheme.colorScheme.surface) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1.1f))

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
                Icon(Icons.Filled.PhotoLibrary, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.home_pick_photo), style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.home_batch_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
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

/** S2 — Configuration: original flat layout, each feature with icon + description. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ConfigScreen(
    state: EnhanceViewModel.UiState,
    onIntent: (EnhanceViewModel.Intent) -> Unit,
) {
    val previewUri = state.previewUri ?: return

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.config_title)) },
                navigationIcon = {
                    IconButton(onClick = { onIntent(EnhanceViewModel.Intent.ClearPhoto) }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.navigate_back),
                        )
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
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
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
        if (state.pickedUris.size > 1) {
            Text(
                stringResource(R.string.config_batch_title, state.pickedUris.size),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        // Scale — icon + title + description + control
        FeatureRow(
            icon = Icons.Filled.SwapHoriz,
            title = stringResource(R.string.config_scale),
            description = stringResource(R.string.config_scale_desc),
        ) {
            val scales = listOf(ScaleFactor.X2, ScaleFactor.X4)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                scales.forEachIndexed { i, sf ->
                    SegmentedButton(
                        selected = state.scale == sf,
                        onClick = { onIntent(EnhanceViewModel.Intent.SetScale(sf)) },
                        shape = SegmentedButtonDefaults.itemShape(i, scales.size),
                    ) { Text("${sf.multiplier}x") }
                }
            }
        }

        // Engine mode
        FeatureRow(
            icon = Icons.Filled.AutoAwesome,
            title = stringResource(R.string.config_mode),
            description = stringResource(
                if (state.mode == EngineMode.PRECISION) R.string.config_mode_precision_desc else R.string.config_mode_creative_desc,
            ),
        ) {
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
        }

        // Denoise
        FeatureRow(
            icon = Icons.Filled.Texture,
            title = stringResource(R.string.config_denoise),
            description = stringResource(R.string.config_denoise_desc),
        ) {
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
        }

        // Face restore
        FeatureRow(
            icon = Icons.Filled.Face,
            title = stringResource(R.string.config_face_restore),
            description = stringResource(R.string.config_face_desc),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(R.string.config_face_strength),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Switch(
                    checked = state.faceRestore,
                    onCheckedChange = { onIntent(EnhanceViewModel.Intent.SetFaceRestore(it)) },
                )
            }
            AnimatedVisibility(state.faceRestore) {
                Slider(
                    value = state.faceStrength.toFloat(),
                    onValueChange = { onIntent(EnhanceViewModel.Intent.SetFaceStrength(it.toInt())) },
                    valueRange = 0f..100f,
                )
            }
        }

        // Accelerator — chips in a FlowRow: 4 labels don't fit one segmented row on phones
        FeatureRow(
            icon = Icons.Filled.Memory,
            title = stringResource(R.string.config_accelerator),
            description = stringResource(R.string.config_accel_desc),
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Accelerator.entries.forEach { acc ->
                    FilterChip(
                        selected = state.accelerator == acc,
                        onClick = { onIntent(EnhanceViewModel.Intent.SetAccelerator(acc)) },
                        label = { Text(stringResource(accelLabel(acc))) },
                    )
                }
            }
        }

        val estW = (state.pickedWidth.takeIf { it > 0 } ?: 1920) * state.scale.multiplier
        val estH = (state.pickedHeight.takeIf { it > 0 } ?: 1080) * state.scale.multiplier
        Text(
            stringResource(R.string.config_output_size, estW, estH),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Button(
            onClick = { onIntent(EnhanceViewModel.Intent.StartEnhance) },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Icon(Icons.Filled.AutoAwesome, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.config_enhance), style = MaterialTheme.typography.labelLarge)
        }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/**
 * One feature block: leading icon in a tinted circle, title, one-line description,
 * then the control below. Flat layout — no card wrapper.
 */
@Composable
private fun FeatureRow(
    icon: ImageVector,
    title: String,
    description: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(21.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        content()
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProcessingScreen(
    state: EnhanceViewModel.UiState,
    onIntent: (EnhanceViewModel.Intent) -> Unit,
) {
    val progress = state.progress ?: return
    var confirmCancel by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.proc_title)) },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (progress.batchTotal > 1) {
                Text(
                    stringResource(R.string.proc_batch_counter, progress.batchIndex + 1, progress.batchTotal),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(8.dp))
            }
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
    val resultUri = state.outputUri
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.compare_title)) },
                navigationIcon = {
                    IconButton(onClick = { onIntent(EnhanceViewModel.Intent.ClearPhoto) }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.navigate_back),
                        )
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
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            state.previewUri?.let { original ->
                ComparisonViewer(
                    originalUri = original,
                    resultUri = resultUri,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(20.dp)),
                )
            }

            Text(
                stringResource(if (resultUri != null) R.string.export_saved else R.string.export_preview_notice),
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
                            putExtra(android.content.Intent.EXTRA_STREAM, resultUri?.let(android.net.Uri::parse))
                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(android.content.Intent.createChooser(share, null))
                    },
                    enabled = resultUri != null,
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                ) { Text(stringResource(R.string.export_share)) }
            }
        }
    }
}

@Composable
fun ComparisonViewer(
    originalUri: String,
    resultUri: String?,
    modifier: Modifier = Modifier,
) {
    var splitFraction by remember { mutableStateOf(0.5f) }
    var zoom by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    androidx.compose.foundation.layout.BoxWithConstraints(modifier.clipToBounds()) {
        val width = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val height = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val density = LocalDensity.current
        val handleHalfPx = with(density) { 24.dp.toPx() }
        val handleX = (width * splitFraction).coerceIn(
            handleHalfPx.coerceAtMost(width / 2f),
            width - handleHalfPx.coerceAtMost(width / 2f),
        )
        val handleDescription = stringResource(R.string.compare_handle)
        val hasResult = resultUri != null
        val resultLabel = if (hasResult) R.string.compare_enhanced else R.string.compare_preview
        val transform = Modifier.graphicsLayer {
            transformOrigin = TransformOrigin(0f, 0f)
            scaleX = zoom
            scaleY = zoom
            translationX = offset.x
            translationY = offset.y
        }

        AsyncImage(
            model = resultUri ?: originalUri,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().then(transform),
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    clipRect(right = size.width * splitFraction) {
                        this@drawWithContent.drawContent()
                    }
                },
        ) {
            AsyncImage(
                model = originalUri,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().then(transform),
            )
        }

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(width, height) {
                    detectTransformGestures { centroid, pan, gestureZoom, _ ->
                        val oldZoom = zoom
                        val newZoom = clampComparisonZoom(oldZoom * gestureZoom)
                        if (oldZoom == 1f && newZoom == 1f && abs(pan.x) > abs(pan.y)) {
                            splitFraction = (splitFraction + pan.x / width).coerceIn(0f, 1f)
                        } else {
                            var nextX = offset.x
                            var nextY = offset.y
                            if (newZoom != oldZoom) {
                                val ratio = newZoom / oldZoom
                                nextX = centroid.x - (centroid.x - nextX) * ratio
                                nextY = centroid.y - (centroid.y - nextY) * ratio
                            }
                            nextX += pan.x
                            nextY += pan.y
                            offset = Offset(
                                clampComparisonPanAxis(nextX, newZoom, width),
                                clampComparisonPanAxis(nextY, newZoom, height),
                            )
                        }
                        zoom = newZoom
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = {
                        zoom = 1f
                        offset = Offset.Zero
                    })
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

        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset { IntOffset(handleX.toInt() - handleHalfPx.toInt(), 0) }
                .size(48.dp)
                .semantics {
                    contentDescription = handleDescription
                    stateDescription = "${(splitFraction * 100).toInt()}%"
                }
                .pointerInput(width) {
                    detectHorizontalDragGestures { change, dragAmount ->
                        change.consume()
                        splitFraction = (splitFraction + dragAmount / width).coerceIn(0f, 1f)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.9f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.SwapHoriz,
                    contentDescription = null,
                    tint = Color.Black.copy(alpha = 0.7f),
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        ComparisonBadge(
            label = stringResource(R.string.compare_original),
            modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
        )
        ComparisonBadge(
            label = stringResource(resultLabel),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
        )
        TextButton(
            onClick = {
                splitFraction = 0.5f
                zoom = 1f
                offset = Offset.Zero
            },
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            colors = ButtonDefaults.textButtonColors(
                containerColor = Color.Black.copy(alpha = 0.5f),
                contentColor = Color.White,
            ),
        ) {
            Text(stringResource(R.string.compare_reset))
        }
        Text(
            stringResource(R.string.compare_zoom_hint),
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

@Composable
private fun ComparisonBadge(label: String, modifier: Modifier = Modifier) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = Color.White,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}
