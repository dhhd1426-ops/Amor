"""把一个 .mpkg / scene.pkg，或官方安装包里的 assets 目录，转成网页渲染器 we.js 能读的形式：
pack.json（文本文件 + 贴图目录）+ 每张贴图一个文件（png，或 ETC2 原始块 .etc2）。
贴图格式编号照官方 common_fragment.h：0 RGBA8888 1 RGB888 2 RGB565 3 ETC1 4 DXT5 5 ETC2_RGBA8 6 DXT3 7 DXT1 8 RG88 9 R8。
用法：python3 -I mkpack.py <mpkg 或 assets 目录> <输出目录>
"""
import io, json, os, struct, sys
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from tex import lz4

TEXT_EXT = ('.json', '.frag', '.vert', '.geom', '.h', '.js', '.tex-json', '.txt')


def unpack(path):
    d = open(path, 'rb').read()
    n = struct.unpack_from('<I', d, 0)[0]
    ver = d[4:4 + n].decode()
    if not ver.startswith(('PKGM', 'PKGV')):
        raise ValueError('不是壁纸包')
    o = 4 + n
    cnt = struct.unpack_from('<I', d, o)[0]; o += 4
    ents = []
    for _ in range(cnt):
        L = struct.unpack_from('<I', d, o)[0]; o += 4
        nm = d[o:o + L].decode('utf8'); o += L
        off, ln = struct.unpack_from('<II', d, o); o += 8
        ents.append((nm, off, ln))
    return ver, {nm: d[o + off:o + off + ln] for nm, off, ln in ents}


def walk(root):
    out = {}
    for dp, _, fs in os.walk(root):
        for f in fs:
            p = os.path.join(dp, f)
            out[os.path.relpath(p, root).replace(os.sep, '/')] = open(p, 'rb').read()
    return out


def dds(fourcc, w, h, data):
    hdr = struct.pack('<4sIIIIIII44xIII4sIIIIIIIII', b'DDS ', 124, 0x1007, h, w, 0, 0, 1,
                      32, 4, fourcc, 0, 0, 0, 0, 0, 0x1000, 0, 0, 0)
    return Image.open(io.BytesIO(hdr + data)).convert('RGBA')


def decode(b):
    """→ (kind, w, h, img_w, img_h, payload)；kind: png / etc2"""
    if b[:4] != b'TEXV' or b[9:13] != b'TEXI':
        raise ValueError('不认识的贴图')
    fmt, flags, tw, th, iw, ih = struct.unpack_from('<6i', b, 18)
    tb = b[46:54].decode()
    ver = int(tb[4:])
    o = 55
    count = struct.unpack_from('<i', b, o)[0]; o += 4
    embedded = -1
    if ver >= 3:
        embedded = struct.unpack_from('<i', b, o)[0]; o += 4
    if ver >= 4:
        o += 4
    o += 4  # mip 数
    w, h = struct.unpack_from('<2i', b, o); o += 8
    lz = full = 0
    if ver >= 2:
        lz, full = struct.unpack_from('<2i', b, o); o += 8
    size = struct.unpack_from('<i', b, o)[0]; o += 4
    raw = b[o:o + size]
    if lz:
        raw = lz4(raw, full)
    iw = iw if 0 < iw <= w else w
    ih = ih if 0 < ih <= h else h
    if embedded >= 0:
        im = Image.open(io.BytesIO(raw)).convert('RGBA')
        return 'png', im.width, im.height, im.width, im.height, im
    if fmt == 5:
        return 'etc2', w, h, iw, ih, raw[:((w + 3) // 4) * ((h + 3) // 4) * 16]
    if fmt == 0:
        im = Image.frombytes('RGBA', (w, h), raw[:w * h * 4])
    elif fmt == 1:
        im = Image.frombytes('RGB', (w, h), raw[:w * h * 3]).convert('RGBA')
    elif fmt == 2:
        im = Image.frombytes('RGB', (w, h), raw[:w * h * 2], 'raw', 'BGR;16').convert('RGBA')
    elif fmt == 8:
        la = Image.frombytes('LA', (w, h), raw[:w * h * 2]); r, g = la.split()
        im = Image.merge('RGBA', (r, g, Image.new('L', (w, h), 0), Image.new('L', (w, h), 255)))
    elif fmt == 9:
        l = Image.frombytes('L', (w, h), raw[:w * h]); im = Image.merge('RGBA', (l, l, l, Image.new('L', (w, h), 255)))
    elif fmt in (4, 6, 7):
        im = dds({4: b'DXT5', 6: b'DXT3', 7: b'DXT1'}[fmt], w, h, raw)
    else:
        raise ValueError(f'还不支持的贴图格式 {fmt}')
    im = im.crop((0, 0, iw, ih))
    return 'png', iw, ih, iw, ih, im


def main(src, out):
    os.makedirs(out, exist_ok=True)
    if os.path.isdir(src):
        files = walk(src)
        ver = 'assets'
    else:
        ver, files = unpack(src)
    text, tex, bad = {}, {}, {}
    n = 0
    for name, b in sorted(files.items()):
        if name.endswith('.tex'):
            key = name[len('materials/'):-4] if name.startswith('materials/') else name[:-4]
            try:
                kind, w, h, iw, ih, payload = decode(b)
            except Exception as e:  # noqa: BLE001
                bad[key] = str(e)
                continue
            n += 1
            if kind == 'etc2':
                fn = f't{n}.etc2'; open(os.path.join(out, fn), 'wb').write(payload)
            else:
                # 和 App 一样：长边超过 2048 的对半缩（WeTex.decode 的 maxSide）
                while max(payload.size) > 2048: payload = payload.resize((payload.width // 2, payload.height // 2), Image.BOX)
                w, h = iw, ih = payload.size
                fn = f't{n}.png'; payload.save(os.path.join(out, fn))
            tex[key] = {'file': fn, 'kind': kind, 'w': w, 'h': h, 'iw': iw, 'ih': ih}
        elif name.endswith(TEXT_EXT):
            text[name] = b.decode('utf8', 'replace')
        elif name.endswith(('.jpg', '.png', '.gif')) and name.split('/')[-1].startswith('preview'):
            open(os.path.join(out, 'preview' + os.path.splitext(name)[1]), 'wb').write(b)
    json.dump({'version': ver, 'text': text, 'tex': tex, 'bad': bad}, open(os.path.join(out, 'pack.json'), 'w', encoding='utf8'), ensure_ascii=False)
    print(f'{os.path.basename(src)} {ver}: {len(text)} 文本, {len(tex)} 贴图, {len(bad)} 张解不出 {list(bad.items())[:3]}')


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
