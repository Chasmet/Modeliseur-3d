import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("generate", Path(__file__).parents[1] / "generate.py")
generate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(generate)


class QuotaClient:
    session_hash = "test-session"
    def __init__(self, prepared):
        self.prepared = prepared
        self.calls = []
    def predict(self, **kwargs):
        name = kwargs["api_name"]
        self.calls.append(name)
        if name == "/preprocess_image": return str(self.prepared)
        if name == "/image_to_3d": return "<p>Modèle calculé</p>"
        if name == "/extract_glb": raise RuntimeError("ZeroGPU quota exceeded")


class GenerateTest(unittest.TestCase):
    def test_quota_preserves_generated_stage_and_resume_does_not_regenerate(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            image = root / "input.png"
            image.write_bytes(b"fixture")
            client = QuotaClient(image)
            with self.assertRaisesRegex(RuntimeError, "quota"):
                generate.run(client, image, root / "out", "1024")
            state = json.loads((root / "out/checkpoint.json").read_text())
            self.assertEqual(state["stage"], "generated")
            self.assertTrue((root / "out/preview.html").is_file())
            client.calls.clear()
            with self.assertRaisesRegex(RuntimeError, "quota"):
                generate.run(client, image, root / "out", "1024", resume=True)
            self.assertEqual(client.calls, ["/extract_glb"])
            image.write_bytes(b"different")
            with self.assertRaisesRegex(ValueError, "référence"):
                generate.run(client, image, root / "out", "1024", resume=True)


if __name__ == "__main__":
    unittest.main()
