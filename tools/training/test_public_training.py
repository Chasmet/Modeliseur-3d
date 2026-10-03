import copy
import unittest
import numpy as np

from fetch_public_references import LICENSES, eligible, verify_lock
from train_public_decoder import validate_manifest
from train_mesh_decoder import point_batch
from decoder import Decoder
from train_decoder import ASSETS


class PublicCorpusTests(unittest.TestCase):
    def test_stratified_batches_preserve_the_population_objective(self):
        labels = np.r_[np.ones(2), np.zeros(98)]
        indices, weights = point_batch(labels, np.random.default_rng(55), 256, "stratified")
        self.assertEqual(int(labels[indices].sum()), 128)
        # Exact for any integrand constant within each stratum; no Monte Carlo tolerance.
        values = np.where(labels == 1, 9., 2.)
        self.assertAlmostEqual(float(np.mean(values[indices]*weights)), float(values.mean()))
        for value in (0, 1):
            flat = np.full(10, value)
            _, w = point_batch(flat, np.random.default_rng(1), 32, "stratified")
            np.testing.assert_array_equal(w, 1)

    def test_weighted_gradients_match_finite_differences(self):
        model = Decoder(ASSETS/"triposr_decoder.onnx")
        model.parameters = [v.astype(np.float64) for v in model.parameters]
        model.initial = [v.copy() for v in model.parameters]
        rng = np.random.default_rng(525)
        views = [rng.normal(size=(9, 120))*.4 for _ in range(4)]
        labels, weights = np.arange(9)%2, np.linspace(.03, 1.97, 9)
        for method, inputs in ((model.loss_and_gradient, views[0]),
                               (model.fused_loss_and_gradient, views)):
            _, gradients = method(inputs, labels, sample_weights=weights)
            for index in (0, 8, 18, 19):
                position = np.unravel_index(np.abs(gradients[index]).argmax(), gradients[index].shape)
                old, epsilon = model.parameters[index][position], 1e-5
                model.parameters[index][position] = old+epsilon
                upper, _ = method(inputs, labels, sample_weights=weights)
                model.parameters[index][position] = old-epsilon
                lower, _ = method(inputs, labels, sample_weights=weights)
                model.parameters[index][position] = old
                self.assertAlmostEqual((upper-lower)/(2*epsilon), gradients[index][position], delta=2e-5)

    def test_invalid_importance_weights_are_refused(self):
        for value in ([], [1], [-1, 2], [np.nan, 1], [0, 0]):
            with self.assertRaises(ValueError):
                Decoder._sample_weights([0, 1], value)

    def record(self, index, role):
        return {"id": str(index), "sha256": str(index), "author_id": str(index),
                "split": role, "license": "by", "license_url": LICENSES["by"]}

    def manifest(self):
        return {"references": [self.record(i, role) for i, role in
                enumerate(("train", "validation", "test"))]}

    def test_object_and_author_roles_are_disjoint(self):
        self.assertEqual(len(validate_manifest(self.manifest())), 3)
        for field in ("id", "sha256", "author_id"):
            manifest = self.manifest()
            manifest["references"][2][field] = manifest["references"][0][field]
            with self.assertRaises(ValueError):
                validate_manifest(manifest)

    def test_license_is_required_for_every_object(self):
        for license in (None, "by-nc", "by-nc-sa", "by-sa"):
            manifest = self.manifest()
            manifest["references"][0]["license"] = license
            with self.assertRaises(ValueError):
                validate_manifest(manifest)
        manifest = self.manifest()
        manifest["references"][0]["license_url"] = LICENSES["cc0"]
        with self.assertRaises(ValueError):
            validate_manifest(manifest)

    def test_no_training_without_independent_test(self):
        manifest = self.manifest()
        manifest["references"].pop()
        with self.assertRaises(ValueError):
            validate_manifest(manifest)

    def test_only_bounded_static_candidates_with_attribution(self):
        record = {"license": "by", "animationCount": 0, "faceCount": 1000,
                  "user": {"uid": "creator"}}
        self.assertTrue(eligible(record))
        for key, value in (("animationCount", 1), ("faceCount", 4000000),
                           ("license", "by-nc"), ("user", {})):
            rejected = copy.deepcopy(record)
            rejected[key] = value
            self.assertFalse(eligible(rejected))

    def test_reacquired_dataset_must_match_hashes_roles_and_metadata(self):
        manifest = self.manifest()
        for record in manifest["references"]:
            record.update(uid=record["id"], metadata_sha256="metadata"+record["id"])
        verify_lock(manifest, copy.deepcopy(manifest))
        for field in ("sha256", "metadata_sha256", "split", "author_id", "license"):
            altered = copy.deepcopy(manifest)
            altered["references"][0][field] = "changed"
            with self.assertRaises(ValueError):
                verify_lock(manifest, altered)


if __name__ == "__main__":
    unittest.main()
