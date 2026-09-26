# Rimuru 2x — Model Assets & Quantization Guide (PRD Deliverable D2)

The app ships neural models as **versioned assets** in `app/src/main/assets/models/`,
verified by sha256 at load time (PRD §5.5). This guide covers converting, verifying,
and registering models. The creative 2× and 4× Real-ESRGAN exports are now bundled;
unsupported model families still use the classical fallback until verified assets
are added (FR-1.3 graceful degradation, shown to the user as "fast mode used").

## Bundled assets in this workspace

The two creative upscaler exports are bundled in `app/src/main/assets/models/`:

| File | Version | Bytes | SHA-256 |
|---|---|---|---|
| `realesrgan_compact_x2.onnx` | `2plus-fp32-op20` | 67,191,396 | `fb3ce45a465b7b5a30a6c6ae8aa09cd0df192824fefdb07e16bae0af18ef60e9` |
| `realesrgan_compact_x4.onnx` | `4plus-fp32-op20` | 67,167,193 | `01be1ebcddc7a08663818ac96e7ab95e4f2c812aa4423c9b7e2e59a716a6626c` |

Measured raw asset footprint: **134,358,589 bytes ≈ 128.1 MiB** for the two files
(FP32, not FP16 — see the conversion section). The remaining model keys
intentionally retain blank checksums and use the classical fallback.

Both graphs were inspected directly (ONNX protobuf) and are float32, `opset 20`,
with a fully dynamic NCHW input `batch_size x 3 x width x height`. Their upsampling
tails differ, and the difference matters to the engine:

| File | Tail ops | Odd tile H/W |
|---|---|---|
| `realesrgan_compact_x2.onnx` | 3x Reshape + 3x Unsqueeze + 1x Transpose (reshape-based pixel shuffle) | **not supported** — the engine pads to even, runs, and crops back |
| `realesrgan_compact_x4.onnx` | 2x Resize, no Reshape/Unsqueeze/Transpose | supported, no padding applied |

`evenTileRequired` (in `app/src/main/java/com/rimuru/twobytwo/data/engine/OnnxInferenceEngine.kt`)
encodes exactly that split, so x4 keeps its existing behaviour. Odd tiles are real,
not hypothetical: `TilingManager` clamps the last tile in a row/column to the image
edge, so any odd image width or height produces odd edge tiles.

### Repository rules for these files

- `.gitattributes` marks `*.onnx binary` (`-text -diff -merge`). A Windows
  checkout with `core.autocrlf=true` would otherwise rewrite the bytes, the
  SHA-256 check in `ModelRegistry` would fail, and **every** model would silently
  demote to the classical fallback. After changing the attribute, renormalize with
  `git add --renormalize .` and confirm the blob still hashes to the manifest value.
- `ModelAssetHashTest` hashes the real files on disk and compares them to
  `ModelManifest.BUNDLED`, so a rewritten asset fails CI instead of shipping.

## Required files

| File | Purpose | Source model | License check |
|---|---|---|---|
| `realesrgan_compact_x2.onnx` | Creative 2× | Real-ESRGAN Compact (x2 variant) | **unverified** — see provenance log |
| `realesrgan_compact_x4.onnx` | Creative 4× | Real-ESRGAN Compact (x4) | **unverified** — see provenance log |
| `swin2sr_light_x2.onnx` | Precision 2× | Swin2SR-class lightweight / line-art-tuned | **unverified** — not bundled; check before use |
| `swin2sr_light_x4.onnx` | Precision 4× | same | **unverified** — not bundled; check before use |
| `blazeface_short_range.onnx` | Face detection (M3) | BlazeFace short-range | **unverified** — not bundled; check before use |
| `gfpgan_mobile.onnx` | Face restoration (M3) | GFPGAN **or** CodeFormer (PRD Open Question #2) | **unverified** — not bundled; check before use |

**No license review has been recorded for any row above.** The upstream Real-ESRGAN
project ships BSD-3-Clause and GFPGAN ships Apache-2.0, but that is the projects'
own claim, not a review of the weights actually vendored here, and the CodeFormer
option carries a non-commercial restriction. Complete the provenance log and get a
recorded sign-off before distributing any binary.

**License verdict for PRD Open Question #2:** prefer **GFPGAN** (permissive) unless
the bake-off shows CodeFormer is decisively better *and* you obtain commercial rights.
This stays a recommendation, not an approval.

## Conversion: PyTorch → ONNX

The bundled exports are **FP32 at opset 20**. Do not switch them to FP16 without
regenerating the manifest checksums and re-running the A/B gate below.

```bash
pip install torch onnx onnxruntime
```

```python
# convert_realesrgan.py — Real-ESRGAN Compact → dynamic-HW ONNX, FP32, opset 20
import torch
from basicsr.archs.rrdbnet_arch import RRDBNet

model = RRDBNet(num_in_ch=3, num_out_ch=3, num_feat=32, num_block=23, num_grow_ch=32, scale=2)
model.load_state_dict(torch.load("RealESRGAN_x2plus.pth", map_location="cpu")["params_ema"])
model.eval()  # FP32 — the shipped artifact is not half precision

dummy = torch.randn(1, 3, 256, 256)
torch.onnx.export(
    model, dummy, "realesrgan_compact_x2.onnx",
    input_names=["input"], output_names=["output"],
    opset_version=20,
    dynamic_axes={"input": {0: "batch", 2: "h", 3: "w"}, "output": {0: "batch", 2: "h", 3: "w"}},
)
```

For 4×: use `scale=4` with the x4 weights (`RealESRGAN_x4plus.pth`), or ship the x2
model and let the app chain two passes (FR-1.4 — `EnhanceImage` already supports this).
Swin2SR: use the official `Swin2SR` HF checkpoint with `torch.onnx.export` the same way.
BlazeFace/GFPGAN: convert from MediaPipe/GFPGAN repos with fixed input shapes.

**If you re-export the x2 model, check the tail ops.** A reshape-based pixel shuffle
reintroduces the even-H/W requirement; a `Resize`-only tail does not. Re-verify with
the inspection snippet below and update `evenTileRequired` only if the new graph
really needs it.

## Optional INT8 quantization (A/B gate required — PRD Risk #3)

```python
from onnxruntime.quantization import quantize_dynamic, QuantType
quantize_dynamic("realesrgan_compact_x2.onnx", "realesrgan_compact_x2_int8.onnx",
                 weight_type=QuantType.QInt8)
```

Run the quality A/B (PSNR/SSIM + blind test) before swapping the shipped file. The
shipped default is FP32, which costs roughly twice the FP16 size — the INT8 path is
the only cheap way back down.

## Verify & register

```bash
# 1. Sanity: run the model once on CPU
python -c "
import onnxruntime as ort, numpy as np
s = ort.InferenceSession('realesrgan_compact_x2.onnx', providers=['CPUExecutionProvider'])
x = np.random.rand(1,3,64,64).astype(np.float32)
outs = s.run(None, {s.get_inputs()[0].name: x})
print('out shape:', outs[0].shape)  # expect (1,3,128,128)
"

# 1b. Confirm dtype, opset and tail ops before registering the file
python -c "
import onnx
m = onnx.load('realesrgan_compact_x2.onnx')
print('opset:', m.opset_import[0].version)
print('input:', m.graph.input[0].type.tensor_type.elem_type)  # 1 = FLOAT
print('tail:', sorted({n.op_type for n in m.graph.node} & {'Reshape','Unsqueeze','Transpose','Resize'}))
"

# 2. Checksum
shasum -a 256 realesrgan_compact_x2.onnx   # macOS/Linux
certutil -hashfile realesrgan_compact_x2.onnx SHA256   # Windows
```

An odd input size only succeeds on a `Resize`-only graph. To reproduce the
CREATIVE_X2 failure the engine guards against:

```bash
python -c "
import onnxruntime as ort, numpy as np
s = ort.InferenceSession('realesrgan_compact_x2.onnx', providers=['CPUExecutionProvider'])
s.run(None, {s.get_inputs()[0].name: np.random.rand(1,3,65,65).astype(np.float32)})
"  # the padded path runs this as 66x66 and crops back to 130x130
```

Then edit `app/src/main/java/com/rimuru/twobytwo/data/engine/ModelManifest.kt`:
replace the placeholder entries with real `version` + `sha256` values. Blank
checksums are rejected by `ModelRegistry`; they do not accept any file and
cause the classical fallback until real SHA-256 values are supplied.

## Packaging decision (PRD Open Question #1)

The two shipped FP32 models already measure **134,358,589 bytes ≈ 128.1 MiB**
uncompressed — the earlier "25–60 MB total" estimate assumed FP16 and a six-model
set, and it does not hold for what is actually bundled. v1.0 still ships
**in-base** (assets/).

The Play 200 MB AAB limit is measured on the **compressed** artifact, and that
figure has **not been measured** for this bundle. How much the two ONNX files
shrink under AAB compression is unknown, so headroom is unknown. Before adding
another model, measure the compressed base on a real release build and decide
in-base vs **Play Asset Delivery** from that number. The decision threshold is
the measurement, not a third model.

## Provenance log (required per PRD Risk #1)

| File | Upstream source | Commit/weights hash | License | Reviewed by |
|---|---|---|---|---|
| `realesrgan_compact_x2.onnx` | Real-ESRGAN (x2plus) | **unknown** — weights hash not recorded | **unverified** | **unreviewed** |
| `realesrgan_compact_x4.onnx` | Real-ESRGAN (x4plus) | **unknown** — weights hash not recorded | **unverified** | **unreviewed** |

Only the exported artifact hashes above are known — those are the values in
`ModelManifest.BUNDLED`. The upstream repository commit, the `.pth` weights hash
they were produced from, the conversion date, and any license sign-off were **not
recorded when these files were bundled**. Fill those columns in before release;
until then treat the license status of both binaries as unknown.
