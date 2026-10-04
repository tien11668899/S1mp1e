# 逐張比對兩組 DevShot 截圖：平均絕對差、差異像素比例（>24/255）
import sys, os
from PIL import Image, ImageChops
a, b = sys.argv[1], sys.argv[2]
rows = []
for n in sorted(os.listdir(a)):
    pa, pb = os.path.join(a, n), os.path.join(b, n)
    if not n.endswith('.png') or not os.path.exists(pb): rows.append((n, None, None)); continue
    ia, ib = Image.open(pa).convert('RGB'), Image.open(pb).convert('RGB')
    if ia.size != ib.size: rows.append((n, 'size', None)); continue
    d = ImageChops.difference(ia, ib).convert('L')
    h = d.histogram(); tot = sum(h)
    mean = sum(i * c for i, c in enumerate(h)) / tot
    big = sum(h[25:]) / tot
    rows.append((n, mean, big))
for n, m, g in sorted(rows, key=lambda r: -(r[2] or 0)):
    print(f'{n:40s} ' + ('missing' if m is None else ('size!' if m == 'size' else f'mean={m:6.2f} diff>24={g*100:6.2f}%')))
