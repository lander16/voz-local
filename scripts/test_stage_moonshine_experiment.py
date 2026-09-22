"""Offline integrity regression tests for experiment fixture staging."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("stage", Path(__file__).with_name("stage-moonshine-experiment.py"))
stage = importlib.util.module_from_spec(spec)
spec.loader.exec_module(stage)


class StagingIntegrityTest(unittest.TestCase):
    def _mock_download(self, payload):
        def run(command, **kwargs):
            output = Path(command[command.index("--output") + 1])
            output.write_bytes(payload)
            return None
        return run

    def test_castagnoli_known_vector(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "vector"
            path.write_bytes(b"123456789")
            size, crc, sha = stage.fingerprint(path)
            self.assertEqual((size, crc), (9, "4waSgw=="))
            self.assertEqual(sha, "15e2b0d3c33891ebb0f1ef609ec419420c20e320ce94c65fbc8c3312448eb225")

    def test_corrupt_cached_asset_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "tiny-es").mkdir()
            (root / "tiny-es" / "adapter.ort").write_bytes(b"corrupt")
            with patch.object(stage.subprocess, "run") as download:
                with self.assertRaisesRegex(ValueError, "failed integrity"):
                    stage.stage(root, "tiny-es")
                download.assert_not_called()
            self.assertFalse((root / "tiny-es" / "manifest.json").exists())

    def test_verified_cache_emits_reproducible_sha_manifest(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "tiny-es").mkdir()
            path = root / "tiny-es" / "fixture"
            path.write_bytes(b"123456789")
            with patch.dict(stage.FILES, {"tiny-es": {"fixture": (9, "4waSgw==")}}):
                with patch.object(stage.subprocess, "run") as download:
                    stage.stage(root, "tiny-es")
                    download.assert_not_called()
            manifest = json.loads((root / "tiny-es" / "manifest.json").read_text())
            self.assertEqual(manifest["files"][0]["sha256"], stage.fingerprint(path)[2])
            self.assertEqual(manifest["upstreamRevision"], stage.UPSTREAM)

    def test_download_integrity_mismatch_never_activates_target(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            payload = b"123456789"
            with patch.dict(stage.FILES, {"tiny-es": {"adapter.ort": (9, "not-the-crc")}}):
                with patch.object(stage.subprocess, "run", side_effect=self._mock_download(payload)):
                    with self.assertRaisesRegex(ValueError, "Upstream integrity mismatch"):
                        stage.stage(root, "tiny-es")
            candidate = root / "tiny-es" / "adapter.ort"
            self.assertFalse(candidate.exists())
            self.assertTrue((root / "tiny-es" / "adapter.ort.part").exists())
            self.assertFalse((root / "tiny-es" / "manifest.json").exists())

    def test_corrupt_later_cached_asset_is_rejected_before_activation(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            model_root = root / "tiny-es"
            model_root.mkdir()
            payload = b"123456789"
            with patch.dict(stage.FILES, {"tiny-es": {
                "adapter.ort": (9, "4waSgw=="),
                "encoder.ort": (9, "4waSgw=="),
            }}):
                (model_root / "adapter.ort").write_bytes(payload)
                (model_root / "encoder.ort").write_bytes(b"corrupt")
                with patch.object(stage.subprocess, "run") as download:
                    with self.assertRaisesRegex(ValueError, "failed integrity"):
                        stage.stage(root, "tiny-es")
                    download.assert_not_called()
            self.assertFalse((model_root / "manifest.json").exists())

    def test_stale_partial_is_replaced_by_verified_download(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            model_root = root / "tiny-es"
            model_root.mkdir()
            target = model_root / "adapter.ort"
            partial = model_root / "adapter.ort.part"
            partial.write_bytes(b"stale")
            payload = b"123456789"
            with patch.dict(stage.FILES, {"tiny-es": {"adapter.ort": (9, "4waSgw==")}}):
                with patch.object(stage.subprocess, "run", side_effect=self._mock_download(payload)):
                    stage.stage(root, "tiny-es")
            self.assertEqual(target.read_bytes(), payload)
            self.assertFalse(partial.exists())
            self.assertTrue((model_root / "manifest.json").exists())


if __name__ == "__main__":
    unittest.main()
