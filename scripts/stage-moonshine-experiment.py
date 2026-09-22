#!/usr/bin/env python3
"""Stage official P11 experiment models; never install or modify production data.

Usage: python3 scripts/stage-moonshine-experiment.py --output /tmp/experiment
Run once per candidate with --model tiny-es or small-es. Assets stay outside Git.
Size/CRC32C pins come from the upstream v0.1.5 generated metadata registry;
the resulting SHA-256 manifest is verified again by the device runner.
"""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import subprocess

UPSTREAM = "234f60faa0eb388b01cdf7e60aca232af37aefda"
FILES = {
    "tiny-es": {
        "adapter.ort": (1318472, "+JOBGg=="),
        "cross_kv.ort": (1288120, "lvRCDw=="),
        "decoder_kv.ort": (19717336, "KEm/xA=="),
        "encoder.ort": (7772792, "AAXCVA=="),
        "frontend.model.ort": (23176, "v7nfyQ=="),
        "frontend.weights.ort": (2093280, "LYbiaA=="),
        "streaming_config.json": (509, "wH/VeA=="),
        "tokenizer.bin": (102888, "/7v8NQ=="),
    },
    "small-es": {
        "adapter.ort": (2869296, "ZCOnJw=="),
        "cross_kv.ort": (5358752, "B6G3LA=="),
        "decoder_kv.ort": (61314512, "Q6TnrQ=="),
        "encoder.ort": (44358376, "EYLK1A=="),
        "frontend.model.ort": (26776, "bvdZXg=="),
        "frontend.weights.ort": (7769280, "ITuX4A=="),
        "streaming_config.json": (512, "Y/oEHw=="),
        "tokenizer.bin": (102888, "/7v8NQ=="),
    },
}


def crc_table():
    table = []
    for value in range(256):
        for _ in range(8):
            value = (value >> 1) ^ (0x82F63B78 if value & 1 else 0)
        table.append(value)
    return table


TABLE = crc_table()


def fingerprint(path):
    sha = hashlib.sha256()
    crc = 0xFFFFFFFF
    size = 0
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            size += len(block)
            sha.update(block)
            for byte in block:
                crc = TABLE[(crc ^ byte) & 255] ^ (crc >> 8)
    checksum = base64.b64encode((crc ^ 0xFFFFFFFF).to_bytes(4, "big")).decode()
    return size, checksum, sha.hexdigest()


def stage(output, model):
    root = output / model
    root.mkdir(parents=True, exist_ok=True)
    base = f"https://download.moonshine.ai/model/{model.replace('-es', '-streaming-es')}/quantized_26_08_24"
    entries = []
    for name, (size, crc) in FILES[model].items():
        target = root / name
        if not target.exists():
            partial = root / (name + ".part")
            print(f"Downloading {model}/{name}", flush=True)
            subprocess.run(["curl", "--fail", "--silent", "--show-error", "--location",
                            "--proto", "=https", "--proto-redir", "=https",
                            "--max-time", "180", "--max-filesize", str(size),
                            "--output", str(partial), f"{base}/{name}"], check=True)
            actual_size, actual_crc, sha = fingerprint(partial)
            if (actual_size, actual_crc) != (size, crc):
                raise ValueError(f"Upstream integrity mismatch: {name}")
            partial.replace(target)
        else:
            actual_size, actual_crc, sha = fingerprint(target)
            if (actual_size, actual_crc) != (size, crc):
                raise ValueError(f"Existing asset failed integrity: {target}")
        entries.append({"name": name, "size": size, "sha256": sha, "crc32c": crc, "url": f"{base}/{name}"})
    manifest = {"model": model, "runtime": "0.1.5", "upstreamRevision": UPSTREAM, "files": entries}
    (root / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(f"Verified {model}: {sum(entry['size'] for entry in entries)} bytes")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--model", choices=FILES, required=True)
    args = parser.parse_args()
    stage(args.output, args.model)
