"""MineColonies / Structurize .blueprint -> Folkways 原生结构 NBT。

目标格式（Folkways 的 StructureNbt 读取口径）：

    { DataVersion:int, size:[sx,sy,sz], palette:[{Name,Properties?}], blocks:[{pos:[x,y,z], state:int, nbt?}], entities:[] }

对 Domum Ornamentum 的材质方块，把 .blueprint 的 tile_entities 转成
``nbt`` = ``{textureData:{componentId:blockId}, block_state:{type:"..."}}``。
Folkways 的 StructureCompat.Handler 会在蓝图/工地/幽灵/放置/材料需求里使用它。

用法：
    python convert_blueprints.py --src <源目录> --out <输出目录> [--solid minecraft:stone]
                                 [--pack 名称] [--deploy <实例目录>] [--limit N]

输出是 ``<out>/<包名>/<建筑名>.nbt``，文件名只含 ``a-z0-9_.-``（Folkways 的本地蓝图
加载器用 ResourceLocation 解析文件名，空格/大写会被直接拒绝）。
"""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import struct
import sys
from collections import Counter

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import nbt  # noqa: E402

DATA_VERSION = 3955
DO_NAMESPACE = "domum_ornamentum"
SKIP_PREFIXES = ("structurize:", "minecolonies:")
AIR = "minecraft:air"

SAFE = re.compile(r"[^a-z0-9_.-]+")


# ----------------------------------------------------------------------
# NBT 写出（只覆盖结构文件需要的类型）
# ----------------------------------------------------------------------
def write_nbt(path: os.PathLike, root: dict) -> None:
    buf = bytearray()

    def write_string(value: str) -> None:
        raw = value.encode("utf-8")
        buf.extend(struct.pack(">H", len(raw)))
        buf.extend(raw)

    def tag_of(value) -> int:
        if isinstance(value, bool):
            return 1
        if isinstance(value, int):
            return 3
        if isinstance(value, float):
            return 6
        if isinstance(value, str):
            return 8
        if isinstance(value, list):
            return 9
        if isinstance(value, dict):
            return 10
        raise TypeError("unsupported NBT value %r" % (value,))

    def payload(tag: int, value) -> None:
        if tag == 1:
            buf.extend(struct.pack(">b", 1 if value else 0))
        elif tag == 3:
            buf.extend(struct.pack(">i", int(value)))
        elif tag == 4:
            buf.extend(struct.pack(">q", int(value)))
        elif tag == 5:
            buf.extend(struct.pack(">f", float(value)))
        elif tag == 6:
            buf.extend(struct.pack(">d", float(value)))
        elif tag == 8:
            write_string(str(value))
        elif tag == 9:
            if not value:
                buf.extend(struct.pack(">b", 10))
                buf.extend(struct.pack(">i", 0))
            else:
                item_tag = tag_of(value[0])
                buf.extend(struct.pack(">b", item_tag))
                buf.extend(struct.pack(">i", len(value)))
                for item in value:
                    payload(item_tag, item)
        elif tag == 10:
            for key, val in value.items():
                child = tag_of(val)
                buf.extend(struct.pack(">b", child))
                write_string(str(key))
                payload(child, val)
            buf.extend(b"\x00")
        elif tag == 11:
            buf.extend(struct.pack(">i", len(value)))
            for item in value:
                buf.extend(struct.pack(">i", int(item)))
        else:
            raise TypeError("unsupported NBT tag %s" % tag)

    buf.extend(struct.pack(">b", 10))
    write_string("")
    payload(10, root)
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    with open(path, "wb") as out:
        import gzip

        with gzip.GzipFile(fileobj=out, mode="wb", mtime=0) as gz:
            gz.write(bytes(buf))


# ----------------------------------------------------------------------
# 读取 .blueprint
# ----------------------------------------------------------------------
def state_of(entry: dict) -> dict:
    state = {"Name": entry.get("Name", AIR)}
    props = entry.get("Properties")
    if isinstance(props, dict) and props:
        state["Properties"] = {str(k): str(v) for k, v in props.items()}
    return state


def unpack_indices(packed, volume: int):
    """Structurize 的 int 数组每个 int 装两个 16 位 palette 下标，高 16 位在前。"""
    out = []
    for raw in packed:
        value = raw & 0xFFFFFFFF
        out.append((value >> 16) & 0xFFFF)
        out.append(value & 0xFFFF)
        if len(out) >= volume:
            break
    return out[:volume]


def dominant_solid(states_by_index, indices) -> str:
    """为 structurize:blocksolidsubstitution 选一个常见的实心方块。"""
    counts = Counter()
    for index in indices:
        if index <= 0 or index >= len(states_by_index):
            continue
        name = states_by_index[index]["Name"]
        if not name or name == AIR or name.startswith(SKIP_PREFIXES):
            continue
        if name.startswith("minecraft:") and (
            name.endswith("_log") or name.endswith("_leaves") or name.endswith("_sapling")
        ):
            continue
        counts[name] += 1
    if not counts:
        return "minecraft:stone"
    return counts.most_common(1)[0][0]


def read_cells(root: dict, solid_override: str | None = None):
    sx = int(root.get("size_x", 0))
    sy = int(root.get("size_y", 0))
    sz = int(root.get("size_z", 0))
    if sx <= 0 or sy <= 0 or sz <= 0:
        raise ValueError("bad size %sx%sx%s" % (sx, sy, sz))

    palette = root.get("palette", [])
    states = [state_of(entry) for entry in palette]
    indices = unpack_indices(root.get("blocks", []), sx * sy * sz)

    tiles = {}
    for tile in root.get("tile_entities", []):
        if not isinstance(tile, dict):
            continue
        if "x" in tile and "y" in tile and "z" in tile:
            tiles[(int(tile["x"]), int(tile["y"]), int(tile["z"]))] = tile

    solid = solid_override or dominant_solid(states, indices)
    cells = []
    for index, state_id in enumerate(indices):
        if state_id <= 0 or state_id >= len(states):
            continue
        state = states[state_id]
        name = state.get("Name", AIR)
        if name == AIR:
            continue
        x = index % sx
        z = (index // sx) % sz
        y = index // (sx * sz)
        tile = tiles.get((x, y, z))

        if name == "structurize:blocksolidsubstitution":
            # 地基占位块：换成指定/常见实心方块
            state = {"Name": solid}
            tile = None
        elif name.startswith(SKIP_PREFIXES):
            # MineColonies / Structurize 的功能方块不移植（Folkways 有自己的建造/工作站）
            continue

        cells.append((x, y, z, state, tile))
    return sx, sy, sz, cells, solid


# ----------------------------------------------------------------------
# 输出
# ----------------------------------------------------------------------
def texture_map(tile: dict) -> dict:
    texture = tile.get("textureData") if tile else None
    if not isinstance(texture, dict):
        return {}
    out = {}
    for key, value in texture.items():
        key = str(key)
        value = str(value)
        if re.fullmatch(r"[a-z0-9_.\-]+:[a-z0-9_./\-]+", key) and re.fullmatch(
            r"[a-z0-9_.\-]+:[a-z0-9_./\-]+", value
        ):
            out[key] = value
    return out


def payload_of(state: dict, tile: dict) -> dict | None:
    """Do 材质方块的 blockEntity 数据；非 Do 方块返回 None（不保留未知 tile）。"""
    name = state.get("Name", "")
    texture = texture_map(tile) if tile else {}
    props = state.get("Properties") or {}
    is_do = name.startswith(DO_NAMESPACE + ":")
    if not is_do and not texture:
        return None
    if not is_do:
        return None

    payload = {}
    if texture:
        payload["textureData"] = texture
    if "type" in props:
        payload["block_state"] = {"type": props["type"]}
    return payload or None


def to_structure(sx, sy, sz, cells):
    palette = []
    index = {}
    blocks = []
    for x, y, z, state, tile in cells:
        key = (state.get("Name"), tuple(sorted((state.get("Properties") or {}).items())))
        if key not in index:
            index[key] = len(palette)
            palette.append(state)
        entry = {"pos": [x, y, z], "state": index[key]}
        payload = payload_of(state, tile)
        if payload:
            entry["nbt"] = payload
        blocks.append(entry)
    return {
        "DataVersion": DATA_VERSION,
        "size": [sx, sy, sz],
        "palette": palette,
        "blocks": blocks,
        "entities": [],
    }


def safe_segment(value: str) -> str:
    value = value.strip().lower()
    value = value.replace(" ", "_")
    value = SAFE.sub("_", value)
    value = re.sub(r"_+", "_", value).strip("_.-")
    return value or "unnamed"


def safe_path(relative: str) -> str:
    parts = [safe_segment(part) for part in relative.replace("\\", "/").split("/") if part]
    return "/".join(parts)


def convert_one(src: str, dst: str, solid_override: str | None):
    tag_type, root = nbt.load(src)
    sx, sy, sz, cells, solid = read_cells(root, solid_override)
    if not cells:
        raise ValueError("empty after filtering (all air / MineColonies blocks)")
    structure = to_structure(sx, sy, sz, cells)
    write_nbt(dst, structure)
    do_cells = sum(1 for _, _, _, state, tile in cells if (state.get("Name") or "").startswith(DO_NAMESPACE + ":"))
    textured = sum(1 for _, _, _, state, tile in cells if payload_of(state, tile) and payload_of(state, tile).get("textureData"))
    return {
        "size": [sx, sy, sz],
        "blocks": len(cells),
        "do_blocks": do_cells,
        "textured_blocks": textured,
        "solid": solid,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--src", required=True, help="源目录（.blueprint 树）")
    parser.add_argument("--out", required=True, help="输出目录（folkways/blueprint）")
    parser.add_argument("--solid", default="minecraft:stone_bricks", help="ground block for solid substitution; use 'auto' for old dominant_solid behavior")
    parser.add_argument("--pack", default=None, help="单目录输入时覆盖包名")
    parser.add_argument("--deploy", default=None, help="转换后复制到该实例目录的 folkways/blueprint")
    parser.add_argument("--limit", type=int, default=0, help="只转换前 N 个（调试）")
    parser.add_argument("--verbose", action="store_true")
    args = parser.parse_args()

    src_root = os.path.abspath(args.src)
    out_root = os.path.abspath(args.out)
    if not os.path.isdir(src_root):
        print("源目录不存在：%s" % src_root, file=sys.stderr)
        return 2

    files = []
    for base, _, names in os.walk(src_root):
        for name in sorted(names):
            if name.lower().endswith(".blueprint"):
                files.append(os.path.join(base, name))
    files.sort()
    if args.limit:
        files = files[: args.limit]
    if not files:
        print("没有找到 .blueprint", file=sys.stderr)
        return 2

    manifest = {"source": src_root, "output": out_root, "entries": [], "skipped": []}
    totals = Counter()
    for number, src in enumerate(files, 1):
        relative = os.path.relpath(src, src_root)
        pack = args.pack or os.path.dirname(relative)
        stem = os.path.splitext(os.path.basename(relative))[0]
        relative_out = os.path.join(safe_path(pack), safe_segment(stem) + ".nbt")
        dst = os.path.join(out_root, relative_out)

        try:
            stats = convert_one(src, dst, None if args.solid == "auto" else args.solid)
            manifest["entries"].append({"source": relative.replace("\\", "/"), "output": relative_out, **stats})
            for key, value in stats.items():
                if isinstance(value, int):
                    totals[key] += value
            if args.verbose:
                print("[%d/%d] %s -> %s %s" % (number, len(files), relative, relative_out, stats))
        except Exception as error:  # noqa: BLE001 - 逐文件报告，继续转换
            manifest["skipped"].append({"source": relative.replace("\\", "/"), "error": repr(error)})
            print("[%d/%d] 跳过 %s：%s" % (number, len(files), relative, error), file=sys.stderr)

    os.makedirs(out_root, exist_ok=True)
    with open(os.path.join(out_root, "ornamentum_manifest.json"), "w", encoding="utf-8") as handle:
        json.dump(manifest, handle, ensure_ascii=False, indent=2)

    if args.deploy:
        target = os.path.join(args.deploy, "folkways", "blueprint")
        os.makedirs(target, exist_ok=True)
        copied = 0
        for entry in manifest["entries"]:
            src = os.path.join(out_root, entry["output"])
            dst = os.path.join(target, entry["output"])
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            shutil.copy2(src, dst)
            copied += 1
        print("已部署 %d 个蓝图到 %s" % (copied, target))

    print("完成：%d 个建筑，%d 个方块，其中 Do 方块 %d（带材质 %d），跳过 %d 个文件"
          % (len(manifest["entries"]), totals["blocks"], totals["do_blocks"],
             totals["textured_blocks"], len(manifest["skipped"])))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())