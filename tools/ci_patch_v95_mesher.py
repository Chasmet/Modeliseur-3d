#!/usr/bin/env python3
"""Chaîne atomique des patches V9.5 mémoire + V5.5 coque 2.5D + V9.5.1 kart streaming + V9.5.2 diagnostic."""
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
print("V9.5 mémoire + V5.5 coque 2.5D + V9.5.1 kart streaming + V9.5.2 diagnostic appliqués.")
