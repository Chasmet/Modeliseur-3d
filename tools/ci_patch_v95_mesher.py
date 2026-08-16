#!/usr/bin/env python3
"""Chaîne atomique V9.5 mémoire + V5.5 2.5D + V9.5.1 streaming + V9.5.2 logs + V9.5.3 meshing final."""
from pathlib import Path
import subprocess

CORE_COMMIT = "76c425589ef35ed37f886cc757500783680450fd"
CORE_PATH = "tools/ci_patch_v95_mesher.py"
TMP = Path("/tmp/ci_patch_v95_mesher_core.py")


def git_show_core() -> bytes:
    command = ["git", "show", f"{CORE_COMMIT}:{CORE_PATH}"]
    result = subprocess.run(command, capture_output=True)
    if result.returncode == 0:
        return result.stdout

    subprocess.run(
        ["git", "fetch", "--no-tags", "--depth=1", "origin", CORE_COMMIT],
        check=True,
    )
    result = subprocess.run(command, capture_output=True, check=True)
    return result.stdout


TMP.write_bytes(git_show_core())
subprocess.run(["python3", str(TMP)], check=True)
subprocess.run(["python3", "tools/ci_patch_v955_25d_shell.py"], check=True)
subprocess.run(["python3", "tools/ci_patch_v951_kart_memory_streaming.py"], check=True)
subprocess.run(["python3", "tools/ci_patch_v952_runtime_log.py"], check=True)
subprocess.run(["python3", "tools/ci_patch_v953_final_meshing.py"], check=True)
print(
    "V9.5.3 FINAL appliquée : mémoire V9.5 + coque 2.5D + streaming kart + "
    "logs copiables + meshing indexé pré-dimensionné."
)
