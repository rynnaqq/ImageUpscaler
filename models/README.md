# Rimuru 2x — Model Assets & Quantization Guide (PRD Deliverable D2)

The app ships neural models as **versioned assets** in `app/src/main/assets/models/`,
verified by sha256 at load time (PRD §5.5). This guide covers converting, verifying,
and registering models. **The current repo ships no real models** — the engine falls
back to bilinear upscaling until you place real `.onnx` files here (FR-1.3 graceful
degradation, shown to the user as "fast mode used").

## Required files

| File | Purpose | Source model | License check |
|---|---|---|---|
| `realesrgan_compact_x2.onnx` | Creative 2× | Real-ESRGAN Compact (x2 variant) | BSD-3 ✔ |
| `realesrgan_compact_x4.onnx` | Creative 4× | Real-ESRGAN Compact (x4) | BSD-3 ✔ |
| `swin2sr_light_x2.onnx` | Precision 2× | Swin2SR-class lightweight / line-art-tuned | check — some Swin2SR weights are non-commercial |
| `swin2sr_light_x4.onnx` | Precision 4× | same | same |
| `blazeface_short_range.onnx` | Face detection (M3) | BlazeFace short-range | Apache-2.0 ✔ |
| `gfpgan_mobile.onnx` | Face restoration (M3) | GFPGAN **or** CodeFormer (PRD Open Question #2) | GFPGAN Apache-2.0 ✔ / CodeFormer S-Lab **non-commercial** ✖ |

**License verdict for PRD Open Question #2:** prefer **GFPGAN** (permissive) unless
the bake-off shows CodeFormer is decisively better *and* you obtain commercial rights.

## Conversion: PyTorch → ONNX

```bash
pip install torch onnx onnxruntime
```

```python
# convert_realesrgan.py — Real-ESRGAN Compact → dynamic-HW ONNX, FP16
import torch
from basicsr.archs.rrdbnet_arch import RRDBNet

model = RRDBNet(num_in_ch=3, num_out_ch=3, num_feat=32, num_block=23, num_grow_ch=32, scale=2)
model.load_state_dict(torch.load("RealESRGAN_x2plus.pth", map_location="cpu")["params_ema"])
model.eval().half()

dummy = torch.randn(1, 3, 256, 256).half()
torch.onnx.export(
    model, dummy, "realesrgan_compact_x2.onnx",
    input_names=["input"], output_names=["output"],
    opset_version=17,
    dynamic_axes={"input": {0: "batch", 2: "h", 3: "w"}, "output": {0: "batch", 2: "h", 3: "w"}},
)
```

For 4×: use `scale=4` with the x4 weights (`RealESRGAN_x4plus.pth`), or ship the x2
model and let the app chain two passes (FR-1.4 — `EnhanceImage` already supports this).
Swin2SR: use the official `Swin2SR` HF checkpoint with `torch.onnx.export` the same way.
BlazeFace/GFPGAN: convert from MediaPipe/GFPGAN repos with fixed input shapes.

## Optional INT8 quantization (A/B gate required — PRD Risk #3)

```python
from onnxruntime.quantization import quantize_dynamic, QuantType
quantize_dynamic("realesrgan_compact_x2.onnx", "realesrgan_compact_x2_int8.onnx",
                 weight_type=QuantType.QInt8)
```

Run the quality A/B (PSNR/SSIM + blind test) before swapping the shipped file. Keep
FP16 as the default on capable devices.

## Verify & register

```bash
# 1. Sanity: run the model once on CPU
python -c "
import onnxruntime as ort, numpy as np
s = ort.InferenceSession('realesrgan_compact_x2.onnx', providers=['CPUExecutionProvider'])
x = np.random.rand(1,3,64,64).astype(np.float16)
outs = s.run(None, {s.get_inputs()[0].name: x})
print('out shape:', outs[0].shape)  # expect (1,3,128,128)
"

# 2. Checksum
shasum -a 256 realesrgan_compact_x2.onnx   # macOS/Linux
certutil -hashfile realesrgan_compact_x2.onnx SHA256   # Windows
```

Then edit `app/src/main/java/com/rimuru/twobytwo/data/engine/ModelManifest.kt`:
replace the placeholder entries with real `version` + `sha256` values. Blank
checksums are rejected by `ModelRegistry`; they do not accept any file and
cause the classical fallback until real SHA-256 values are supplied.

## Packaging decision (PRD Open Question #1)

Six FP16 compact models ≈ 25–60 MB total. v1.0: ship **in-base** (assets/) — under
the Play 200 MB AAB base limit, zero complexity. Move to **Play Asset Delivery**
only if the model set grows past ~150 MB or you add video models later.

## Provenance log (required per PRD Risk #1)

| File | Upstream source | Commit/weights hash | License | Reviewed by |
|---|---|---|---|---|
| _(fill in at conversion time)_ | | | | |
