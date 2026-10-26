#!/usr/bin/env python3
import os, zlib, struct, math

def make_png(width, height, pixel_fn):
    raw_data = bytearray()
    for y in range(height):
        raw_data.append(0)
        for x in range(width):
            r, g, b, a = pixel_fn(x, y, width, height)
            raw_data.extend((r, g, b, a))

    def chunk(tag, data):
        return struct.pack("!I", len(data)) + tag + data + struct.pack("!I", zlib.crc32(tag + data) & 0xffffffff)

    ihdr = struct.pack("!IIBBBBB", width, height, 8, 6, 0, 0, 0)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IDAT", zlib.compress(bytes(raw_data), 9)) + chunk(b"IEND", b"")

def apkcreator_logo_pixel(x, y, w, h):
    nx = (x / w) * 2.0 - 1.0
    ny = (y / h) * 2.0 - 1.0
    r_corner = 0.78
    dx = max(abs(nx) - r_corner, 0.0)
    dy = max(abs(ny) - r_corner, 0.0)
    if math.hypot(dx, dy) > 0.22:
        return (0, 0, 0, 0)

    bg = (255, 255, 255, 255)
    in_head = (ny < -0.05 and ny > -0.55 and math.hypot(nx, ny + 0.05) < 0.42)
    in_eye_l = math.hypot(nx + 0.17, ny + 0.24) < 0.055
    in_eye_r = math.hypot(nx - 0.17, ny + 0.24) < 0.055
    in_body = (abs(nx) < 0.40 and ny >= -0.01 and ny < 0.52)
    in_arms = (abs(nx) > 0.44 and abs(nx) < 0.58 and ny > 0.02 and ny < 0.44)
    in_legs = ((abs(nx + 0.18) < 0.09 or abs(nx - 0.18) < 0.09) and ny >= 0.52 and ny < 0.74)
    in_ant_l = (abs((nx + 0.24) - (ny + 0.55) * 0.45) < 0.035 and ny < -0.45 and ny > -0.72)
    in_ant_r = (abs((nx - 0.24) + (ny + 0.55) * 0.45) < 0.035 and ny < -0.45 and ny > -0.72)
    in_katana = (abs((nx * 0.85 + ny * 0.85) - 0.08) < 0.045 and abs(nx) < 0.52 and abs(ny) < 0.55)

    if in_eye_l or in_eye_r:
        return (255, 255, 255, 255)
    if in_katana:
        return (35, 115, 58, 255)
    if in_head or in_body or in_arms or in_legs or in_ant_l or in_ant_r:
        return (61, 212, 102, 255)
    return bg

os.makedirs("app/src/main/res/drawable-nodpi", exist_ok=True)
os.makedirs("template/src/main/res/drawable", exist_ok=True)

png_bytes = make_png(192, 192, apkcreator_logo_pixel)
with open("app/src/main/res/drawable-nodpi/ic_apkcreator_logo.png", "wb") as f:
    f.write(png_bytes)
with open("template/src/main/res/drawable/app_icon.png", "wb") as f:
    f.write(png_bytes)
print("Generated default PNG icons successfully.")
