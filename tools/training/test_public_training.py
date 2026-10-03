import copy
import unittest

from fetch_public_references import LICENSES, eligible, verify_lock
from train_public_decoder import validate_manifest


class PublicCorpusTests(unittest.TestCase):
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
