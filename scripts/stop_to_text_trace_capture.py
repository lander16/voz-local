#!/usr/bin/env python3
"""Capture and validate metadata-only StopToTextTrace logcat rows."""

from __future__ import annotations

import argparse
from collections import deque
import json
import os
import re
import shutil
import signal
import subprocess
import sys
import threading
from pathlib import Path
from typing import Iterable


LINE_RE = re.compile(
    r"^\s*(?P<time>\d+(?:\.\d+)?)\s+(?P<pid>\d+)\s+\d+\s+"
    r"[VDIWEF]\s+StopToTextTrace:\s+(?P<body>.*)$"
)
ID_RE = re.compile(r"\bid=(\d+)\b")
REQUIRED = (
    "source=",
    "model=",
    "outcome=",
    "finalized_at=",
    "metadata=[",
    "spans=[",
    "points=[",
)
ENDPOINTS = ("stop_to_app_state_published=", "stop_to_overlay_action_accepted=")


def summarize_lines(lines: Iterable[str]) -> dict[str, object]:
    records: dict[tuple[str, str], dict[str, object]] = {}
    duplicate_lines = 0
    ignored_lines = 0
    malformed_trace_lines = 0
    for line in lines:
        normalized = line.rstrip("\r\n")
        match = LINE_RE.match(normalized)
        if not match:
            if "StopToTextTrace:" in normalized:
                malformed_trace_lines += 1
            else:
                ignored_lines += 1
            continue
        body = match.group("body")
        id_match = ID_RE.search(body)
        if not id_match:
            malformed_trace_lines += 1
            continue
        key = (match.group("pid"), id_match.group(1))
        complete = all(token in body for token in REQUIRED) and any(
            token in body for token in ENDPOINTS
        )
        if key in records:
            duplicate_lines += 1
            records[key]["complete"] = bool(records[key]["complete"]) or complete
            continue
        records[key] = {
            "pid": key[0],
            "trace_id": int(key[1]),
            "monotonic_seconds": float(match.group("time")),
            "complete": complete,
        }
    ordered = sorted(records.values(), key=lambda record: (str(record["pid"]), int(record["trace_id"])))
    return {
        "unique_records": len(ordered),
        "complete_records": sum(bool(record["complete"]) for record in ordered),
        "incomplete_records": sum(not bool(record["complete"]) for record in ordered),
        "duplicate_lines": duplicate_lines,
        "ignored_lines": ignored_lines,
        "malformed_trace_lines": malformed_trace_lines,
        "records": ordered,
    }


def trace_key(line: str) -> tuple[str, str] | None:
    match = LINE_RE.match(line.rstrip("\r\n"))
    if not match:
        return None
    id_match = ID_RE.search(match.group("body"))
    return (match.group("pid"), id_match.group(1)) if id_match else None


def read_summary(path: Path) -> dict[str, object]:
    with path.open("r", encoding="utf-8", errors="replace") as stream:
        return summarize_lines(stream)


def create_capture_file(path: Path):
    """Create a private, new capture file; never truncate existing evidence."""
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    return os.fdopen(descriptor, "w", encoding="utf-8")


def capture(args: argparse.Namespace) -> int:
    output = Path(args.output)
    if not output.is_absolute():
        print("capture output path must be absolute", file=sys.stderr)
        return 2
    if not output.parent.is_dir():
        print("capture output directory must already exist", file=sys.stderr)
        return 2
    adb = args.adb or shutil.which("adb")
    if not adb:
        print("adb was not found; pass --adb with its absolute path", file=sys.stderr)
        return 2
    command = [
        adb,
        "-s",
        args.serial,
        "logcat",
        "-v",
        "monotonic,usec",
        f"--uid={args.uid}",
        "-s",
        "StopToTextTrace:I",
    ]
    try:
        try:
            baseline = subprocess.run(
                [*command[:4], "-d", *command[4:]],
                check=True,
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="replace",
                timeout=15,
            )
        except subprocess.CalledProcessError as error:
            detail = (error.stderr or "").strip()
            print(
                f"filtered baseline snapshot failed (adb exit {error.returncode}); "
                f"no capture file was opened. {detail}",
                file=sys.stderr,
            )
            return 2
        except subprocess.TimeoutExpired:
            print("filtered baseline snapshot timed out; no capture file was opened", file=sys.stderr)
            return 2
        baseline_summary = summarize_lines(baseline.stdout.splitlines())
        baseline_keys = {
            (str(record["pid"]), str(record["trace_id"]))
            for record in baseline_summary["records"]
        }
        # Exclusive create prevents accidental truncation of earlier evidence.
        # The capture's stdout is the filtered stream itself, never a terminal buffer.
        with create_capture_file(output) as stream:
            print(
                f"capture_started output={output} preexisting_unique_ids={len(baseline_keys)}",
                flush=True,
            )
            process = subprocess.Popen(
                command,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                encoding="utf-8",
                errors="replace",
                bufsize=1,
            )
            stderr_tail: deque[str] = deque(maxlen=20)

            def drain_stderr() -> None:
                assert process.stderr is not None
                for error_line in process.stderr:
                    stderr_tail.append(error_line[-1000:])

            stderr_reader = threading.Thread(target=drain_stderr, daemon=True)
            stderr_reader.start()
            stop_reason = "adb_stream_ended"
            complete_keys: set[tuple[str, str]] = set()
            try:
                assert process.stdout is not None
                for line in process.stdout:
                    # logcat may replay buffered rows when it starts. The snapshot above
                    # identifies those rows by app PID + per-process trace ID.
                    if trace_key(line) in baseline_keys:
                        continue
                    stream.write(line)
                    stream.flush()
                    key = trace_key(line)
                    if key is not None and summarize_lines([line])["complete_records"] == 1:
                        complete_keys.add(key)
                    if args.stop_after is not None and len(complete_keys) >= args.stop_after:
                        stop_reason = "expected_complete_rows"
                        break
            except KeyboardInterrupt:
                stop_reason = "keyboard_interrupt"
            finally:
                if process.poll() is None:
                    process.send_signal(signal.SIGINT)
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
                if process.stdout is not None:
                    process.stdout.close()
                stderr_reader.join(timeout=2)
                logcat_exit_code = process.returncode
                if process.stderr is not None:
                    process.stderr.close()
                adb_stderr_tail = "".join(stderr_tail)[-3000:].strip()
    except FileExistsError:
        print("refusing to overwrite an existing capture file", file=sys.stderr)
        return 2
    except OSError as error:
        print(f"capture failed: {error}", file=sys.stderr)
        return 2

    summary = read_summary(output)
    print(json.dumps({
        "capture_stopped": True,
        "stop_reason": stop_reason,
        "adb_logcat_exit_code": logcat_exit_code,
        "adb_logcat_stderr_tail": adb_stderr_tail,
        "output": str(output),
        **summary,
    }, sort_keys=True))
    if stop_reason == "expected_complete_rows" and int(summary["complete_records"]) >= args.stop_after:
        return 0
    if stop_reason == "keyboard_interrupt" and int(summary["complete_records"]) > 0:
        return 0
    return 2 if logcat_exit_code not in (0, -signal.SIGINT) else 1


def validate(args: argparse.Namespace) -> int:
    path = Path(args.file)
    if not path.is_file():
        print(f"capture file does not exist: {path}", file=sys.stderr)
        return 2
    summary = read_summary(path)
    print(json.dumps({"file": str(path), **summary}, sort_keys=True))
    complete_records = int(summary["complete_records"])
    if args.expected_complete is not None and complete_records != args.expected_complete:
        return 1
    if args.expected_complete is None and complete_records < args.minimum_complete:
        return 1
    if summary["incomplete_records"] or summary["malformed_trace_lines"]:
        return 1
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    capture_parser = subparsers.add_parser("capture", help="stream one UID/tag to an exclusive local file")
    capture_parser.add_argument("--serial", required=True, help="ADB device serial")
    capture_parser.add_argument("--uid", required=True, type=int, help="VozLocal Android UID")
    capture_parser.add_argument("--output", required=True, help="new absolute private local file path")
    capture_parser.add_argument("--adb", help="absolute adb path; defaults to PATH lookup")
    capture_parser.add_argument("--stop-after", type=int, help="stop after this many new complete trace rows")
    capture_parser.set_defaults(func=capture)

    validate_parser = subparsers.add_parser("validate", help="count unique and complete trace rows in a capture")
    validate_parser.add_argument("--file", required=True)
    validate_parser.add_argument("--minimum-complete", type=int, default=1)
    validate_parser.add_argument("--expected-complete", type=int)
    validate_parser.set_defaults(func=validate)

    args = parser.parse_args()
    if getattr(args, "uid", 0) < 0:
        parser.error("UID must be non-negative")
    if getattr(args, "stop_after", None) is not None and args.stop_after < 1:
        parser.error("--stop-after must be positive")
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
