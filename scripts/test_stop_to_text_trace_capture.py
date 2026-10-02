"""Synthetic parser tests only; these fixtures are not performance measurements."""

import sys
import tempfile
import unittest
from pathlib import Path
from stat import S_IMODE

sys.path.insert(0, str(Path(__file__).resolve().parent))
from stop_to_text_trace_capture import (  # noqa: E402
    create_capture_file,
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


if __name__ == "__main__":
    unittest.main()
