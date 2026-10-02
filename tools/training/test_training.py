"""Meaningful checks for custom CPU backprop, coordinate convention and export."""
import tempfile
import hashlib
import json
import unittest
from pathlib import Path
import numpy as np
from decoder import Decoder, sample_planes
from references import to_world, to_local, parts, occupancy, render
from train_decoder import ASSETS, ROOT, session


class TrainingTests(unittest.TestCase):
    def test_archived_candidate_hash_parameters_and_runtime_match_its_report(self):
        folder = ROOT / "tools/training/experiments/cpu-001"
        report = json.loads((folder / "report.json").read_text())
        audit = json.loads((folder / "audit.json").read_text())
        path = folder / "triposr_decoder_candidate.onnx"
        self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(), report["candidate_sha256"])
        self.assertFalse(report["production_approved"])
        base, candidate = Decoder(ASSETS / "triposr_decoder.onnx"), Decoder(path)
        changed = sum(int(np.count_nonzero(a != b)) for a, b in zip(base.parameters, candidate.parameters))
        self.assertEqual(changed, audit["changed_float_parameters"])
        self.assertGreater(changed, 40000)
        np.testing.assert_array_equal(base.parameters[-2][1:], candidate.parameters[-2][1:])
        x = np.random.default_rng(19).normal(size=(32, 120)).astype(np.float32)
        actual = session(path).run(None, {"triplane_features": x[None]})[0][0]
        self.assertTrue(np.isfinite(actual).all())
        np.testing.assert_allclose(actual, candidate.forward(x), atol=2e-5, rtol=2e-5)

    def test_actual_gradient_matches_finite_differences_in_hidden_and_output_layers(self):
        model = Decoder(ASSETS / "triposr_decoder.onnx")
        model.parameters = [v.astype(np.float64) for v in model.parameters]
        model.initial = [v.copy() for v in model.parameters]
        x = np.random.default_rng(72).normal(size=(9, 120))*.4
        labels = np.arange(9) % 2
        _, gradients = model.loss_and_gradient(x, labels)
        for index in (0, 8, 16, 18, 19):
            location = np.unravel_index(np.abs(gradients[index]).argmax(), gradients[index].shape)
            old = model.parameters[index][location]
            epsilon = 1e-5
            model.parameters[index][location] = old+epsilon
            upper, _ = model.loss_and_gradient(x, labels)
            model.parameters[index][location] = old-epsilon
            lower, _ = model.loss_and_gradient(x, labels)
            model.parameters[index][location] = old
            self.assertAlmostEqual((upper-lower)/(2*epsilon), gradients[index][location], delta=2e-5)

    def test_trained_onnx_export_matches_the_cpu_model_and_keeps_rgb_output_rows(self):
        model = Decoder(ASSETS / "triposr_decoder.onnx")
        x = np.random.default_rng(45).normal(size=(32, 120)).astype(np.float32)
        old_rows = model.parameters[-2][1:].copy()
        _, gradients = model.loss_and_gradient(x, np.arange(32) % 2)
        model.update(gradients, 2e-5)
        np.testing.assert_array_equal(old_rows, model.parameters[-2][1:])
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "candidate.onnx"
            model.save(path)
            actual = session(path).run(None, {"triplane_features": x[None]})[0][0]
            np.testing.assert_allclose(actual, model.forward(x), atol=2e-5, rtol=2e-5)

    def test_four_view_rotations_are_inverse_and_planes_have_zero_padding(self):
        points = np.random.default_rng(9).normal(size=(30, 3)).astype(np.float32)
        for view in range(4):
            np.testing.assert_array_equal(points, to_world(to_local(points, view), view))
        scene = np.ones((3, 40, 64, 64), np.float32)
        out = sample_planes(scene, np.array([[0, 0, 0], [1, 1, 1], [2, 2, 2]], np.float32))
        np.testing.assert_array_equal(out[0], np.ones(120))
        np.testing.assert_allclose(out[1], .25)
        np.testing.assert_array_equal(out[2], np.zeros(120))

    def test_reference_labels_are_known_geometry_and_all_four_renders_have_alpha(self):
        for family in ("bottle", "chair", "figure"):
            geometry = parts(family, 101)
            self.assertFalse(occupancy(np.array([[2, 2, 2]], np.float32), geometry)[0])
            for view in range(4):
                image = render(geometry, view, side=48)
                self.assertGreater(image.width, 3)
                self.assertGreater(image.height, 3)
                self.assertEqual(np.asarray(image)[..., 3].max(), 255)


if __name__ == "__main__":
    unittest.main()
