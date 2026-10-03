"""Train and evaluate a real TripoSR decoder candidate, never modify Android assets.

python tools/training/train_decoder.py --steps 600 --output build/training/cpu-001
Twelve synthetic objects, four renders each. Splits are made BY OBJECT, not pixel.
The encoder is frozen. Test objects never drive optimisation or checkpoint choice.
"""
import os
os.environ.setdefault("OPENBLAS_NUM_THREADS", "2")
os.environ.setdefault("OMP_NUM_THREADS", "2")
import argparse
import gc
import hashlib
import json
from pathlib import Path
import time
import numpy as np
import onnxruntime as ort
from decoder import Decoder, ISO, sample_planes, sigmoid
from references import parts, bounds, occupancy, render, encoder_input, to_local, to_world

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "app/src/main/assets/models"
VERSION = "synthetic-multiview-decoder-v1"
FAMILIES = ("bottle", "chair", "figure")
SPLITS = {"train": (101, 102), "validation": (201,), "test": (301,)}


def digest(path):
    h = hashlib.sha256()
    with Path(path).open("rb") as f:
        for chunk in iter(lambda: f.read(1024*1024), b""):
            h.update(chunk)
    return h.hexdigest()


def session(path, threads=2):
    options = ort.SessionOptions()
    options.intra_op_num_threads = threads
    options.inter_op_num_threads = 1
    options.enable_cpu_mem_arena = False
    options.enable_mem_pattern = False
    options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_EXTENDED
    return ort.InferenceSession(str(path), options, providers=["CPUExecutionProvider"])


def prepare_scenes(folder, manifest, encoder_threads):
    records = []
    encoder = None
    cache = folder / "scenes"
    cache.mkdir(parents=True, exist_ok=True)
    key = json.dumps(manifest, sort_keys=True)
    for split, seeds in SPLITS.items():
        for family in FAMILIES:
            for seed in seeds:
                identifier = f"{family}-{seed}"
                record = {"id": identifier, "split": split, "family": family, "seed": seed}
                geometry = parts(family, seed)
                scene_file = cache / (identifier + ".npz")
                marker = cache / (identifier + ".key")
                if scene_file.is_file() and marker.is_file() and marker.read_text() == key:
                    scene = np.load(scene_file)["scenes"]
                else:
                    if encoder is None:
                        print("Loading pinned INT4 TripoSR encoder on CPU", flush=True)
                        encoder = session(ASSETS / "triposr_encoder_int4.onnx", encoder_threads)
                    images = folder / "references" / identifier
                    images.mkdir(parents=True, exist_ok=True)
                    (images / "geometry.json").write_text(json.dumps(geometry, indent=2))
                    scene = []
                    for view in range(4):
                        image = render(geometry, view)
                        image.save(images / ("face", "back", "right", "left")[view], format="PNG")
                        value = encoder.run(None, {"input_image": encoder_input(image)})[0]
                        value = value.reshape(3, 40, 64, 64)
                        if not np.isfinite(value).all():
                            raise ValueError("Non-finite encoder output")
                        scene.append(value)
                        print(f"ENCODE {split} {identifier} view {view+1}/4", flush=True)
                    scene = np.array(scene, np.float32)
                    np.savez_compressed(scene_file, scenes=scene)
                    marker.write_text(key)
                record["scene_path"] = str(scene_file)
                records.append(record)
    del encoder
    gc.collect()
    return records


def build_samples(records, model, folder):
    # Same point cloud for all four views of an object: consistent 3D supervision.
    axis = np.linspace(-1, 1, 28, dtype=np.float32)
    grid = np.stack(np.meshgrid(axis, axis, axis, indexing="ij"), -1).reshape(-1, 3)
    sets = {key: [] for key in SPLITS}
    skipped = []
    for record in records:
        scenes = np.load(record["scene_path"])["scenes"]
        geometry = parts(record["family"], record["seed"])
        low_world, high_world = bounds(geometry)
        center_world = (low_world+high_world)/2
        half_world = (high_world-low_world)/2
        world = center_world+grid*half_world*1.15
        labels = occupancy(world, geometry).astype(np.float32)
        views = []
        try:
            for view in range(4):
                probe = model.forward(sample_planes(scenes[view], grid))[:, 0]
                occupied = grid[probe >= ISO]
                if len(occupied) < 16:
                    raise ValueError(f"no usable base density in view {view}")
                low = np.maximum(-1, occupied.min(0)-2/27)
                high = np.minimum(1, occupied.max(0)+2/27)
                if ((high-low) < .1).any():
                    raise ValueError(f"thin base field in view {view}")
                normalized = to_local((world-center_world)/half_world, view)
                raw = low+(normalized+1)*.5*(high-low)
                features = sample_planes(scenes[view], raw)
                views.append(features)
            sets[record["split"]].append({**record, "features": views, "labels": labels})
            print(f"SAMPLES {record['split']} {record['id']}: {len(labels)} points x 4 views", flush=True)
        except ValueError as error:
            skipped.append({"id": record["id"], "split": record["split"], "reason": str(error)})
    (folder / "skipped.json").write_text(json.dumps(skipped, indent=2))
    for split in SPLITS:
        present = {v["family"] for v in sets[split]}
        if present != set(FAMILIES):
            raise ValueError(f"Missing reference family in {split}: {present}; training aborted")
    return sets, skipped


def evaluate(model, examples):
    result = []
    for example in examples:
        probabilities = np.array([sigmoid(2*(model.forward(x)[:, 0]-ISO)) for x in example["features"]])
        ordered = np.sort(probabilities, axis=0)
        # App-style two strongest supports plus mean, without silhouette clipping.
        fused = .4*ordered[-1]+.4*ordered[-2]+.2*probabilities.mean(0)
        predicted = fused >= .5
        truth = example["labels"] > .5
        intersection = (predicted & truth).sum()
        union = (predicted | truth).sum()
        tp, fp, fn = int(intersection), int((predicted & ~truth).sum()), int((~predicted & truth).sum())
        result.append({"id": example["id"], "family": example["family"], "iou": float(intersection/max(1, union)),
                       "precision": tp/max(1, tp+fp), "recall": tp/max(1, tp+fn)})
    return {"mean_iou": float(np.mean([v["iou"] for v in result])), "objects": result}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--steps", type=int, default=600)
    parser.add_argument("--encoder-threads", type=int, choices=range(1, 9), default=6)
    parser.add_argument("--output", type=Path, default=ROOT / "build/training/cpu-001")
    args = parser.parse_args()
    if args.steps < 1 or args.steps > 5000:
        raise ValueError("Use a bounded training run of 1 to 5000 steps")
    folder = args.output.resolve()
    if folder == ASSETS.resolve() or ASSETS.resolve() in folder.parents:
        raise ValueError("Training may never overwrite Android assets")
    folder.mkdir(parents=True, exist_ok=True)
    started = time.monotonic()
    # Pinned hashes, NOT a download. Training cannot silently swap the encoder.
    import sys
    sys.path.insert(0, str(ROOT / "tools/offline-models"))
    from fetch_triposr import FINAL, valid
    for name, expected in FINAL.items():
        if not valid(ASSETS/name, expected):
            raise ValueError("Prepare pinned model assets before training: " + name)
    manifest = {"version": VERSION, "seed": 74123, "families": FAMILIES, "splits": SPLITS,
                "source_assets": {name: sha for name, (sha, _) in FINAL.items()}}
    (folder / "dataset.json").write_text(json.dumps(manifest, indent=2))
    model = Decoder(ASSETS / "triposr_decoder.onnx")
    records = prepare_scenes(folder, manifest, args.encoder_threads)
    sets, skipped = build_samples(records, model, folder)
    initial_validation = evaluate(model, sets["validation"])
    initial_test = evaluate(model, sets["test"])
    best_validation = initial_validation
    best_weights = [v.copy() for v in model.parameters]
    best_step = 0
    rng = np.random.default_rng(74123)
    history = []
    print("BASELINE validation", initial_validation["mean_iou"], "test", initial_test["mean_iou"], flush=True)
    for step in range(1, args.steps+1):
        example = sets["train"][int(rng.integers(len(sets["train"])))]
        view = int(rng.integers(4))
        indices = rng.integers(len(example["labels"]), size=512)
        loss, gradients = model.loss_and_gradient(example["features"][view][indices], example["labels"][indices])
        model.update(gradients, 2e-5)
        if step % 50 == 0 or step == args.steps:
            score = evaluate(model, sets["validation"])
            history.append({"step": step, "loss": loss, "validation_iou": score["mean_iou"]})
            print(f"TRAIN {step}/{args.steps} loss={loss:.5f} validation IoU={score['mean_iou']:.5f}", flush=True)
            if score["mean_iou"] > best_validation["mean_iou"]:
                best_validation = score
                best_step = step
                best_weights = [v.copy() for v in model.parameters]
    # Selection ONLY from validation, never from held-out test results.
    model.parameters = best_weights
    candidate = folder / "triposr_decoder_candidate.onnx"
    model.save(candidate)
    final_test = evaluate(model, sets["test"])
    export = session(candidate)
    x = sets["test"][0]["features"][0][:128]
    difference = float(np.max(np.abs(export.run(None, {"triplane_features": x[None]})[0][0]-model.forward(x))))
    if difference > 2e-4:
        raise ValueError("ONNX export differs from trained NumPy model")
    regression = [old["id"] for old, new in zip(initial_test["objects"], final_test["objects"]) if new["iou"] < old["iou"]-.01]
    accepted = best_step > 0 and not regression and final_test["mean_iou"] > initial_test["mean_iou"]+.01
    report = {"scope": "actual TripoSR NeRF decoder fine-tuning, frozen INT4 image encoder",
              "trained_parameters": sum(v.size for v in model.parameters)-3*65,
              "frozen_rgb_output_parameters": 3*65,
              "training_steps": args.steps, "selected_step": best_step,
              "encoder_cpu_threads": args.encoder_threads,
              "objects": {k: len(v) for k, v in sets.items()}, "views_per_object": 4, "skipped": skipped,
              "validation_before": initial_validation, "validation_after": best_validation,
              "test_before": initial_test, "test_after": final_test,
              "heldout_regressions": regression, "synthetic_benchmark_passed": accepted,
              "production_approved": False,
              "candidate_sha256": digest(candidate), "candidate_bytes": candidate.stat().st_size,
              "onnx_numpy_max_difference": difference, "duration_seconds": time.monotonic()-started,
              "history": history,
              "limitations": ["Twelve authored synthetic shapes; no real scan or photoreal human supervision.",
                              "Fixed alignment from the base decoder; not the complete Android pipeline.",
                              "No silhouette clipping or texture training; no evidence for user-photo fidelity.",
                              "A small held-out set cannot certify generalisation or production quality.",
                              "RGB output rows frozen, but hidden layer changes can alter predicted RGB.",
                              "Android application and its released weights have not been changed."]}
    (folder / "report.json").write_text(json.dumps(report, indent=2))
    print("RESULT", json.dumps({k: report[k] for k in ("selected_step", "synthetic_benchmark_passed", "production_approved", "candidate_sha256", "duration_seconds")}), flush=True)


if __name__ == "__main__":
    main()
