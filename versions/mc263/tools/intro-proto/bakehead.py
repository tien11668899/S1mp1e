# Signed distance field of the boot headline from headmask.png (SS=4 mask px per design px) -> 2 texels per design px,
# distance in design px clamped to [-128, 128), packed 16-bit: R = high byte, G = low byte.
import json, sys
import numpy as np
from PIL import Image
from scipy.ndimage import distance_transform_edt

SS, TPX, RANGE = 4, 2, 128.0
m = np.asarray(Image.open('headmask.png').convert('L')).astype(np.float32) / 255.0
inside = m >= 0.5
sdf = np.where(inside, -(distance_transform_edt(inside) - 0.5), distance_transform_edt(~inside) - 0.5) / SS
step = SS // TPX
field = sdf[step // 2::step, step // 2::step]
v = np.round(np.clip((field + RANGE) / (2 * RANGE), 0, 1 - 1e-9) * 65535.0).astype(np.uint32)
rgba = np.zeros(field.shape + (4,), np.uint8)
rgba[..., 0] = (v >> 8) & 255
rgba[..., 1] = v & 255
rgba[..., 3] = 255
Image.fromarray(rgba, 'RGBA').save(sys.argv[1], optimize=True)
print('field', field.shape, 'min', float(field.min()), 'max', float(field.max()))
