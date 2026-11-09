"""
A small QR code encoder: byte mode, error correction level M, versions 1-40.
Python standard library only, so the skill needs nothing installed.

Follows the structure of Project Nayuki's reference QR Code generator (MIT).

    modules = encode("homebaked://link?...")      # list of rows of bools, True = dark
    write_png(modules, "qr.png")
    write_svg(modules, "qr.svg")
    print(to_text(modules))
"""

import struct
import zlib

# Per version 1..40, index 0 unused. Error correction level M.
_ECC_PER_BLOCK = [-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26,
                  26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28]
_NUM_BLOCKS = [-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16,
               17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49]
_FORMAT_BITS_M = 0  # format-info code for level M


def _raw_data_modules(ver):
    result = (16 * ver + 128) * ver + 64
    if ver >= 2:
        n = ver // 7 + 2
        result -= (25 * n - 10) * n - 55
        if ver >= 7:
            result -= 36
    return result


def _data_codewords(ver):
    return _raw_data_modules(ver) // 8 - _ECC_PER_BLOCK[ver] * _NUM_BLOCKS[ver]


def _gf_mul(x, y):
    z = 0
    for i in reversed(range(8)):
        z = (z << 1) ^ ((z >> 7) * 0x11D)
        z ^= ((y >> i) & 1) * x
    return z


def _rs_divisor(degree):
    result = [0] * (degree - 1) + [1]
    root = 1
    for _ in range(degree):
        for j in range(degree):
            result[j] = _gf_mul(result[j], root)
            if j + 1 < degree:
                result[j] ^= result[j + 1]
        root = _gf_mul(root, 0x02)
    return result


def _rs_remainder(data, divisor):
    result = [0] * len(divisor)
    for b in data:
        factor = b ^ result.pop(0)
        result.append(0)
        for i, coef in enumerate(divisor):
            result[i] ^= _gf_mul(coef, factor)
    return result


def _alignment_positions(ver, size):
    if ver == 1:
        return []
    n = ver // 7 + 2
    step = (ver * 8 + n * 3 + 5) // (n * 4 - 4) * 2
    result = [size - 7 - i * step for i in range(n - 1)]
    result.append(6)
    return list(reversed(result))


class _Grid:
    def __init__(self, ver):
        self.ver = ver
        self.size = ver * 4 + 17
        self.dark = [[False] * self.size for _ in range(self.size)]
        self.fixed = [[False] * self.size for _ in range(self.size)]

    def set_fixed(self, x, y, dark):
        self.dark[y][x] = dark
        self.fixed[y][x] = True

    def draw_function_patterns(self):
        size = self.size
        for i in range(size):
            self.set_fixed(6, i, i % 2 == 0)
            self.set_fixed(i, 6, i % 2 == 0)
        for (x, y) in ((3, 3), (size - 4, 3), (3, size - 4)):
            for dy in range(-4, 5):
                for dx in range(-4, 5):
                    xx, yy = x + dx, y + dy
                    if 0 <= xx < size and 0 <= yy < size:
                        self.set_fixed(xx, yy, max(abs(dx), abs(dy)) not in (2, 4))
        pos = _alignment_positions(self.ver, size)
        last = len(pos) - 1
        for i, a in enumerate(pos):
            for j, b in enumerate(pos):
                if (i, j) in ((0, 0), (0, last), (last, 0)):
                    continue
                for dy in range(-2, 3):
                    for dx in range(-2, 3):
                        self.set_fixed(a + dx, b + dy, max(abs(dx), abs(dy)) != 1)
        self.draw_format(0)
        if self.ver >= 7:
            rem = self.ver
            for _ in range(12):
                rem = (rem << 1) ^ ((rem >> 11) * 0x1F25)
            bits = self.ver << 12 | rem
            for i in range(18):
                bit = (bits >> i) & 1 != 0
                a, b = size - 11 + i % 3, i // 3
                self.set_fixed(a, b, bit)
                self.set_fixed(b, a, bit)

    def draw_format(self, mask):
        data = _FORMAT_BITS_M << 3 | mask
        rem = data
        for _ in range(10):
            rem = (rem << 1) ^ ((rem >> 9) * 0x537)
        bits = (data << 10 | rem) ^ 0x5412
        bit = lambda i: (bits >> i) & 1 != 0
        size = self.size
        for i in range(6):
            self.set_fixed(8, i, bit(i))
        self.set_fixed(8, 7, bit(6))
        self.set_fixed(8, 8, bit(7))
        self.set_fixed(7, 8, bit(8))
        for i in range(9, 15):
            self.set_fixed(14 - i, 8, bit(i))
        for i in range(8):
            self.set_fixed(size - 1 - i, 8, bit(i))
        for i in range(8, 15):
            self.set_fixed(8, size - 15 + i, bit(i))
        self.set_fixed(8, size - 8, True)

    def draw_codewords(self, data):
        size = self.size
        i = 0
        right = size - 1
        while right >= 1:
            if right == 6:
                right = 5
            for vert in range(size):
                for j in range(2):
                    x = right - j
                    upward = ((right + 1) & 2) == 0
                    y = size - 1 - vert if upward else vert
                    if not self.fixed[y][x] and i < len(data) * 8:
                        self.dark[y][x] = (data[i >> 3] >> (7 - (i & 7))) & 1 != 0
                        i += 1
            right -= 2

    def apply_mask(self, mask):
        tests = (
            lambda x, y: (x + y) % 2 == 0,
            lambda x, y: y % 2 == 0,
            lambda x, y: x % 3 == 0,
            lambda x, y: (x + y) % 3 == 0,
            lambda x, y: (x // 3 + y // 2) % 2 == 0,
            lambda x, y: x * y % 2 + x * y % 3 == 0,
            lambda x, y: (x * y % 2 + x * y % 3) % 2 == 0,
            lambda x, y: ((x + y) % 2 + x * y % 3) % 2 == 0,
        )
        t = tests[mask]
        for y in range(self.size):
            for x in range(self.size):
                if not self.fixed[y][x] and t(x, y):
                    self.dark[y][x] = not self.dark[y][x]

    def penalty(self):
        """Runs, 2x2 blocks and dark/light balance. Enough to pick a readable mask."""
        size, m = self.size, self.dark
        score = 0
        for line in list(m) + [list(col) for col in zip(*m)]:
            run, prev = 0, None
            for v in line:
                if v == prev:
                    run += 1
                else:
                    if run >= 5:
                        score += run - 2
                    run, prev = 1, v
            if run >= 5:
                score += run - 2
        for y in range(size - 1):
            for x in range(size - 1):
                if m[y][x] == m[y][x + 1] == m[y + 1][x] == m[y + 1][x + 1]:
                    score += 3
        dark = sum(sum(r) for r in m)
        total = size * size
        score += (abs(dark * 20 - total * 10) + total - 1) // total * 10
        return score


def encode(text):
    data = text.encode("utf-8")
    for ver in range(1, 41):
        count_bits = 8 if ver <= 9 else 16
        if 4 + count_bits + len(data) * 8 <= _data_codewords(ver) * 8:
            break
    else:
        raise ValueError("text too long for a QR code")

    # Bit stream: byte mode, length, data, terminator, padding.
    bits = []
    def put(value, n):
        bits.extend((value >> i) & 1 for i in reversed(range(n)))
    put(0b0100, 4)
    put(len(data), count_bits)
    for b in data:
        put(b, 8)
    capacity = _data_codewords(ver) * 8
    put(0, min(4, capacity - len(bits)))
    put(0, (-len(bits)) % 8)
    pad = 0xEC
    while len(bits) < capacity:
        put(pad, 8)
        pad ^= 0xEC ^ 0x11
    codewords = [int("".join(map(str, bits[i:i + 8])), 2) for i in range(0, len(bits), 8)]

    # Split into blocks, add Reed-Solomon error correction, interleave.
    num_blocks = _NUM_BLOCKS[ver]
    ecc_len = _ECC_PER_BLOCK[ver]
    raw = _raw_data_modules(ver) // 8
    num_short = num_blocks - raw % num_blocks
    short_len = raw // num_blocks
    divisor = _rs_divisor(ecc_len)
    blocks, k = [], 0
    for i in range(num_blocks):
        n = short_len - ecc_len + (0 if i < num_short else 1)
        dat = codewords[k:k + n]
        k += n
        ecc = _rs_remainder(dat, divisor)
        if i < num_short:
            dat.append(0)
        blocks.append(dat + ecc)
    final = []
    for i in range(len(blocks[0])):
        for j, blk in enumerate(blocks):
            if i != short_len - ecc_len or j >= num_short:
                final.append(blk[i])

    # Draw, then keep whichever mask scores best.
    best, best_score = None, None
    for mask in range(8):
        g = _Grid(ver)
        g.draw_function_patterns()
        g.draw_codewords(final)
        g.apply_mask(mask)
        g.draw_format(mask)
        s = g.penalty()
        if best_score is None or s < best_score:
            best, best_score = g, s
    return best.dark


def write_png(modules, path, scale=10, border=4):
    n = len(modules) + border * 2
    px = n * scale
    rows = []
    for y in range(px):
        my = y // scale - border
        row = bytearray([0])  # filter: none
        for x in range(px):
            mx = x // scale - border
            dark = 0 <= my < len(modules) and 0 <= mx < len(modules) and modules[my][mx]
            row.append(0 if dark else 255)
        rows.append(bytes(row))
    def chunk(kind, body):
        return struct.pack(">I", len(body)) + kind + body + struct.pack(">I", zlib.crc32(kind + body) & 0xFFFFFFFF)
    png = (b"\x89PNG\r\n\x1a\n"
           + chunk(b"IHDR", struct.pack(">IIBBBBB", px, px, 8, 0, 0, 0, 0))
           + chunk(b"IDAT", zlib.compress(b"".join(rows), 9))
           + chunk(b"IEND", b""))
    with open(path, "wb") as f:
        f.write(png)


def write_svg(modules, path, border=4):
    n = len(modules) + border * 2
    parts = [
        '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 %d %d" width="400" height="400" '
        'shape-rendering="crispEdges"><rect width="100%%" height="100%%" fill="#fff"/><path fill="#000" d="' % (n, n)
    ]
    for y, row in enumerate(modules):
        for x, dark in enumerate(row):
            if dark:
                parts.append("M%d %dh1v1h-1z" % (x + border, y + border))
    parts.append('"/></svg>')
    with open(path, "w") as f:
        f.write("".join(parts))


def to_text(modules, border=2):
    """Two modules per character row, using half blocks. Light-on-dark terminals read it fine."""
    n = len(modules)
    get = lambda x, y: 0 <= x < n and 0 <= y < n and modules[y][x]
    lines = []
    for y in range(-border, n + border, 2):
        line = []
        for x in range(-border, n + border):
            top, bottom = get(x, y), get(x, y + 1)
            # Inverted: light modules drawn as blocks, so it scans on a dark background.
            line.append({(False, False): "█", (True, False): "▄",
                         (False, True): "▀", (True, True): " "}[(top, bottom)])
        lines.append("".join(line))
    return "\n".join(lines)
