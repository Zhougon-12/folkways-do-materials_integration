"""MineColonies .blueprint -> Folkways Blueprint NBT / 原版结构 NBT。

用法:
  python convert2.py <输入.blueprint> <输出.nbt> [名字] [--structure]
"""
import gzip
import os
import struct
import sys
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import nbt  # noqa: E402


def state_compound(entry):
    out = {'Name': entry.get('Name', 'minecraft:air')}
    props = entry.get('Properties')
    if isinstance(props, dict) and props:
        out['Properties'] = {k: str(v) for k, v in props.items()}
    return out


def read_blueprint(blueprint):
    sx = int(blueprint.get('size_x', 0))
    sy = int(blueprint.get('size_y', 0))
    sz = int(blueprint.get('size_z', 0))
    palette = blueprint.get('palette', [])
    packed = blueprint.get('blocks', [])
    tile_entities = blueprint.get('tile_entities', [])

    tiles = {}
    for tile in tile_entities:
        if not isinstance(tile, dict):
            continue
        if 'x' in tile and 'y' in tile and 'z' in tile:
            key = (int(tile['x']), int(tile['y']), int(tile['z']))
            tiles[key] = {k: v for k, v in tile.items() if k not in ('x', 'y', 'z')}

    indices = []
    for value in packed:
        # Structurize 打包：高 16 位是第一个方块
        indices.append((value >> 16) & 0xFFFF)
        indices.append(value & 0xFFFF)

    cells = []
    volume = sx * sy * sz
    for idx, state_id in enumerate(indices[:volume]):
        if state_id <= 0 or state_id >= len(palette):
            continue
        entry = palette[state_id]
        if entry.get('Name', 'minecraft:air') == 'minecraft:air':
            continue
        x = idx % max(sx, 1)
        z = (idx // max(sx, 1)) % max(sz, 1)
        y = idx // max(sx * sz, 1)
        cells.append((x, y, z, state_compound(entry), tiles.get((x, y, z))))
    return sx, sy, sz, cells


def to_blueprint(sx, sy, sz, cells, name):
    blocks = []
    for x, y, z, state, tile in cells:
        item = {'offset': [x, y, z], 'state': state}
        if tile:
            item['blockEntity'] = tile
        blocks.append(item)
    return {
        'id': list(struct.unpack('>4i', uuid.uuid4().bytes)),
        'name': name,
        'printed': False,
        'createdGameTime': 0,
        'size': [sx, sy, sz],
        'blocks': blocks,
        'joints': [],
    }


def to_structure(sx, sy, sz, cells):
    palette = []
    index = {}
    blocks = []
    for x, y, z, state, tile in cells:
        key = (state.get('Name'), tuple(sorted((state.get('Properties') or {}).items())))
        if key not in index:
            index[key] = len(palette)
            palette.append(state)
        entry = {'pos': [x, y, z], 'state': index[key]}
        if tile:
            # 结构 NBT：方块实体数据放在 "nbt" 里
            entry['nbt'] = dict(tile)
        blocks.append(entry)
    return {
        'DataVersion': 3955,
        'size': [sx, sy, sz],
        'palette': palette,
        'blocks': blocks,
        'entities': [],
    }


def write_nbt(path, root):
    buf = bytearray()

    def write_string(v):
        raw = v.encode('utf-8')
        buf.extend(struct.pack('>H', len(raw)))
        buf.extend(raw)

    def tag_of(val):
        if isinstance(val, bool):
            return 1
        if isinstance(val, int):
            return 3
        if isinstance(val, float):
            return 6
        if isinstance(val, str):
            return 8
        if isinstance(val, list):
            return 9
        if isinstance(val, dict):
            return 10
        raise ValueError('unsupported value %r' % (val,))

    def write_payload(tag, value):
        if tag == 3:
            buf.extend(struct.pack('>i', int(value)))
        elif tag == 4:
            buf.extend(struct.pack('>q', int(value)))
        elif tag == 1:
            buf.extend(struct.pack('>b', 1 if value else 0))
        elif tag == 8:
            write_string(value)
        elif tag == 9:
            if not value:
                buf.extend(struct.pack('>b', 10))
                buf.extend(struct.pack('>i', 0))
            else:
                item_tag = tag_of(value[0])
                buf.extend(struct.pack('>b', item_tag))
                buf.extend(struct.pack('>i', len(value)))
                for item in value:
                    write_payload(item_tag, item)
        elif tag == 10:
            for key, val in value.items():
                itag = tag_of(val)
                buf.extend(struct.pack('>b', itag))
                write_string(key)
                write_payload(itag, val)
            buf.extend(b'\x00')
        elif tag == 11:
            buf.extend(struct.pack('>i', len(value)))
            for item in value:
                buf.extend(struct.pack('>i', int(item)))
        else:
            raise ValueError('unsupported tag %s' % tag)

    buf.extend(struct.pack('>b', 10))
    write_string('')
    write_payload(10, root)
    with gzip.open(path, 'wb') as f:
        f.write(bytes(buf))


def main():
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    structure = '--structure' in sys.argv
    if len(args) < 2:
        print(__doc__)
        return
    src, dst = args[0], args[1]
    name = args[2] if len(args) > 2 else os.path.splitext(os.path.basename(src))[0]
    _, root = nbt.load(src)
    sx, sy, sz, cells = read_blueprint(root)
    out = to_structure(sx, sy, sz, cells) if structure else to_blueprint(sx, sy, sz, cells, name)
    write_nbt(dst, out)
    print('%s: %d blocks, size %s' % ('structure' if structure else 'blueprint', len(cells), (sx, sy, sz)))
    print('out', dst)


if __name__ == '__main__':
    main()
