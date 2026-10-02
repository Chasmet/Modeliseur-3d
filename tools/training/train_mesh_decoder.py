"""Actual CPU decoder adaptation from private static meshes and synthetic replay.

Object-level train/validation/test split; never replace Android assets. This is
a research benchmark with fixed reference bounds, not Android reconstruction.
"""
import os
os.environ.setdefault("OPENBLAS_NUM_THREADS", "2")
os.environ.setdefault("OMP_NUM_THREADS", "2")
import argparse
import gc
import json
from pathlib import Path
import re
import sys
import time

import numpy as np
from PIL import Image

from decoder import Decoder, ISO, sample_planes
from mesh_references import prepare_reference, VIEW_NAMES, VERSION as REFERENCE_VERSION
from references import encoder_input, to_local
from train_decoder import (ASSETS, ROOT, FAMILIES, SPLITS, digest, session,
                           prepare_scenes, build_samples, evaluate)

VERSION = "private-mesh-decoder-v2"


def reference_specs(args):
    records, names, hashes = [], set(), set()
    for split in ("train", "validation", "test"):
        for identifier, source in getattr(args, split+"_reference"):
            if not re.fullmatch(r"[a-z0-9][a-z0-9_-]{0,63}", identifier) or identifier in names:
                raise ValueError("Use unique safe reference identifiers")
            path = Path(source).resolve(strict=True)
            sha = digest(path)
            if sha in hashes:
                raise ValueError("An identical reference cannot appear twice or leak across splits")
            names.add(identifier)
            hashes.add(sha)
            records.append({"id": identifier, "split": split, "source_path": str(path), "sha256": sha})
    return records


def encode_references(records, folder, source_assets, threads):
    encoder = None
    for record in records:
        cache = folder/"scenes"/(record["id"]+".npz")
        marker = cache.with_suffix(".key")
        cache.parent.mkdir(parents=True, exist_ok=True)
        key = json.dumps({"source": record["sha256"], "assets": source_assets,
                          "reference_version": REFERENCE_VERSION,
                          "preprocessing": "alpha bounding box, 90 percent, gray 128, 512 square"}, sort_keys=True)
        if not (cache.is_file() and marker.is_file() and marker.read_text() == key):
            if encoder is None:
                print("Loading pinned INT4 encoder on CPU; no gradients in the encoder", flush=True)
                encoder = session(ASSETS/"triposr_encoder_int4.onnx", threads)
            scenes = []
            for view in VIEW_NAMES:
                image = Image.open(folder/"references"/record["id"]/(view+".png")).convert("RGBA")
                image = image.crop(image.getbbox())
                value = encoder.run(None, {"input_image": encoder_input(image)})[0].reshape(3, 40, 64, 64)
                if not np.isfinite(value).all():
                    raise ValueError("Non-finite encoder output")
                scenes.append(value)
                print("ENCODE", record["split"], record["id"], view, flush=True)
            np.savez_compressed(cache, scenes=np.array(scenes, np.float32))
            marker.write_text(key)
        record["scene_path"] = str(cache)
    del encoder
    gc.collect()


def mesh_samples(records, base, folder):
    sets = {k: [] for k in SPLITS}
    # Field alignment is always from the released decoder, never fitted again
    # after optimisation. Query grid matches the synthetic reference protocol.
    axis = np.linspace(-1, 1, 28, dtype=np.float32)
    probe_grid = np.stack(np.meshgrid(axis, axis, axis, indexing="ij"), -1).reshape(-1, 3)
    for record in records:
        with np.load(folder/"references"/record["id"]/"targets.npz") as archive:
            mask = archive["reliable"]
            normalized = archive["grid"][mask]*1.15
            labels = archive["labels"][mask]
        with np.load(record["scene_path"]) as archive:
            scenes = archive["scenes"]
        features, alignments = [], []
        for view in range(4):
            probe = base.forward(sample_planes(scenes[view], probe_grid))[:, 0]
            occupied = probe_grid[probe >= ISO]
            if len(occupied) < 16:
                raise ValueError(f"Reference {record['id']} has no usable base field in view {view}")
            low = np.maximum(-1, occupied.min(0)-2/27)
            high = np.minimum(1, occupied.max(0)+2/27)
            if ((high-low) < .1).any():
                raise ValueError(f"Reference {record['id']} has a thin base field in view {view}")
            raw = low+(to_local(normalized, view)+1)*.5*(high-low)
            features.append(sample_planes(scenes[view], raw))
            alignments.append({"view": VIEW_NAMES[view], "low": low.tolist(), "high": high.tolist()})
        sets[record["split"]].append({**record, "family": "private-mesh", "features": features,
                                     "labels": labels, "alignment": alignments})
        print("SAMPLES", record["split"], record["id"], len(labels), "reliable points x four views", flush=True)
    return sets


def validation_score(uploaded, synthetic):
    return .5*uploaded["mean_iou"] + .5*synthetic["mean_iou"]


def precision_regressions(before, after, tolerance=.02):
    return [a["id"] for a, b in zip(before["objects"], after["objects"])
            if b["precision"] < a["precision"]-tolerance]


def geometry_regressions(before, after):
    """A mean score cannot compensate for missing parts on another object."""
    if [a["id"] for a in before["objects"]] != [a["id"] for a in after["objects"]]:
        raise ValueError("Geometry comparison requires exactly the same object ordering")
    regressions = []
    for a, b in zip(before["objects"], after["objects"]):
        for metric, tolerance in (("iou", .01), ("precision", .02), ("recall", .02)):
            if b[metric] < a[metric]-tolerance:
                regressions.append({"id": a["id"], "metric": metric, "before": a[metric], "after": b[metric]})
    return regressions


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for split in ("train", "validation", "test"):
        parser.add_argument("--"+split+"-reference", nargs=2, action="append", required=True,
                            metavar=("IDENTIFIER", "LOCAL_GLB"))
    parser.add_argument("--steps", type=int, default=800)
    parser.add_argument("--objective", choices=("per-view", "four-view"), default="per-view")
    parser.add_argument("--grid", type=int, choices=(28, 40, 48), default=40)
    parser.add_argument("--encoder-threads", type=int, choices=range(1, 9), default=6)
    parser.add_argument("--initial", type=Path, default=ASSETS/"triposr_decoder.onnx")
    parser.add_argument("--synthetic-cache", type=Path, default=ROOT/"build/training/cpu-001")
    parser.add_argument("--output", type=Path, default=ROOT/"build/training/uploads-002")
    args = parser.parse_args()
    if not 1 <= args.steps <= 5000:
        raise ValueError("Use 1 to 5000 bounded steps")
    folder = args.output.resolve()
    # All private training outputs stay in ignored build/, never in Git assets
    # or public experiment folders. Publishing derivatives is a separate action.
    if ROOT/"build" not in folder.parents:
        raise ValueError("Private mesh outputs must be under the repository's ignored build/ directory")
    folder.mkdir(parents=True, exist_ok=True)
    started = time.monotonic()
    sys.path.insert(0, str(ROOT/"tools/offline-models"))
    from fetch_triposr import FINAL, valid
    for name, expected in FINAL.items():
        if not valid(ASSETS/name, expected):
            raise ValueError("Prepare pinned model assets before training: "+name)
    source_assets = {name: sha for name, (sha, _) in FINAL.items()}
    records = reference_specs(args)
    audits = []
    for record in records:
        audits.append(prepare_reference(record["source_path"], folder/"references"/record["id"],
                                        record["id"], resolution=args.grid))
        gc.collect()
    manifest = {"version": VERSION, "seed": 84219, "references": records,
                "audits": audits, "source_assets": source_assets, "initial_sha256": digest(args.initial),
                "views_per_object": 4, "grid_resolution": args.grid,
                "synthetic_replay_probability": .5, "objective": args.objective, "production_approved": False,
                "redistribution": "Private data and derived checkpoints; rights not established for public distribution"}
    (folder/"dataset.json").write_text(json.dumps(manifest, indent=2))
    encode_references(records, folder, source_assets, args.encoder_threads)
    base = Decoder(ASSETS/"triposr_decoder.onnx")
    model = Decoder(args.initial)
    uploaded = mesh_samples(records, base, folder)
    synthetic_manifest = {"version": "synthetic-multiview-decoder-v1", "seed": 74123,
                          "families": FAMILIES, "splits": SPLITS, "source_assets": source_assets}
    synthetic_records = prepare_scenes(args.synthetic_cache, synthetic_manifest, args.encoder_threads)
    synthetic, skipped = build_samples(synthetic_records, base, folder)
    base_metrics = {"mesh_validation": evaluate(base, uploaded["validation"]),
                    "mesh_test": evaluate(base, uploaded["test"]),
                    "synthetic_validation": evaluate(base, synthetic["validation"]),
                    "synthetic_test": evaluate(base, synthetic["test"])}
    initial_metrics = {"mesh_validation": evaluate(model, uploaded["validation"]),
                       "mesh_test": evaluate(model, uploaded["test"]),
                       "synthetic_validation": evaluate(model, synthetic["validation"]),
                       "synthetic_test": evaluate(model, synthetic["test"])}
    best_score = validation_score(initial_metrics["mesh_validation"], initial_metrics["synthetic_validation"])
    best_weights = [v.copy() for v in model.parameters]
    best_step = 0
    history = []
    rng = np.random.default_rng(84219)
    print("BASELINE", json.dumps({k: v["mean_iou"] for k, v in base_metrics.items()}), flush=True)
    print("INITIAL", json.dumps({k: v["mean_iou"] for k, v in initial_metrics.items()}), flush=True)
    for step in range(1, args.steps+1):
        examples = uploaded["train"] if rng.random() < .5 else synthetic["train"]
        example = examples[int(rng.integers(len(examples)))]
        if args.objective == "four-view":
            indices = rng.integers(len(example["labels"]), size=256)
            loss, gradients = model.fused_loss_and_gradient(
                [x[indices] for x in example["features"]], example["labels"][indices])
        else:
            view = int(rng.integers(4))
            indices = rng.integers(len(example["labels"]), size=512)
            loss, gradients = model.loss_and_gradient(example["features"][view][indices], example["labels"][indices])
        model.update(gradients, 2e-5)
        if step % 100 == 0 or step == args.steps:
            mesh_val = evaluate(model, uploaded["validation"])
            synth_val = evaluate(model, synthetic["validation"])
            score = validation_score(mesh_val, synth_val)
            regressions = (geometry_regressions(initial_metrics["mesh_validation"], mesh_val)
                           + geometry_regressions(initial_metrics["synthetic_validation"], synth_val))
            history.append({"step": step, "loss": loss, "validation_score": score,
                            "mesh_validation": mesh_val, "synthetic_validation": synth_val,
                            "validation_geometry_regressions": regressions})
            print(f"TRAIN {step}/{args.steps} loss={loss:.5f} score={score:.5f} geometry regressions={regressions}", flush=True)
            if not regressions and score > best_score:
                best_score, best_step = score, step
                best_weights = [v.copy() for v in model.parameters]
    last = folder/"triposr_decoder_last.onnx"
    model.save(last)
    last_changed = sum(int(np.count_nonzero(a != b)) for a, b in zip(model.parameters, model.initial))
    # Test set is evaluated only after checkpoint selection is finished.
    model.parameters = best_weights
    candidate = folder/"triposr_decoder_candidate.onnx"
    model.save(candidate)
    final_metrics = {"mesh_validation": evaluate(model, uploaded["validation"]),
                     "mesh_test": evaluate(model, uploaded["test"]),
                     "synthetic_validation": evaluate(model, synthetic["validation"]),
                     "synthetic_test": evaluate(model, synthetic["test"])}
    x = uploaded["test"][0]["features"][0][:128]
    parity = float(np.max(np.abs(session(candidate).run(None, {"triplane_features": x[None]})[0][0]-model.forward(x))))
    if parity > 2e-4:
        raise ValueError("ONNX export differs from trained NumPy decoder")
    regressions = (geometry_regressions(initial_metrics["mesh_test"], final_metrics["mesh_test"])
                   + geometry_regressions(initial_metrics["synthetic_test"], final_metrics["synthetic_test"]))
    changed = sum(int(np.count_nonzero(a != b)) for a, b in zip(model.parameters, model.initial))
    report = {"version": VERSION, "scope": "actual decoder fine-tuning; frozen INT4 encoder; synthetic replay",
              "training_steps": args.steps, "objective": args.objective, "selected_step": best_step,
              "changed_selected_parameters": changed, "changed_last_parameters": last_changed,
              "trainable_parameters": 41089, "frozen_rgb_final_parameters": 195,
              "object_splits": {k: [r["id"] for r in records if r["split"] == k] for k in SPLITS},
              "synthetic_objects": {k: len(v) for k, v in synthetic.items()},
              "released_decoder": base_metrics, "initial_candidate": initial_metrics, "selected_candidate": final_metrics,
              "heldout_geometry_regressions": regressions, "production_approved": False,
              "candidate_sha256": digest(candidate), "candidate_bytes": candidate.stat().st_size,
              "last_sha256": digest(last), "initial_sha256": digest(args.initial),
              "rgb_final_rows_unchanged": bool(np.array_equal(model.parameters[-2][1:], model.initial[-2][1:])
                                               and np.array_equal(model.parameters[-1][1:], model.initial[-1][1:])),
              "source_assets": source_assets, "views_per_object": 4, "grid_resolution": args.grid,
              "onnx_numpy_max_difference": parity, "duration_seconds": time.monotonic()-started,
              "history": history, "reference_audits": audits,
              "limitations": [f"{len(records)} user-supplied meshes, no verified scans or corresponding real photographs.",
                              "Object-level private train/validation/test split; validation selects weights, test does not.",
                              "Open-component bounds masked; partial supervision and evaluation for affected meshes.",
                              "Coarse sampled occupancy may miss thin parts. Reference bounds used for fixed alignment.",
                              "Base-color diffuse CPU renders omit normal maps, specular and full PBR lighting.",
                              "No full Android pipeline, silhouette clipping, surface or texture training, or phone timing.",
                              "Final RGB rows frozen; hidden-layer updates can still change neural color outputs.",
                              "Supplied content and derived weights are private; public redistribution rights not established.",
                              "APK, released model assets and main branch unchanged."]}
    (folder/"report.json").write_text(json.dumps(report, indent=2))
    print("RESULT", json.dumps({k: report[k] for k in ("selected_step", "changed_selected_parameters", "changed_last_parameters", "production_approved", "candidate_sha256", "duration_seconds")}), flush=True)


if __name__ == "__main__":
    main()
