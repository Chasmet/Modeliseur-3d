#!/usr/bin/env bash
set -euo pipefail
MODEL=app/src/main/assets/models/da3_small_four_view_224.onnx
SHA=b83acb5bcfa05e1393396fab77e31605b6f2ba53c8f04c2a0644c46557acefb6
mkdir -p "$(dirname "$MODEL")"
if [ -f "$MODEL" ] && echo "$SHA  $MODEL" | sha256sum -c - >/dev/null 2>&1; then exit 0; fi
rm -f "$MODEL"
python -m pip install --disable-pip-version-check --index-url https://download.pytorch.org/whl/cpu torch==2.7.1 torchvision==0.22.1
python -m pip install --disable-pip-version-check onnx==1.18.0 onnxruntime==1.22.1 huggingface_hub==0.33.4 safetensors==0.5.3 protobuf==7.35.1 einops==0.8.1 omegaconf==2.3.0 addict==2.4.0 numpy==1.26.4 opencv-python-headless==4.11.0.86 imageio==2.37.0
SRC="${RUNNER_TEMP}/da3-onnx-source"; CKPT="${RUNNER_TEMP}/da3-small-checkpoint"
git clone --no-checkout https://github.com/devin-lai/Depth-Anything-3-Onnx.git "$SRC"
git -C "$SRC" checkout 9d75a0527225a0cad939af3e72dd5c24978d2723
python -c "from huggingface_hub import snapshot_download; snapshot_download('depth-anything/DA3-SMALL',revision='e08cab65ca0ec38e7826075418411ab90cab4da3',local_dir='$CKPT')"
PYTHONPATH="$SRC/src" python tools/export_da3_multiview.py --model "$CKPT" --output "$MODEL" --size 224
echo "$SHA  $MODEL" | sha256sum -c -
