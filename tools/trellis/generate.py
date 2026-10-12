"""Official TRELLIS.2 client. Never retries a GPU quota denial automatically."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import sys

SPACE = "https://microsoft-trellis-2.hf.space/"


def atomic_json(path, data):
    temporary = path.with_suffix(".tmp")
    temporary.write_text(json.dumps(data, indent=2), encoding="utf-8")
    os.chmod(temporary, 0o600)
    temporary.replace(path)


def run(client, reference, output, resolution, resume=False):
    output.mkdir(parents=True, exist_ok=True)
    checkpoint = output / "checkpoint.json"
    identity = hashlib.sha256(reference.read_bytes()).hexdigest()
    state = {"image_sha256": identity, "resolution": resolution, "stage": "new"}
    if resume and checkpoint.exists():
        state = json.loads(checkpoint.read_text(encoding="utf-8"))
        if state["image_sha256"] != identity or state["resolution"] != resolution:
            raise ValueError("La référence ou la résolution a changé : utilisez une nouvelle sortie.")
        client.session_hash = state["session_hash"]
    else:
        state["session_hash"] = client.session_hash
        atomic_json(checkpoint, state)
        client.predict(api_name="/start_session")

    raw = output / "generated.glb"
    if state["stage"] == "exported" and raw.is_file():
        print("GLB déjà récupéré.", flush=True)
        return raw
    if state["stage"] == "new":
        from gradio_client import handle_file
        print("Préparation de la référence…", flush=True)
        prepared = client.predict(input=handle_file(str(reference)), api_name="/preprocess_image")
        prepared = prepared if isinstance(prepared, str) else prepared["path"]
        shutil.copyfile(prepared, output / "prepared.png")
        state["stage"] = "prepared"
        atomic_json(checkpoint, state)
    if state["stage"] == "prepared":
        from gradio_client import handle_file
        print("Génération 3D sur le service GPU…", flush=True)
        preview = client.predict(
            image=handle_file(str(output / "prepared.png")),
            seed=20261001, resolution=resolution, api_name="/image_to_3d",
        )
        # Keep the useful visual result even if the separate GPU export is denied.
        (output / "preview.html").write_text(str(preview), encoding="utf-8")
        state["stage"] = "generated"
        atomic_json(checkpoint, state)
    print("Extraction du GLB…", flush=True)
    result = client.predict(decimation_target=150000, texture_size=2048, api_name="/extract_glb")
    exported = Path(result[0])
    if exported.stat().st_size < 20 or exported.read_bytes()[:4] != b"glTF":
        raise ValueError("Le service n’a pas retourné de GLB valide.")
    shutil.copyfile(exported, raw)
    state["stage"] = "exported"
    atomic_json(checkpoint, state)
    return raw


def main():
    parser = argparse.ArgumentParser(description="Générer un GLB avec la démo officielle TRELLIS.2")
    parser.add_argument("--image", required=True, type=Path)
    parser.add_argument("--output", type=Path, default=Path("output/trellis"))
    parser.add_argument("--resolution", choices=["512", "1024", "1536"], default="1024")
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()
    if not args.image.is_file():
        parser.error("Image introuvable.")
    from gradio_client import Client
    args.output.mkdir(parents=True, exist_ok=True)
    # Tokens, if supplied through the server environment, never enter Android or logs.
    try:
        client = Client(SPACE, token=os.environ.get("HF_TOKEN"), verbose=False,
                        download_files=str(args.output / "cache"), httpx_kwargs={"timeout": 90})
        run(client, args.image, args.output, args.resolution, args.resume)
    except Exception as error:
        message = str(error)
        if "quota" in message.lower():
            print("Quota GPU refusé : arrêt sans nouvelle tentative. La génération éventuelle et son aperçu sont conservés.", file=sys.stderr)
            print("La reprise de l’export exige que la session distante soit encore active ; ses données peuvent expirer.", file=sys.stderr)
            return 3
        print("Génération interrompue. Vérifiez la disponibilité du service et la session distante.", file=sys.stderr)
        # Keep private URLs, session IDs and credentials out of workflow logs.
        return 1
    print("GLB récupéré ; animation et compression peuvent commencer.", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
