"""Closed-volume, privacy, transform and textured four-view importer checks."""
import argparse
import json
import struct
from pathlib import Path
import tempfile
import unittest

import numpy as np
from PIL import Image
import trimesh

from mesh_references import MeshReference, document, prepare_reference
from train_mesh_decoder import reference_specs, precision_regressions, geometry_regressions


class MeshTrainingTests(unittest.TestCase):
    def write_scene(self, folder, meshes):
        scene = trimesh.Scene()
        for index, (mesh, transform) in enumerate(meshes):
            scene.add_geometry(mesh, node_name=f"part-{index}", transform=transform)
        # Binary sniffing must work even when the user file has a .txt suffix.
        path = Path(folder)/"reference.glb.txt"
        path.write_bytes(scene.export(file_type="glb"))
        return path

    def test_world_transforms_and_union_of_overlapping_solids(self):
        transforms = [trimesh.transformations.translation_matrix([x, .5, 0]) for x in (-.3, .3)]
        with tempfile.TemporaryDirectory() as directory:
            path = self.write_scene(directory, [(trimesh.creation.box(), t) for t in transforms])
            original = path.read_bytes()
            reference = MeshReference(path)
            self.assertEqual(len(reference.solids), 2)
            self.assertEqual(reference.audit["holes_filled"], 0)
            np.testing.assert_allclose(reference.audit["original_bounds"], [[-.8, 0, -.5], [.8, 1, .5]])
            points = (np.array([[0, .5, 0], [-.7, .5, 0], [0, 2, 0]])-reference.center)/reference.scale
            labels, reliable = reference.labels(points, .01)
            np.testing.assert_array_equal(labels, [1, 1, 0])
            self.assertTrue(reliable.all())
            self.assertEqual(path.read_bytes(), original)

    def test_open_surface_is_masked_and_never_silently_filled(self):
        triangle = trimesh.Trimesh(vertices=[[1.2, 0, 0], [1.7, 0, 0], [1.2, .5, 0]], faces=[[0, 1, 2]], process=False)
        with tempfile.TemporaryDirectory() as directory:
            path = self.write_scene(directory, [(trimesh.creation.box(), np.eye(4)), (triangle, np.eye(4))])
            reference = MeshReference(path)
            self.assertEqual(len(reference.solids), 1)
            self.assertEqual(len(reference.uncertain), 1)
            points = (np.array([[0, 0, 0], [1.3, .1, 0], [1.3, .1, .6]])-reference.center)/reference.scale
            labels, reliable = reference.labels(points, .05)
            np.testing.assert_array_equal(labels, [1, 0, 0])
            np.testing.assert_array_equal(reliable, [True, False, True])
            self.assertEqual(reference.audit["holes_filled"], 0)

    def test_inconsistent_winding_cannot_become_a_training_solid(self):
        box = trimesh.creation.box()
        box.faces[0] = box.faces[0][::-1]
        with tempfile.TemporaryDirectory() as directory:
            path = self.write_scene(directory, [(box, np.eye(4))])
            with self.assertRaisesRegex(ValueError, "No closed"):
                MeshReference(path)

    def test_texture_uv_orientation_and_four_view_visibility(self):
        texture = np.zeros((32, 32, 3), np.uint8)
        texture[:16, :16] = [255, 0, 0]
        texture[:16, 16:] = [0, 255, 0]
        texture[16:, :16] = [0, 0, 255]
        texture[16:, 16:] = [255, 255, 0]
        box = trimesh.creation.box()
        uv = box.vertices[:, :2]+.5
        material = trimesh.visual.material.PBRMaterial(baseColorTexture=Image.fromarray(texture),
                                                        baseColorFactor=[255, 255, 255, 255])
        box.visual = trimesh.visual.texture.TextureVisuals(uv=uv, material=material)
        with tempfile.TemporaryDirectory() as directory:
            path = self.write_scene(directory, [(box, np.eye(4))])
            reference = MeshReference(path)
            face = np.asarray(reference.render(0, side=64))
            np.testing.assert_array_equal(face[20, 20], [255, 0, 0, 255])
            np.testing.assert_array_equal(face[20, 44], [0, 255, 0, 255])
            np.testing.assert_array_equal(face[44, 20], [0, 0, 255, 255])
            back = np.asarray(reference.render(1, side=64))
            np.testing.assert_array_equal(back[20, 20], [0, 255, 0, 255])
            for view in range(4):
                alpha = np.asarray(reference.render(view, side=64))[..., 3]
                self.assertEqual(alpha[0, 0], 0)
                self.assertGreater((alpha > 0).sum(), 500)

    def test_cached_preparation_has_reliable_targets_and_four_images(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.write_scene(directory, [(trimesh.creation.box(), np.eye(4))])
            output = Path(directory)/"output"
            audit = prepare_reference(path, output, "box", side=32, resolution=28)
            self.assertEqual(audit, prepare_reference(path, output, "box", side=32, resolution=28))
            self.assertEqual(audit["sample_points"], 28**3)
            self.assertEqual(audit["reliable_points"], 28**3)
            self.assertGreater(audit["positive_reliable_points"], 1000)
            self.assertEqual(len(audit["view_files"]), 4)
            targets = np.load(output/"targets.npz")
            self.assertEqual(targets["labels"].shape, (28**3,))
            for name in audit["view_files"]:
                self.assertTrue((output/name).is_file())

    def test_split_guard_prevents_duplicate_file_leakage_and_unsafe_ids(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.write_scene(directory, [(trimesh.creation.box(), np.eye(4))])
            args = argparse.Namespace(train_reference=[["box", str(path)]],
                                      validation_reference=[["same-object", str(path)]], test_reference=[])
            with self.assertRaisesRegex(ValueError, "leak"):
                reference_specs(args)
            args.train_reference = [["../../unsafe", str(path)]]
            with self.assertRaisesRegex(ValueError, "identifiers"):
                reference_specs(args)

    def test_binary_length_and_external_resource_guards(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.write_scene(directory, [(trimesh.creation.box(), np.eye(4))])
            original = path.read_bytes()
            path.write_bytes(original+b"junk")
            with self.assertRaisesRegex(ValueError, "complete binary"):
                document(path)
            old_size = struct.unpack_from("<I", original, 12)[0]
            tree = json.loads(original[20:20+old_size])
            tree["buffers"][0]["uri"] = "https://example.invalid/private.bin"
            encoded = json.dumps(tree).encode()
            encoded += b" "*((-len(encoded)) % 4)
            tail = original[20+old_size:]
            rewritten = (struct.pack("<4sII", b"glTF", 2, 20+len(encoded)+len(tail))
                         +struct.pack("<II", len(encoded), 0x4E4F534A)+encoded+tail)
            path.write_bytes(rewritten)
            with self.assertRaisesRegex(ValueError, "no external access"):
                document(path)

    def test_precision_guard_rejects_additional_false_volume(self):
        before = {"objects": [{"id": "person", "precision": .8}]}
        after = {"objects": [{"id": "person", "precision": .75}]}
        self.assertEqual(precision_regressions(before, after), ["person"])
        after["objects"][0]["precision"] = .79
        self.assertEqual(precision_regressions(before, after), [])

    def test_higher_precision_cannot_hide_missing_geometry(self):
        before = {"objects": [{"id": "person", "iou": .34, "precision": .47, "recall": .54}]}
        after = {"objects": [{"id": "person", "iou": .32, "precision": .51, "recall": .47}]}
        result = geometry_regressions(before, after)
        self.assertEqual({r["metric"] for r in result}, {"iou", "recall"})
        after["objects"][0]["id"] = "different-person"
        with self.assertRaisesRegex(ValueError, "same object ordering"):
            geometry_regressions(before, after)


if __name__ == "__main__":
    unittest.main()
