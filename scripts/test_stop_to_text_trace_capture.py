"""Synthetic parser tests only; these fixtures are not performance measurements."""

import sys
import argparse
import contextlib
import io
import tempfile
import unittest
from pathlib import Path
from stat import S_IMODE

sys.path.insert(0, str(Path(__file__).resolve().parent))
from stop_to_text_trace_capture import (  # noqa: E402
    create_capture_file,
    capture,
    read_summary,
    summarize_lines,
    trace_key,
)


class TraceSummaryTests(unittest.TestCase):
    def test_counts_complete_rows_by_process_and_trace_id(self):
        complete = (
            "592150.993131 20733 20761 I StopToTextTrace: id=1 source=in_app "
            "model=moonshine_small_es outcome=app_result_published "
            "stop_to_app_state_published=1491ms finalized_at=1498ms "
            "metadata=[pcm_samples:10] spans=[inference:1-2ms] points=[stop_received:0ms]\n"
        )
        summary = summarize_lines([complete, complete, complete.replace("20733", "20734")])

        self.assertEqual(summary["unique_records"], 2)
        self.assertEqual(summary["complete_records"], 2)
        self.assertEqual(summary["duplicate_lines"], 1)
        self.assertEqual([row["pid"] for row in summary["records"]], ["20733", "20734"])

    def test_incomplete_row_is_not_counted_as_complete(self):
        line = (
            "592151.000000 20733 20761 I StopToTextTrace: id=2 source=in_app "
            "model=moonshine_small_es outcome=app_result_published spans=[x] points=[y]\n"
        )
        summary = summarize_lines([line, "unrelated filtered-stream diagnostic\n"])

        self.assertEqual(summary["unique_records"], 1)
        self.assertEqual(summary["complete_records"], 0)
        self.assertEqual(summary["incomplete_records"], 1)
        self.assertEqual(summary["ignored_lines"], 1)

    def test_truncated_tag_line_is_reported_as_malformed(self):
        summary = summarize_lines(["592151.000000 20733 20761 I StopToTextTrace: id="])
        self.assertEqual(summary["unique_records"], 0)
        self.assertEqual(summary["malformed_trace_lines"], 1)

    def test_identity_includes_app_pid_when_trace_ids_restart(self):
        self.assertEqual(
            trace_key("592151.000000 20733 20761 I StopToTextTrace: id=4 payload"),
            ("20733", "4"),
        )
        self.assertEqual(
            trace_key("592151.000000 20734 20761 I StopToTextTrace: id=4 payload"),
            ("20734", "4"),
        )

    def test_file_capture_is_private_exclusive_and_parsed_from_disk(self):
        line = (
            "592150.993131 20733 20761 I StopToTextTrace: id=1 source=in_app "
            "model=moonshine_small_es outcome=app_result_published "
            "stop_to_app_state_published=1491ms finalized_at=1498ms "
            "metadata=[pcm_samples:10] spans=[inference:1-2ms] points=[stop_received:0ms]\n"
        )
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "capture.log"
            rows = [line.replace("id=1", f"id={trace_id}", 1) for trace_id in range(1, 201)]
            with create_capture_file(path) as capture:
                capture.writelines(rows)
                capture.flush()

            self.assertEqual(S_IMODE(path.stat().st_mode), 0o600)
            self.assertGreater(path.stat().st_size, 10_000)
            self.assertEqual(read_summary(path)["complete_records"], 200)
            with self.assertRaises(FileExistsError):
                create_capture_file(path)
            self.assertIn("id=200", path.read_text(encoding="utf-8"))

    def test_fake_adb_replay_filters_baseline_and_stops_after_requested_rows(self):
        baseline = (
            "592150.900000 20733 20761 I StopToTextTrace: id=1 source=in_app "
            "model=moonshine_small_es outcome=app_result_published "
            "stop_to_app_state_published=100ms finalized_at=101ms "
            "metadata=[pcm_samples:10] spans=[inference:1-2ms] points=[stop_received:0ms]\n"
        )
        rows = [
            baseline.replace("id=1", "id=2", 1),
            baseline.replace("id=1", "id=3", 1),
        ]
        with tempfile.TemporaryDirectory() as directory:
            fake_adb = Path(directory) / "fake-adb"
            output = Path(directory) / "captured.log"
            fake_adb.write_text(
                "#!/usr/bin/env python3\n"
                "import signal, sys, time\n"
                f"baseline = {baseline!r}\n"
                f"rows = {rows!r}\n"
                "if '-d' in sys.argv:\n"
                "    print(baseline, end='')\n"
                "    raise SystemExit(0)\n"
                "signal.signal(signal.SIGINT, lambda *_: sys.exit(0))\n"
                "for row in [baseline, *rows]:\n"
                "    print(row, end='', flush=True)\n"
                "while True:\n"
                "    time.sleep(0.05)\n",
                encoding="utf-8",
            )
            fake_adb.chmod(0o700)
            args = argparse.Namespace(
                serial="synthetic-device",
                uid=10595,
                output=str(output),
                adb=str(fake_adb),
                stop_after=2,
            )
            stdout = io.StringIO()
            stderr = io.StringIO()
            with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                result = capture(args)

            self.assertEqual(result, 0, stderr.getvalue())
            summary = read_summary(output)
            self.assertEqual(summary["complete_records"], 2)
            self.assertEqual([row["trace_id"] for row in summary["records"]], [2, 3])
            self.assertNotIn("id=1", output.read_text(encoding="utf-8"))
            self.assertIn('"stop_reason": "expected_complete_rows"', stdout.getvalue())

    def test_fake_adb_disconnect_returns_nonzero_exit_in_summary(self):
        with tempfile.TemporaryDirectory() as directory:
            fake_adb = Path(directory) / "fake-adb"
            output = Path(directory) / "captured.log"
            fake_adb.write_text(
                "#!/usr/bin/env python3\n"
                "import sys\n"
                "if '-d' in sys.argv:\n"
                "    raise SystemExit(0)\n"
                "print('synthetic adb disconnect', file=sys.stderr, flush=True)\n"
                "raise SystemExit(17)\n",
                encoding="utf-8",
            )
            fake_adb.chmod(0o700)
            args = argparse.Namespace(
                serial="synthetic-device",
                uid=10595,
                output=str(output),
                adb=str(fake_adb),
                stop_after=1,
            )
            stdout = io.StringIO()
            stderr = io.StringIO()
            with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                result = capture(args)

            self.assertEqual(result, 2)
            self.assertIn("synthetic adb disconnect", stdout.getvalue())
            self.assertIn('"adb_logcat_exit_code": 17', stdout.getvalue())
            self.assertEqual(read_summary(output)["complete_records"], 0)


if __name__ == "__main__":
    unittest.main()
