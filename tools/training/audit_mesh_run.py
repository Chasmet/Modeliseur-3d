"""Verify a private training run and apply stricter per-object geometry safeguards.

This never edits the experiment report, weights, original meshes or APK assets.
The audit records a refusal even if an older weighted-average selector picked
a checkpoint. Inspecting test metrics here never starts another optimisation.
"""
import argparse
import json
from pathlib import Path

import numpy as np

from decoder import Decoder
from train_decoder import ASSETS, ROOT, digest, session
from train_mesh_decoder import geometry_regressions


def audit(folder):
    folder = Path(folder).resolve()
    report = json.loads((folder/"report.json").read_text())
    dataset = json.loads((folder/"dataset.json").read_text())
    if report["production_approved"] is not False:
        raise ValueError("Research run must not approve Android deployment")
    initial_paths = [ASSETS/"triposr_decoder.onnx",
                     ROOT/"tools/training/experiments/cpu-001/triposr_decoder_candidate.onnx"]
    initial_path = next((p for p in initial_paths if digest(p) == report["initial_sha256"]), None)
    if initial_path is None:
        raise ValueError("Unknown initial checkpoint")
    initial = Decoder(initial_path)
    checks = []
    for name, sha_key, changed_key in (("candidate", "candidate_sha256", "changed_selected_parameters"),
                                       ("last", "last_sha256", "changed_last_parameters")):
        path = folder/("triposr_decoder_"+name+".onnx")
        if digest(path) != report[sha_key]:
            raise ValueError("Checkpoint hash differs from report")
        model = Decoder(path)
        changed = sum(int(np.count_nonzero(a != b)) for a, b in zip(model.parameters, initial.parameters))
        if changed != report[changed_key]:
            raise ValueError("Changed weight count differs from report")
        if not all(np.isfinite(v).all() for v in model.parameters):
            raise ValueError("Non-finite weights")
        for index in (-2, -1):
            np.testing.assert_array_equal(model.parameters[index][1:], initial.parameters[index][1:])
        x = np.random.default_rng(519).normal(size=(128, 120)).astype(np.float32)*.4
        actual = session(path).run(None, {"triplane_features": x[None]})[0][0]
        difference = float(np.abs(actual-model.forward(x)).max())
        if difference > 2e-4:
            raise ValueError("ONNX/NumPy parity failed")
        checks.append({"checkpoint": name, "sha256": digest(path), "changed_parameters": changed,
                       "rgb_final_rows_unchanged": True, "onnx_numpy_max_difference": difference})
    sources = []
    for record in dataset["references"]:
        source = Path(record["source_path"])
        if digest(source) != record["sha256"]:
            raise ValueError("Original source was modified")
        sources.append({"id": record["id"], "sha256": record["sha256"], "original_unchanged": True})
    for name, sha in report["source_assets"].items():
        if digest(ASSETS/name) != sha:
            raise ValueError("Released Android source assets were modified")
    initial_metrics = report["initial_candidate"]
    final_metrics = report["selected_candidate"]
    validation = (geometry_regressions(initial_metrics["mesh_validation"], final_metrics["mesh_validation"])
                  + geometry_regressions(initial_metrics["synthetic_validation"], final_metrics["synthetic_validation"]))
    test = (geometry_regressions(initial_metrics["mesh_test"], final_metrics["mesh_test"])
            + geometry_regressions(initial_metrics["synthetic_test"], final_metrics["synthetic_test"]))
    history = []
    for item in report["history"]:
        regressions = (geometry_regressions(initial_metrics["mesh_validation"], item["mesh_validation"])
                       + geometry_regressions(initial_metrics["synthetic_validation"], item["synthetic_validation"]))
        history.append({"step": item["step"], "stricter_validation_regressions": regressions,
                        "eligible_under_v2_geometry_safeguards": not regressions})
    result = {"experiment_protocol": report["version"], "audit_protocol": "per-object-iou-precision-recall-v2",
              "checkpoints": checks, "source_files": sources,
              "released_assets_unchanged": True, "validation_geometry_regressions": validation,
              "test_geometry_regressions": test, "historical_validation_audit": history,
              "new_weights_pass_stricter_validation": bool(report["selected_step"] > 0 and not validation),
              "production_approved": False,
              "decision": "No APK integration; candidate and last checkpoint retained for private research"}
    (folder/"acceptance-audit.json").write_text(json.dumps(result, indent=2))
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("folder", type=Path)
    result = audit(parser.parse_args().folder)
    print(json.dumps({"production_approved": result["production_approved"],
                      "new_weights_pass_stricter_validation": result["new_weights_pass_stricter_validation"],
                      "validated_checkpoints": len(result["checkpoints"]),
                      "originals_unchanged": len(result["source_files"]),
                      "validation_regressions": result["validation_geometry_regressions"],
                      "test_regressions": result["test_geometry_regressions"]}, indent=2))
