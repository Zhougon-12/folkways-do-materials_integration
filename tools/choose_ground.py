"""Choose the ground block used for all converted Sword & Kingdom blueprints.

Run:
    python choose_ground.py

It updates blueprint-tools/ground_blocks.json.  Then rerun
convert_sword_blueprints.py, or answer yes when prompted.
"""
import json
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
CONFIG = os.path.join(HERE, "ground_blocks.json")
CONVERTER = os.path.join(HERE, "convert_sword_blueprints.py")

PRESETS = [
    ("1", "minecraft:stone_bricks", "石砖"),
    ("2", "minecraft:calcite", "方解石"),
    ("3", "minecraft:smooth_quartz", "平滑石英"),
    ("4", "minecraft:deepslate_bricks", "深板岩砖"),
    ("5", "minecraft:polished_diorite", "磨制闪长岩"),
    ("6", "minecraft:stone", "石头"),
]


def main() -> int:
    print("选择地面方块（应用于所有 structurize:blocksolidsubstitution 占位格）：")
    for key, block, name in PRESETS:
        print("  %s. %s —— %s" % (key, name, block))
    print("  7. 自定义 block id")
    choice = input("请输入编号 [1]: ").strip() or "1"
    if choice == "7":
        block = input("block id（例如 minecraft:mossy_stone_bricks）: ").strip()
    else:
        block = next((item[1] for item in PRESETS if item[0] == choice), "")
    if not block or ":" not in block or any(c.isupper() or ord(c) > 127 for c in block):
        print("无效 block id：", block)
        return 2
    try:
        config = json.load(open(CONFIG, encoding="utf-8"))
    except FileNotFoundError:
        config = {}
    config["default"] = block
    config.setdefault("packs", {})
    config.setdefault("buildings", {})
    with open(CONFIG, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(config, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    print("已设置默认地面方块：", block)
    if input("现在重新转换全部建筑包？[y/N]: ").strip().lower() in ("y", "yes"):
        return subprocess.call([sys.executable, CONVERTER])
    print("稍后运行：python", CONVERTER)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
