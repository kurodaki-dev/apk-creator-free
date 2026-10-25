#!/usr/bin/env python3
import os, zlib, struct, math

def make_png(width, height, pixel_fn):
    raw_data = bytearray()
    for y in range(height):
        raw_data.append(0)  # Filter type 0
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
    # Fond carré arrondi blanc
    r_corner = 0.78
    dx = max(abs(nx) - r_corner, 0.0)
    dy = max(abs(ny) - r_corner, 0.0)
    if math.hypot(dx, dy) > 0.22:
        return (0, 0, 0, 0)

    # Cercle d'arrière-plan vert clair subtil
    dist_center = math.hypot(nx, ny)
    bg = (255, 255, 255, 255)

    # Tête et corps du robot Android vert (#3DDC84 / #2DBE60)
    # Tête demi-cercle haut
    in_head = (ny < -0.05 and ny > -0.55 and math.hypot(nx, ny + 0.05) < 0.42)
    # Yeux blancs
    in_eye_l = math.hypot(nx + 0.17, ny + 0.24) < 0.055
    in_eye_r = math.hypot(nx - 0.17, ny + 0.24) < 0.055
    # Corps
    in_body = (abs(nx) < 0.40 and ny >= -0.01 and ny < 0.52)
    # Bras gauche & droit
    in_arms = (abs(nx) > 0.44 and abs(nx) < 0.58 and ny > 0.02 and ny < 0.44)
    # Jambes
    in_legs = ((abs(nx + 0.18) < 0.09 or abs(nx - 0.18) < 0.09) and ny >= 0.52 and ny < 0.74)
    # Antennes
    in_ant_l = (abs((nx + 0.24) - (ny + 0.55) * 0.45) < 0.035 and ny < -0.45 and ny > -0.72)
    in_ant_r = (abs((nx - 0.24) + (ny + 0.55) * 0.45) < 0.035 and ny < -0.45 and ny > -0.72)
    # Bandeau/Sabre diagonal (style ninja ApkCreator)
    in_katana = (abs((nx * 0.85 + ny * 0.85) - 0.08) < 0.045 and abs(nx) < 0.52 and abs(ny) < 0.55)

    if in_eye_l or in_eye_r:
        return (255, 255, 255, 255)
    if in_katana:
        return (35, 115, 58, 255)
    if in_head or in_body or in_arms or in_legs or in_ant_l or in_ant_r:
        return (61, 212, 102, 255)
    return bg

os.makedirs("app/src/main/res/drawable-nodpi", exist_ok=True)
os.makedirs("template/src/main/res/drawable-nodpi", exist_ok=True)

png_bytes = make_png(192, 192, apkcreator_logo_pixel)
with open("app/src/main/res/drawable-nodpi/ic_apkcreator_logo.png", "wb") as f:
    f.write(png_bytes)
with open("template/src/main/res/drawable-nodpi/app_icon.png", "wb") as f:
    f.write(png_bytes)
print("Generated PNG icons successfully.")
