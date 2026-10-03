"""Private, static GLB references: transformed geometry, CPU renders, known solids.

Inputs are never edited. No hole filling, remeshing, network loading or generated
app output is silently treated as scanned ground truth. Open component regions
are masked from volumetric supervision. Reference data belongs in ignored build/.
"""
from io import BytesIO
import json
from pathlib import Path
import struct

import numpy as np
from PIL import Image
import trimesh

from references import to_world
from train_decoder import digest

VERSION = "static-glb-reference-v1"
VIEW_NAMES = ("face", "back", "right", "left")


def document(path):
    path = Path(path)
    if path.stat().st_size > 256 * 1024**2:
        raise ValueError("Reference exceeds the 256 MiB input bound")
    data = path.read_bytes()
    if len(data) < 20:
        raise ValueError("Truncated GLB")
    magic, version, length = struct.unpack_from("<4sII", data)
    if (magic, version, length) != (b"glTF", 2, len(data)):
        raise ValueError("Expected a complete binary glTF 2.0, regardless of suffix")
    size, kind = struct.unpack_from("<II", data, 12)
    if kind != 0x4E4F534A or 20+size > len(data):
        raise ValueError("Missing GLB JSON chunk")
    tree = json.loads(data[20:20+size])
    if tree.get("skins") or tree.get("animations"):
        raise ValueError("Bake animated or skinned references before training")
    if tree.get("extensionsRequired"):
        raise ValueError("Required GLB extensions are not supported by this importer")
    if any("uri" in item for field in ("buffers", "images") for item in tree.get(field, [])):
        raise ValueError("References must embed their buffers and images; no external access")
    if any(p.get("mode", 4) != 4 for m in tree.get("meshes", []) for p in m["primitives"]):
        raise ValueError("References must use triangles")
    if any(m.get("alphaMode", "OPAQUE") != "OPAQUE" for m in tree.get("materials", [])):
        raise ValueError("Transparent materials need a separate surface supervision policy")
    if any(p.get("targets") for m in tree.get("meshes", []) for p in m["primitives"]):
        raise ValueError("Bake morph targets before training")
    return data, tree


class MeshReference:
    def __init__(self, path):
        data, tree = document(path)
        scene = trimesh.load_scene(BytesIO(data), file_type="glb", process=False)
        self.parts = []
        for node in sorted(scene.graph.nodes_geometry):
            transform, name = scene.graph[node]
            mesh = scene.geometry[name].copy()
            mesh.apply_transform(transform)
            if not np.isfinite(mesh.vertices).all() or not len(mesh.faces):
                raise ValueError("Empty or non-finite transformed reference")
            if (mesh.faces < 0).any() or (mesh.faces >= len(mesh.vertices)).any():
                raise ValueError("Invalid triangle indices")
            self.parts.append(mesh)
        if not self.parts or sum(len(m.faces) for m in self.parts) > 2_500_000:
            raise ValueError("Expected 1 to 2.5 million static triangles")
        original_bounds = np.array([m.bounds for m in self.parts])
        low, high = original_bounds[:, 0].min(0), original_bounds[:, 1].max(0)
        self.center = (low+high)/2
        self.scale = float(np.max(high-low)/2)
        if self.scale <= 1e-9 or ((high-low) < self.scale*1e-5).any():
            raise ValueError("Reference is empty or effectively planar")
        for mesh in self.parts:
            mesh.vertices = (mesh.vertices-self.center)/self.scale
        # Separate render geometry retains material boundaries and UV seams.
        bare = [trimesh.Trimesh(m.vertices, m.faces, process=False) for m in self.parts]
        self.render_mesh = trimesh.util.concatenate(bare)
        self.face_ends = np.cumsum([len(m.faces) for m in self.parts])
        self.bounds = self.render_mesh.bounds.copy()
        geometry = self.render_mesh.copy()
        # Only weld positions and discard duplicate/degenerate triangles.
        # Unlike process(validate=True), do not silently repair winding here.
        geometry.merge_vertices()
        geometry.update_faces(geometry.unique_faces() & geometry.nondegenerate_faces())
        geometry.remove_unreferenced_vertices()
        components = geometry.split(only_watertight=False, repair=False)
        self.solids, self.uncertain = [], []
        summaries = []
        for component in components:
            closed = bool(component.is_watertight and component.is_winding_consistent
                          and abs(component.volume) > 1e-12)
            summaries.append({"triangles": len(component.faces), "watertight": bool(component.is_watertight),
                              "winding_consistent": bool(component.is_winding_consistent),
                              "accepted_for_occupancy": closed})
            if closed:
                self.solids.append(component)
            else:
                self.uncertain.append(component.bounds.copy())
        if not self.solids:
            raise ValueError("No closed, consistently wound solids; volume training refused")
        images = [getattr(getattr(m.visual, "material", None), "baseColorTexture", None)
                  for m in self.parts]
        self.audit = {"reference_version": VERSION, "source_name": Path(path).name,
                      "source_sha256": digest(path), "source_bytes": len(data),
                      "asset": tree.get("asset"), "extensions_used": tree.get("extensionsUsed", []),
                      "geometry_instances": len(self.parts),
                      "triangles": len(self.render_mesh.faces), "welded_triangles": len(geometry.faces),
                      "welded_vertices": len(geometry.vertices), "components": summaries,
                      "closed_components": len(self.solids), "uncertain_components": len(self.uncertain),
                      "original_bounds": [low.tolist(), high.tolist()],
                      "normalization": {"center": self.center.tolist(), "uniform_half_extent": self.scale,
                                        "axes": "glTF world: Y up, front camera +Z, right camera +X"},
                      "texture_sizes": [list(im.size) for im in images if im is not None],
                      "holes_filled": 0, "license_status": "not established by the supplied file",
                      "reference_kind": "user-supplied authored or generated mesh, not a verified scan",
                      "rendering": "orthographic CPU rays, base color and diffuse shading; not full PBR"}

    def labels(self, points, margin):
        """Union of closed components; exclude any open component's expanded AABB.

        Overlapping closed parts use union, not parity of the concatenated mesh.
        The mask is deliberately conservative, including uncertain empty space.
        """
        points = np.asarray(points)
        truth = np.zeros(len(points), bool)
        reliable = np.ones(len(points), bool)
        for mesh in self.solids:
            eligible = (~truth & (points > mesh.bounds[0]).all(1)
                        & (points < mesh.bounds[1]).all(1))
            indices = np.flatnonzero(eligible)
            if len(indices):
                truth[indices] |= mesh.contains(points[indices])
        for low, high in self.uncertain:
            reliable &= ~((points >= low-margin).all(1) & (points <= high+margin).all(1))
        return truth.astype(np.float32), reliable

    def render(self, view, side=256):
        if view not in range(4) or side < 8 or side > 1024:
            raise ValueError("Expected one of four views and a bounded image size")
        axis = np.linspace(-1.12, 1.12, side)
        horizontal, vertical = np.meshgrid(axis, axis[::-1])
        local = np.stack((np.full(horizontal.size, 2.), horizontal.ravel(), vertical.ravel()), -1)
        origins = to_world(local, view)
        directions = np.repeat(to_world(np.array([[-1., 0, 0]]), view), len(origins), axis=0)
        points, rays, faces = self.render_mesh.ray.intersects_location(origins, directions, multiple_hits=False)
        pixels = np.zeros((side*side, 4), np.uint8)
        part_ids = np.searchsorted(self.face_ends, faces, side="right")
        for index, mesh in enumerate(self.parts):
            use = part_ids == index
            if not use.any():
                continue
            triangles = faces[use] - (self.face_ends[index-1] if index else 0)
            barycentric = trimesh.triangles.points_to_barycentric(mesh.triangles[triangles], points[use])
            material = getattr(mesh.visual, "material", None)
            factor = getattr(material, "baseColorFactor", None)
            factor = np.array([255, 255, 255, 255] if factor is None else factor)/255
            color = np.broadcast_to(factor[:3], (len(triangles), 3)).copy()
            texture = getattr(material, "baseColorTexture", None)
            if texture is not None:
                uv = (mesh.visual.uv[mesh.faces[triangles]]*barycentric[..., None]).sum(1)
                tex = np.asarray(texture.convert("RGB"))
                # glTF UV origin is top-left. trimesh's loader flips V for its
                # internal convention, hence flip back when sampling the image.
                x = np.minimum(tex.shape[1]-1, np.floor((uv[:, 0] % 1)*tex.shape[1]).astype(int))
                y = np.minimum(tex.shape[0]-1, np.floor(((1-uv[:, 1]) % 1)*tex.shape[0]).astype(int))
                sample = tex[y, x]/255
                linear = np.where(sample <= .04045, sample/12.92, ((sample+.055)/1.055)**2.4)
                color *= linear
            if mesh.visual.kind == "vertex":
                sample = (mesh.visual.vertex_colors[mesh.faces[triangles], :3]*barycentric[..., None]).sum(1)/255
                color *= np.where(sample <= .04045, sample/12.92, ((sample+.055)/1.055)**2.4)
            normals = mesh.face_normals[triangles]
            toward_camera = -directions[rays[use]]
            illumination = .65 + .35*np.abs((normals*toward_camera).sum(1))
            color = np.clip(color*illumination[:, None], 0, 1)
            srgb = np.where(color <= .0031308, color*12.92, 1.055*color**(1/2.4)-.055)
            pixels[rays[use], :3] = np.rint(srgb*255).astype(np.uint8)
            pixels[rays[use], 3] = 255
        image = Image.fromarray(pixels.reshape(side, side, 4))
        if image.getbbox() is None:
            raise ValueError("Empty reference render")
        return image


def prepare_reference(path, folder, identifier, side=256, resolution=28):
    folder = Path(folder)
    folder.mkdir(parents=True, exist_ok=True)
    key = json.dumps({"version": VERSION, "sha256": digest(path), "side": side,
                      "resolution": resolution}, sort_keys=True)
    marker = folder/"reference.key"
    expected = [folder/"audit.json", folder/"targets.npz"] + [folder/(v+".png") for v in VIEW_NAMES]
    if marker.is_file() and marker.read_text() == key and all(p.is_file() for p in expected):
        return json.loads((folder/"audit.json").read_text())
    print("IMPORT", identifier, flush=True)
    reference = MeshReference(path)
    for view, name in enumerate(VIEW_NAMES):
        reference.render(view, side).save(folder/(name+".png"))
        print("RENDER", identifier, name, flush=True)
    axis = np.linspace(-1, 1, resolution, dtype=np.float32)
    grid = np.stack(np.meshgrid(axis, axis, axis, indexing="ij"), -1).reshape(-1, 3)
    center = reference.bounds.mean(0)
    half = np.diff(reference.bounds, axis=0)[0]/2
    points = center + grid*half*1.15
    labels, reliable = reference.labels(points, margin=half*2.3/(resolution-1))
    if labels[reliable].sum() < 32 or reliable.mean() < .5:
        raise ValueError("Too little reliable occupancy supervision")
    np.savez_compressed(folder/"targets.npz", grid=grid, labels=labels, reliable=reliable)
    audit = {**reference.audit, "id": identifier, "grid_resolution": resolution,
             "sample_points": len(grid), "reliable_points": int(reliable.sum()),
             "masked_uncertain_points": int((~reliable).sum()),
             "positive_reliable_points": int(labels[reliable].sum()),
             "view_files": [v+".png" for v in VIEW_NAMES]}
    (folder/"audit.json").write_text(json.dumps(audit, indent=2))
    marker.write_text(key)
    return audit
