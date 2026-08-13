#!/usr/bin/env python3
"""Export a fixed four-view Depth Anything 3 graph for Android.

The Android application does not ship Python or PyTorch.  This helper only
runs in GitHub Actions to convert the Apache-2.0 DA3-SMALL checkpoint into one
portable ONNX file consumed by ONNX Runtime Android.
"""

from __future__ import annotations

import argparse
from pathlib import Path

import torch

from depth_anything_3.api import DepthAnything3


class FourViewDepthWrapper(torch.nn.Module):
    """Keep DA3's cross-view attention and expose only mobile-useful tensors."""

    def __init__(self, model: DepthAnything3) -> None:
        super().__init__()
        self.model = model.model
        # The public DA3 API applies ImageNet normalization before invoking
        # the underlying network.  Keep it in the graph so Android can send
        # ordinary RGB values in [0, 1] without duplicating hidden constants.
        self.register_buffer(
            "image_mean",
            torch.tensor([0.485, 0.456, 0.406]).view(1, 1, 3, 1, 1),
        )
        self.register_buffer(
            "image_std",
            torch.tensor([0.229, 0.224, 0.225]).view(1, 1, 3, 1, 1),
        )

    def forward(self, images: torch.Tensor) -> tuple[torch.Tensor, torch.Tensor]:
        # images: (1, 4, 3, H, W), values in [0, 1]
        normalized = (images - self.image_mean) / self.image_std
        output = self.model(
            normalized,
            None,
            None,
            export_feat_layers=[],
            infer_gs=False,
        )
        return output["depth"], output["depth_conf"]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", required=True, help="Pinned local DA3-SMALL directory")
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--size", type=int, default=224)
    parser.add_argument("--opset", type=int, default=17)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    if args.size % 14 != 0:
        raise SystemExit("DA3 input size must be divisible by patch size 14")

    model = DepthAnything3.from_pretrained(args.model).to("cpu").eval()
    wrapper = FourViewDepthWrapper(model).eval()
    sample = torch.zeros(1, 4, 3, args.size, args.size, dtype=torch.float32)
    args.output.parent.mkdir(parents=True, exist_ok=True)

    with torch.inference_mode():
        expected_depth, expected_confidence = wrapper(sample)
        torch.onnx.export(
            wrapper,
            sample,
            str(args.output),
            input_names=["images"],
            output_names=["depth", "depth_confidence"],
            opset_version=args.opset,
            do_constant_folding=True,
            dynamo=False,
        )

    import onnx
    import onnxruntime as ort
    import numpy as np

    graph = onnx.load(str(args.output))
    onnx.checker.check_model(graph)
    session_options = ort.SessionOptions()
    session_options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_BASIC
    session = ort.InferenceSession(
        str(args.output),
        session_options,
        providers=["CPUExecutionProvider"],
    )
    actual_depth, actual_confidence = session.run(
        None,
        {"images": sample.numpy()},
    )
    np.testing.assert_allclose(
        actual_depth,
        expected_depth.detach().numpy(),
        rtol=3e-2,
        atol=3e-2,
    )
    expected_confidence_np = expected_confidence.detach().numpy()
    confidence_scale = max(float(np.mean(np.abs(expected_confidence_np))), 1e-6)
    confidence_relative_mae = float(
        np.mean(np.abs(actual_confidence - expected_confidence_np))
        / confidence_scale
    )
    confidence_correlation = float(
        np.corrcoef(actual_confidence.ravel(), expected_confidence_np.ravel())[0, 1]
    )
    if confidence_relative_mae >= 0.03 or confidence_correlation <= 0.98:
        raise AssertionError(
            "ONNX confidence drift: relative MAE={} correlation={}".format(
                confidence_relative_mae,
                confidence_correlation,
            )
        )
    print(
        "DA3 four-view ONNX verified:",
        args.output,
        actual_depth.shape,
        actual_confidence.shape,
        "confidence_relative_mae={:.6f}".format(confidence_relative_mae),
        "confidence_correlation={:.6f}".format(confidence_correlation),
        args.output.stat().st_size,
        "bytes",
    )


if __name__ == "__main__":
    main()
