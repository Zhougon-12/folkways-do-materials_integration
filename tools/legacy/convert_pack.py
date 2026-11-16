"""批量把 MineColonies 蓝图包转成原版结构 NBT（给 Folkways 用）。

规则：
  - 空气 / structurize:* 占位块 → 跳过
  - minecolonies:* 殖民地工作方块 → 跳过（相当于空气）
  - domum_ornamentum:* 保留，并把 tile_entities 的 textureData 写进结构 nbt

用法:
  python convert_pack.py <蓝图包目录> <输出目录>
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import convert2  # noqa: E402
import nbt  # noqa: E402


def keep(state, tile):
    name = state.get('Name', 'minecraft:air')
    if name == 'minecraft:air':
        return False
    if name.startswith('structurize:'):
        return False
    if name.startswith('minecolonies:'):
        return False
    return True


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
            entry['nbt'] = tile
        blocks.append(entry)
    return {
        'DataVersion': 3955,
        'size': [sx, sy, sz],
        'palette': palette,
        'blocks': blocks,
        'entities': [],
    }


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return
    src_root = sys.argv[1]
    dst_root = sys.argv[2]
    total_files = 0
    total_blocks = 0
    skipped = 0
    failed = 0
    for dirpath, _, names in os.walk(src_root):
        for name in names:
            if not name.endswith('.blueprint'):
                continue
            total_files += 1
            src = os.path.join(dirpath, name)
            rel = os.path.relpath(src, src_root)
            dst = os.path.join(dst_root, os.path.splitext(rel)[0] + '.nbt')
            try:
                _, root = nbt.load(src)
                sx, sy, sz, cells = convert2.read_blueprint(root)
                kept = [c for c in cells if keep(c[3], c[4])]
                skipped += len(cells) - len(kept)
                total_blocks += len(kept)
                os.makedirs(os.path.dirname(dst), exist_ok=True)
                convert2.write_nbt(dst, to_structure(sx, sy, sz, kept))
            except Exception as error:
                failed += 1
                print('FAIL', rel, error)
    print('files', total_files, 'blocks', total_blocks, 'skipped', skipped, 'failed', failed)


if __name__ == '__main__':
    main()
