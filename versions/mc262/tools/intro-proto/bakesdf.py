# Signed distance fields of "S1m" / "p1e" from the 8x masks -> one 16-bit-packed RGBA PNG.
# Texel (i, j) of half h sits at design (x0 + (i + .5) / TPX, y0 + (j + .5) / TPX); value = signed distance in design
# px (inside < 0), clamped to [-RANGE, RANGE), packed as v = (d + RANGE) / (2 RANGE) * 65535 -> R = v >> 8, G = v & 255.
import json, sys
import numpy as np
from PIL import Image
from scipy.ndimage import distance_transform_edt

SS, TPX, RANGE = 8, 2, 128.0
halves = []
for h in (0, 1):
    m = np.asarray(Image.open(f'mask{h}.png').convert('L')).astype(np.float32) / 255.0
    inside = m >= 0.5
    # distance from pixel centres to the boundary, in mask px -> design px
    d_out = distance_transform_edt(~inside)
    d_in = distance_transform_edt(inside)
    sdf = (np.where(inside, -(d_in - 0.5), d_out - 0.5)) / SS
    step = SS // TPX                                   # 4 mask px per texel
    sdf_t = sdf[step // 2::step, step // 2::step]      # sample at texel centres
    halves.append(sdf_t)
    print(h, sdf_t.shape, 'min', float(sdf_t.min()), 'max', float(sdf_t.max()))
field = np.concatenate(halves, axis=1)
v = np.clip((field + RANGE) / (2 * RANGE), 0, 1 - 1e-9) * 65535.0
v = np.round(v).astype(np.uint32)
rgba = np.zeros(field.shape + (4,), np.uint8)
rgba[..., 0] = (v >> 8) & 255
rgba[..., 1] = v & 255
rgba[..., 3] = 255
Image.fromarray(rgba, 'RGBA').save(sys.argv[1] if len(sys.argv) > 1 else 'intro_sdf.png', optimize=True)
json.dump({'w': field.shape[1], 'h': field.shape[0], 'tpx': TPX, 'range': RANGE}, open('sdf_meta.json', 'w'))
print('saved', field.shape)
