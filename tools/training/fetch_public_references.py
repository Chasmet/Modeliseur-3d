"""Bounded Objaverse acquisition with attribution, hashes and geometry checks.

No model downloads occur in Android. Output belongs to ignored build/. Objects
are selected by reference quality, never by decoder evaluation scores.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
import gzip
import hashlib
import json
from pathlib import Path
import re
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
BASE = "https://huggingface.co/datasets/allenai/objaverse/resolve/main/"
LICENSES = {"by": "https://creativecommons.org/licenses/by/4.0/",
            "cc0": "https://creativecommons.org/publicdomain/zero/1.0/"}


def digest(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def eligible(record):
    return (record.get("license") in LICENSES and record.get("animationCount") == 0
            and 500 <= record.get("faceCount", 0) <= 200000
            and bool(record.get("user", {}).get("uid")))


def download(relative, target, bound=64*1024**2):
    if not re.fullmatch(r"(?:glbs/[0-9]{3}-[0-9]{3}/[a-f0-9]{32}\.glb|metadata/[0-9]{3}-[0-9]{3}\.json\.gz|object-paths\.json\.gz)", relative):
        raise ValueError("Unexpected dataset path")
    target = Path(target)
    if target.is_file():
        if target.stat().st_size > bound:
            raise ValueError("Cached reference exceeds bound")
        return target
    target.parent.mkdir(parents=True, exist_ok=True)
    part = target.with_suffix(target.suffix+".part")
    try:
        request = urllib.request.Request(BASE+relative+"?download=true",
                                         headers={"User-Agent": "Modeliseur3D-Research"})
        with urllib.request.urlopen(request, timeout=35) as source, part.open("wb") as out:
            count = 0
            while chunk := source.read(1024*1024):
                count += len(chunk)
                if count > bound:
                    raise ValueError("Download exceeds bound")
                out.write(chunk)
        part.replace(target)
        return target
    finally:
        part.unlink(missing_ok=True)


def acquire(folder, uids, count):
    import numpy as np
    from mesh_references import MeshReference, prepare_reference
    folder = Path(folder).resolve()
    if ROOT/"build" not in folder.parents:
        raise ValueError("Store acquired references inside ignored build/")
    if not 1 <= len(uids) <= 64 or len(set(uids)) != len(uids):
        raise ValueError("Use 1 to 64 distinct candidate UIDs")
    folder.mkdir(parents=True, exist_ok=True)
    index = download("object-paths.json.gz", folder/"object-paths.json.gz")
    paths = json.loads(gzip.decompress(index.read_bytes()))
    shards, candidates = {}, []
    for uid in uids:
        if not re.fullmatch(r"[a-f0-9]{32}", uid) or uid not in paths:
            raise ValueError("Unknown object UID")
        shard = paths[uid].split("/")[1]
        if shard not in shards:
            path = download("metadata/"+shard+".json.gz", folder/("metadata-"+shard+".json.gz"))
            shards[shard] = json.loads(gzip.decompress(path.read_bytes()))
        record = shards[shard][uid]
        if eligible(record):
            candidates.append((uid, record))

    def get(item):
        uid, metadata = item
        try:
            path = download(paths[uid], folder/"meshes"/(uid+".glb"))
            return uid, metadata, path, None
        except Exception as error:
            return uid, metadata, None, str(error)

    accepted, rejected, authors, hashes = [], [], set(), set()
    with ThreadPoolExecutor(max_workers=4) as pool:
        downloaded = list(pool.map(get, candidates))
    axis = np.linspace(-1, 1, 28, dtype=np.float32)
    grid = np.stack(np.meshgrid(axis, axis, axis, indexing="ij"), -1).reshape(-1, 3)
    for uid, metadata, path, error in downloaded:
        if len(accepted) == count:
            break
        try:
            if error:
                raise ValueError(error)
            author = metadata["user"]["uid"]
            sha = digest(path)
            if author in authors or sha in hashes:
                raise ValueError("Same author or duplicate content already in pilot corpus")
            reference = MeshReference(path)
            labels, reliable = reference.labels(grid, 2/27)
            if reliable.mean() < .75 or labels[reliable].sum() < 100:
                raise ValueError("Insufficient trustworthy volume for occupancy supervision")
            identifier = "obj-"+uid
            audit = prepare_reference(path, folder/"references"/identifier, identifier, resolution=28)
            record = {"id": identifier, "uid": uid, "name": metadata["name"],
                      "author": metadata["user"]["displayName"], "author_id": author,
                      "author_url": metadata["user"]["profileUrl"],
                      "source_url": metadata["viewerUrl"], "download_path": paths[uid],
                      "license": metadata["license"], "license_url": LICENSES[metadata["license"]],
                      "source_path": str(path), "sha256": sha,
                      "metadata_sha256": hashlib.sha256(json.dumps(metadata, sort_keys=True).encode()).hexdigest(),
                      "reliable_fraction": float(reliable.mean()), "closed_components": audit["closed_components"],
                      "reference_kind": "public authored mesh; scan status not independently established"}
            accepted.append(record)
            authors.add(author)
            hashes.add(sha)
            print("ACCEPT", len(accepted), metadata["name"], float(reliable.mean()), flush=True)
        except Exception as error:
            rejected.append({"uid": uid, "name": metadata["name"], "reason": str(error)})
            print("REJECT", metadata["name"], str(error), flush=True)
    if len(accepted) != count:
        (folder/"rejected.json").write_text(json.dumps(rejected, indent=2))
        raise ValueError(f"Only {len(accepted)}/{count} references passed; no partial training corpus")
    # Stable object/author split fixed before any decoder metrics are inspected.
    roles = ["train", "train", "validation", "test"]
    for i, record in enumerate(accepted):
        record["split"] = roles[i % len(roles)]
    manifest = {"version": "objaverse-pilot-v1", "dataset_url": "https://huggingface.co/datasets/allenai/objaverse",
                "dataset_license": "ODC-By 1.0", "object_paths_sha256": digest(index),
                "references": accepted, "rejected": rejected, "views_per_object": 4,
                "split_policy": "distinct objects and authors; fixed before inference; no metric filtering",
                "production_approved": False}
    (folder/"public-manifest.json").write_text(json.dumps(manifest, indent=2, ensure_ascii=False))
    (folder/"ATTRIBUTION.md").write_text("# Objaverse pilot references\n\nDataset: Allen Institute for AI, ODC-By 1.0.\n\n"+
        "\n".join(f"- [{r['name']}]({r['source_url']}) — [{r['author']}]({r['author_url']}), [license]({r['license_url']}); four renders and normalized occupancy targets derived for research." for r in accepted)+"\n")
    return manifest


def verify_lock(expected, actual):
    fields = ("uid", "sha256", "metadata_sha256", "license", "author_id", "split")
    def keys(manifest):
        return [tuple(record[field] for field in fields) for record in manifest["references"]]
    if keys(expected) != keys(actual):
        raise ValueError("Acquired corpus differs from pinned experiment; do not train")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--uids", nargs="+", required=True)
    parser.add_argument("--count", type=int, choices=(4, 8, 12), default=8)
    parser.add_argument("--expected-manifest", type=Path)
    args = parser.parse_args()
    result = acquire(args.output, args.uids, args.count)
    if args.expected_manifest:
        verify_lock(json.loads(args.expected_manifest.read_text()), result)
