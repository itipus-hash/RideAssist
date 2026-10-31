"""生成 RideAssist 骑行助手应用图标 PNG（纯标准库实现，无需 Pillow）。

图标：蓝底 + 白色闪电（电池/能量意象）。
输出 mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png 与 ic_launcher_round.png
"""
import math
import os
import struct
import zlib

BG = (0x15, 0x65, 0xC0)
FG = (0xFF, 0xFF, 0xFF)

# 闪电折线轮廓（归一化坐标 0~1），闭合多边形
BOLT = [
    (0.56, 0.16), (0.30, 0.55), (0.47, 0.55),
    (0.42, 0.86), (0.70, 0.45), (0.52, 0.45),
]


def point_in_poly(x, y, poly):
    inside = False
    n = len(poly)
    for i in range(n):
        x1, y1 = poly[i]
        x2, y2 = poly[(i + 1) % n]
        if (y1 > y) != (y2 > y):
            xin = (x2 - x1) * (y - y1) / (y2 - y1) + x1
            if x < xin:
                inside = not inside
    return inside


def make_rgba(size, round_icon):
    # 采样点略向内缩，避免圆角边缘锯齿过粗
    px = bytearray()
    r_out = size / 2.0 * 0.94
    cx = cy = size / 2.0
    for row in range(size):
        px.append(0)  # PNG filter type 0
        for col in range(size):
            x = col + 0.5
            y = row + 0.5
            if round_icon and math.hypot(x - cx, y - cy) > r_out:
                px.extend((0, 0, 0, 0))
                continue
            nx, ny = x / size, y / size
            color = FG if point_in_poly(nx, ny, BOLT) else BG
            px.extend((color[0], color[1], color[2], 255))
    return bytes(px)


def write_png(path, size, round_icon):
    raw = make_rgba(size, round_icon)
    def chunk(tag, data):
        c = struct.pack(">I", len(data)) + tag + data
        return c + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    ihdr = struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0)
    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", ihdr)
    png += chunk(b"IDAT", zlib.compress(raw, 9))
    png += chunk(b"IEND", b"")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(png)
    print("生成", path, size)


ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "app", "src", "main", "res")
DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}

for density, size in DENSITIES.items():
    folder = os.path.join(ROOT, f"mipmap-{density}")
    write_png(os.path.join(folder, "ic_launcher.png"), size, False)
    write_png(os.path.join(folder, "ic_launcher_round.png"), size, True)

print("全部完成")