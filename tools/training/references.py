"""Authored analytic reference geometry, known occupancy, four opaque/alpha renders.

Synthetic fixtures under MIT. They are NOT scans, not user photos, and cannot
establish photorealistic human reconstruction quality.
"""
import numpy as np
from PIL import Image


def parts(family, seed):
    rng = np.random.default_rng(seed)
    w, d = float(rng.uniform(.28, .43)), float(rng.uniform(.20, .32))
    if family == "bottle":
        return [("ellipsoid", (0, -.20, 0), (w, .62, d)),
                ("box", (0, .37, 0), (w*.6, .12, d*.65)),
                ("box", (0, .65, 0), (w*.25, .26, d*.35)),
                ("ellipsoid", (0, .88, 0), (w*.30, .06, d*.40))]
    if family == "chair":
        result = [("box", (0, -.05, 0), (w*1.35, .07, d*1.30)),
                  ("box", (0, .40, -d*1.13), (w*1.30, .49, .055))]
        for x in (-w*1.12, w*1.12):
            for z in (-d*1.12, d*1.12):
                result.append(("box", (x, -.48, z), (.045, .38, .045)))
        return result
    if family == "figure":
        arm = float(rng.uniform(.33, .50))
        return [("ellipsoid", (0, .19, 0), (w*.88, .39, d*.68)),
                ("ellipsoid", (0, .73, 0), (.145, .19, .145)),
                ("ellipsoid", (-arm/2, .36, 0), (arm*.60, .12, .10)),
                ("ellipsoid", (arm/2, .36, .01), (arm*.60, .12, .10)),
                ("ellipsoid", (-arm, .02, 0), (.08, .33, .095)),
                ("ellipsoid", (arm, .02, .015), (.08, .33, .095)),
                ("ellipsoid", (-.14, -.49, 0), (.105, .41, .12)),
                ("ellipsoid", (.14, -.49, 0), (.105, .41, .12)),
                ("box", (-.14, -.87, .045), (.11, .045, .17)),
                ("box", (.14, -.87, .045), (.11, .045, .17))]
    raise ValueError("Unknown reference family")


def bounds(geometry):
    centers = np.array([p[1] for p in geometry], np.float32)
    sizes = np.array([p[2] for p in geometry], np.float32)
    return (centers-sizes).min(0), (centers+sizes).max(0)


def occupancy(points, geometry):
    occupied = np.zeros(points.shape[:-1], bool)
    for kind, center, radius in geometry:
        q = (points-np.array(center, np.float32))/np.array(radius, np.float32)
        occupied |= (q*q).sum(-1) <= 1 if kind == "ellipsoid" else np.abs(q).max(-1) <= 1
    return occupied


def render(geometry, view, side=128):
    u, y, depth = np.meshgrid(np.linspace(-1, 1, side, dtype=np.float32),
                             np.linspace(1, -1, side, dtype=np.float32),
                             np.linspace(1, -1, side, dtype=np.float32), indexing="xy")
    if view == 0: coordinates = np.stack((u, y, depth), -1)
    elif view == 1: coordinates = np.stack((-u, y, -depth), -1)
    elif view == 2: coordinates = np.stack((depth, y, -u), -1)
    else: coordinates = np.stack((-depth, y, u), -1)
    hit = occupancy(coordinates, geometry)
    visible = hit.any(-1)
    index = hit.argmax(-1)
    points = np.take_along_axis(coordinates, index[..., None, None], axis=2).squeeze(2)
    # Shared position-based colours across views, with gentle lighting variation.
    colour = np.clip(np.stack((.32+.38*(points[..., 1]+1)/2,
                              .26+.32*(points[..., 0]+1)/2,
                              .35+.30*(points[..., 2]+1)/2), -1), 0, 1)
    pixels = np.zeros((side, side, 4), np.uint8)
    pixels[..., :3] = np.rint(colour*255).astype(np.uint8)
    pixels[..., 3] = visible.astype(np.uint8)*255
    image = Image.fromarray(pixels)
    bbox = image.getbbox()
    if bbox is None: raise ValueError("Empty synthetic reference")
    return image.crop(bbox)


def encoder_input(image):
    scale = 512*.9/max(image.size)
    resized = image.resize((max(1, round(image.width*scale)), max(1, round(image.height*scale))), Image.Resampling.BILINEAR)
    canvas = Image.new("RGBA", (512, 512), (128, 128, 128, 255))
    canvas.alpha_composite(resized, ((512-resized.width)//2, (512-resized.height)//2))
    return np.asarray(canvas.convert("RGB"), np.float32).transpose(2, 0, 1)[None]/255


def to_world(local, view):
    x, y, z = local.T
    if view == 0: return np.stack((y, z, x), -1)
    if view == 1: return np.stack((-y, z, -x), -1)
    if view == 2: return np.stack((x, z, -y), -1)
    return np.stack((-x, z, y), -1)


def to_local(world, view):
    x, y, z = world.T
    if view == 0: return np.stack((z, x, y), -1)
    if view == 1: return np.stack((-z, -x, y), -1)
    if view == 2: return np.stack((x, -z, y), -1)
    return np.stack((-x, z, y), -1)
