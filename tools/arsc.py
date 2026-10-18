"""Minimal resources.arsc + binary XML dumper (no deps).
usage: arsc.py table <resources.arsc> [type-filter]   -> TSV: type name config value
       arsc.py xml <resources.arsc> <file.xml>          -> pretty XML with resolved @refs
"""
import struct, sys

def u16(b, o): return struct.unpack_from('<H', b, o)[0]
def u32(b, o): return struct.unpack_from('<I', b, o)[0]

def parse_strpool(b, o):
    hsz, sz = u16(b, o + 2), u32(b, o + 4)
    cnt, scnt, flags, sstart = u32(b, o + 8), u32(b, o + 12), u32(b, o + 16), u32(b, o + 20)
    utf8 = flags & 0x100
    offs = [u32(b, o + hsz + 4 * i) for i in range(cnt)]
    base = o + sstart
    out = []
    for so in offs:
        p = base + so
        if utf8:
            n = b[p]; p += 1
            if n & 0x80: p += 1
            n = b[p]; p += 1
            if n & 0x80: n = ((n & 0x7f) << 8) | b[p]; p += 1
            out.append(b[p:p + n].decode('utf8', 'replace'))
        else:
            n = u16(b, p); p += 2
            if n & 0x8000: n = ((n & 0x7fff) << 16) | u16(b, p); p += 2
            out.append(b[p:p + 2 * n].decode('utf16', 'replace'))
    return out

DIM_UNITS = ['px', 'dp', 'sp', 'pt', 'in', 'mm']
RADIX = [1 / (1 << 8), 1 / (1 << 15), 1 / (1 << 23), 1 / (1 << 31)]

def fmt_val(t, d, gstr, names):
    if t == 0x03: return gstr[d] if d < len(gstr) else '?str'
    if t == 0x01: return '@' + names.get(d, hex(d))
    if t == 0x02: return '?' + names.get(d, hex(d))
    if t == 0x05:
        m = d & 0xffffff00
        if m & 0x80000000: m -= 1 << 32
        v = m * RADIX[(d >> 4) & 3]
        return f'{round(v, 3)}{DIM_UNITS[d & 0xf] if (d & 0xf) < 6 else "?"}'
    if t == 0x06:
        m = d & 0xffffff00
        if m & 0x80000000: m -= 1 << 32
        v = m * RADIX[(d >> 4) & 3]
        return f'{round(v * 100, 3)}%' + ('p' if d & 0xf else '')
    if t == 0x04: return str(struct.unpack('<f', struct.pack('<I', d))[0])
    if t == 0x10: return str(d if d < 0x80000000 else d - (1 << 32))
    if t == 0x11: return hex(d)
    if t == 0x12: return 'true' if d else 'false'
    if 0x1c <= t <= 0x1f: return '#%08x' % d
    if t == 0x00: return '@null'
    return f'(t{t:x}){d:#x}'

def cfg_str(b, o):
    sz = u32(b, o)
    c = b[o:o + sz]
    parts = []
    lang = c[8:10]
    if lang[0]: parts.append(lang.decode('latin1', 'replace'))
    if sz >= 20:
        density = u16(c, 14)
        if density: parts.append(f'{density}dpi')
    if sz >= 28:
        sw = u16(c, 24) if sz > 25 else 0
    if sz >= 32:
        smallest = u16(c, 28)
        if smallest: parts.append(f'sw{smallest}dp')
        sdw, sdh = u16(c, 30), u16(c, 32) if sz > 33 else 0
        if sdw: parts.append(f'w{sdw}dp')
        if sdh: parts.append(f'h{sdh}dp')
    if sz >= 28:
        ui = c[27] if sz > 27 else 0
        if ui & 0x30 == 0x20: parts.append('night')
    if sz >= 12:
        orient = c[12]
        if orient == 2: parts.append('land')
    sdk = u16(c, 22) if sz > 23 else 0
    if sdk: parts.append(f'v{sdk}')
    return '-'.join(parts) or 'default'

def parse_table(path):
    b = open(path, 'rb').read()
    hsz = u16(b, 2)
    o = hsz
    gstr = None
    entries = []  # (resid, type, name, cfg, value)
    names = {}
    while o < len(b):
        ct, ch, cs = u16(b, o), u16(b, o + 2), u32(b, o + 4)
        if ct == 0x0001 and gstr is None:
            gstr = parse_strpool(b, o)
        elif ct == 0x0200:
            pid = u32(b, o + 8)
            typestr_off, keystr_off = u32(b, o + 268), u32(b, o + 276)
            tstr = parse_strpool(b, o + typestr_off)
            kstr = parse_strpool(b, o + keystr_off)
            p = o + ch
            while p < o + cs:
                t2, h2, s2 = u16(b, p), u16(b, p + 2), u32(b, p + 4)
                if t2 == 0x0201:
                    tid, flags = b[p + 8], b[p + 9]
                    ecount, estart = u32(b, p + 12), u32(b, p + 16)
                    cfg = cfg_str(b, p + 20)
                    sparse = flags & 1
                    off16 = flags & 2
                    for i in range(ecount):
                        if sparse:
                            idx, eo = u16(b, p + h2 + 4 * i), u16(b, p + h2 + 4 * i + 2) * 4
                        elif off16:
                            eo = u16(b, p + h2 + 2 * i)
                            if eo == 0xffff: continue
                            eo *= 4; idx = i
                        else:
                            eo = u32(b, p + h2 + 4 * i)
                            if eo == 0xffffffff: continue
                            idx = i
                        e = p + estart + eo
                        esz, eflags, key = u16(b, e), u16(b, e + 2), u32(b, e + 4)
                        resid = (pid << 24) | (tid << 16) | idx
                        tname = tstr[tid - 1]
                        kname = kstr[key] if key < len(kstr) else '?'
                        names[resid] = f'{tname}/{kname}'
                        if eflags & 1:
                            parent, cnt = u32(b, e + 8), u32(b, e + 12)
                            items = []
                            for j in range(cnt):
                                q = e + 16 + 12 * j
                                items.append((u32(b, q), b[q + 7], u32(b, q + 8)))
                            entries.append((resid, tname, kname, cfg, ('bag', parent, items)))
                        else:
                            entries.append((resid, tname, kname, cfg, (b[e + esz + 3], u32(b, e + esz + 4))))
                p += s2
        o += cs
    return gstr, entries, names

def show(v, gstr, names):
    if v[0] == 'bag':
        _, parent, items = v
        s = ('parent=@' + names.get(parent, hex(parent)) + ' ') if parent else ''
        return s + '{' + '; '.join(f'{names.get(k, hex(k))}={fmt_val(t, d, gstr, names)}' for k, t, d in items) + '}'
    return fmt_val(v[0], v[1], gstr, names)

def parse_axml(path, gstr_t, names):
    b = open(path, 'rb').read()
    o = u16(b, 2)
    strs, resmap, depth, out = [], [], 0, []
    while o < len(b):
        ct, ch, cs = u16(b, o), u16(b, o + 2), u32(b, o + 4)
        if ct == 0x0001: strs = parse_strpool(b, o)
        elif ct == 0x0180: resmap = [u32(b, o + 8 + 4 * i) for i in range((cs - 8) // 4)]
        elif ct == 0x0102:
            name = strs[u32(b, o + 20)]
            astart, asz, acnt = u16(b, o + 24), u16(b, o + 26), u16(b, o + 28)
            attrs = []
            for i in range(acnt):
                a = o + 16 + astart + i * asz
                ni, raw, t, d = u32(b, a + 4), u32(b, a + 8), b[a + 15], u32(b, a + 16)
                an = strs[ni] if strs[ni] else names.get(resmap[ni], '?') if ni < len(resmap) else '?'
                if not an and ni < len(resmap): an = names.get(resmap[ni], hex(resmap[ni]))
                val = strs[raw] if raw != 0xffffffff else fmt_val(t, d, strs if t == 3 else gstr_t, names)
                attrs.append(f'{an}="{val}"')
            out.append('  ' * depth + f'<{name} ' + ' '.join(attrs) + '>')
            depth += 1
        elif ct == 0x0103:
            depth -= 1
        o += cs
    return '\n'.join(out)

if __name__ == '__main__':
    mode, arsc = sys.argv[1], sys.argv[2]
    gstr, entries, names = parse_table(arsc)
    if mode == 'table':
        flt = sys.argv[3] if len(sys.argv) > 3 else None
        for resid, t, k, cfg, v in entries:
            if flt and t != flt: continue
            print(f'{t}\t{k}\t{cfg}\t{show(v, gstr, names)}')
    else:
        print(parse_axml(sys.argv[3], gstr, names))
