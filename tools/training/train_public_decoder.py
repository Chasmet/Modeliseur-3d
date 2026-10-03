"""Run decoder research on attributed public references; no APK replacement."""
import argparse
import json
from pathlib import Path
import subprocess
import sys
import shutil

from fetch_public_references import LICENSES, ROOT, digest


def validate_manifest(manifest):
    records = manifest["references"]
    hashes, authors, ids = set(), set(), set()
    for record in records:
        if record["license"] not in LICENSES:
            raise ValueError("Unsupported public reference license")
        if record["license_url"] != LICENSES[record["license"]]:
            raise ValueError("Reference license URL differs from recorded license")
        if record["sha256"] in hashes or record["author_id"] in authors or record["id"] in ids:
            raise ValueError("Duplicate author or reference can leak across splits")
        if record["split"] not in ("train", "validation", "test"):
            raise ValueError("Unknown reference role")
        hashes.add(record["sha256"])
        authors.add(record["author_id"])
        ids.add(record["id"])
    if {r["split"] for r in records} != {"train", "validation", "test"}:
        raise ValueError("Need nonempty train, validation and test sets")
    return records


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--steps", type=int, default=800)
    parser.add_argument("--sampling", choices=("uniform", "stratified"), default="uniform")
    parser.add_argument("--reuse-cache", type=Path)
    args = parser.parse_args()
    manifest = json.loads(args.manifest.read_text())
    records = validate_manifest(manifest)
    output = args.output.resolve()
    if ROOT/"build" not in output.parents or output.exists():
        raise ValueError("Use a new experiment directory inside ignored build/")
    output.mkdir(parents=True)
    if args.reuse_cache:
        previous = args.reuse_cache.resolve()
        if ROOT/"build" not in previous.parents:
            raise ValueError("Cache must come from ignored build/")
        from fetch_public_references import verify_lock
        verify_lock(json.loads((previous/"public-provenance.json").read_text()), manifest)
        for name in ("scenes", "references"):
            shutil.copytree(previous/name, output/name)
    command = [sys.executable, str(Path(__file__).with_name("train_mesh_decoder.py")),
               "--steps", str(args.steps), "--objective", "four-view", "--grid", "28",
               "--sampling", args.sampling, "--output", str(output)]
    for record in records:
        if digest(record["source_path"]) != record["sha256"]:
            raise ValueError("Downloaded reference content changed")
        command += ["--"+record["split"]+"-reference", record["id"], record["source_path"]]
    # Persist provenance before starting the costly encoder, even if interrupted.
    (output/"public-provenance.json").write_text(json.dumps(manifest, indent=2, ensure_ascii=False))
    subprocess.run(command, check=True, cwd=ROOT)
    path = output/"report.json"
    report = json.loads(path.read_text())
    report["version"] = "public-objaverse-decoder-v1"
    report["reference_origin"] = "Objaverse; individual CC-BY 4.0 or CC0 metadata and SHA-256 retained"
    report["provenance_manifest_sha256"] = digest(output/"public-provenance.json")
    report["limitations"][0] = f"{len(records)} public authored meshes; scan status not independently established; rendered views, no real photos."
    report["limitations"][1] = "Object and author split fixed before inference; validation selects weights; test never selects checkpoints."
    report["limitations"][7] = "Attributions preserved; public references do not establish generalization to personal photographs."
    report["limitations"].append("Test objects are withheld from this adaptation; membership in original TripoSR pretraining data is unknown.")
    report["limitations"].append("Previously inspected synthetic test objects are regression controls, not a new independent generalization benchmark.")
    path.write_text(json.dumps(report, indent=2))
    print("PUBLIC_EXPERIMENT_COMPLETE", report["selected_step"], flush=True)


if __name__ == "__main__":
    main()
