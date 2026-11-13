"""Convert every Sword & Kingdom .blueprint pack into Folkways structure NBT.

Output layout:
    blueprints-out-sword-kingdom/<pack_slug>/<relative_ascii_path>.nbt
    blueprints-out-sword-kingdom/blueprint_names_zh_cn.json
    blueprints-out-sword-kingdom/conversion_manifest.json

The JSON is consumed by the optional core BlueprintNameMap patch so the
English/ASCII resource path can still be displayed with a Chinese name in the
Folkways blueprint picker.
"""
from __future__ import annotations

import hashlib
import importlib.util
import json
import os
import re
import sys
from collections import Counter

HERE = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.dirname(HERE)
CONVERTER = os.path.join(PROJECT, "folkways-ornamentum", "tools", "convert_blueprints.py")
spec = importlib.util.spec_from_file_location("blueprint_converter", CONVERTER)
converter = importlib.util.module_from_spec(spec)
spec.loader.exec_module(converter)

SRC = r"D:\software\pcl\.minecraft\versions\剑与王国官服正式版\blueprints"
OUT = os.path.join(PROJECT, "blueprints-out-sword-kingdom")
GROUND_CONFIG = os.path.join(HERE, "ground_blocks.json")

# The old converter used dominant_solid(), which picked a different block for
# every building.  Ground blocks are now selected explicitly.  Edit
# ground_blocks.json to change the default or override one pack/building.
GROUND_DEFAULT = "minecraft:stone_bricks"
try:
    with open(GROUND_CONFIG, encoding="utf-8") as _ground_file:
        _ground = json.load(_ground_file)
except FileNotFoundError:
    _ground = {}
GROUND_PACKS = dict(_ground.get("packs", {}))
GROUND_BUILDINGS = dict(_ground.get("buildings", {}))
if isinstance(_ground.get("default"), str) and _ground["default"].strip():
    GROUND_DEFAULT = _ground["default"].strip()


def ground_for(pack_slug: str, relative: str) -> str:
    stem = os.path.splitext(relative.replace("\\", "/"))[0]
    key = pack_slug + "/" + stem
    value = GROUND_BUILDINGS.get(key) or GROUND_PACKS.get(pack_slug) or GROUND_DEFAULT
    if not re.fullmatch(r"[a-z0-9_.\-]+:[a-z0-9_./\-]+", value):
        raise ValueError("invalid ground block id: %s" % value)
    return value


CJK = re.compile(r"[\u3400-\u9fff\uf900-\ufaff]")

PACK_SLUGS = {
    "Age of Colonies": "age_of_colonies",
    "CQs Extras": "cqs_extras",
    "High Elven": "high_elven",
    "Hive": "hive",
    "OakTreehouse": "oak_treehouse",
    "Road": "road",
    "Roman": "roman",
    "WarMineCraft": "war_minecraft",
    "cliffs": "cliffs",
    "clients": "local_collection",
    "caidaotongxue": "caidaotongxue",
    "kehuanmeng": "kehuanmeng",
    "lyc123456": "lyc_buildings",
    "medievaloakresidences": "medieval_oak_residences",
    "medievalwalls": "medieval_walls",
    "old-english-1-8-mcol-1-20-1": "old_english",
    "sangairs": "sangairs",
    "structbackup": "struct_backup",
    "thurloe": "thurloe",
    "zhougon": "zhougon",
    "balltvsa": "balltvsa",
    "世贸大厦1.5": "world_trade_center_15",
    "世贸大厦1.7": "world_trade_center_17",
    "剑与王国特殊建筑": "sword_kingdom_special",
    "剑与王国风格": "sword_kingdom_style",
    "樱花小镇2.0(正式版)": "sakura_town_20",
    "联动特殊装饰": "collab_special_decorations",
    "[悬崖风格] Cliffs": "cliffs_local",
    "[罗马帝国风格]Imperial Roman": "roman_imperial",
}

PACK_NAMES_ZH = {
    "age_of_colonies": "殖民时代",
    "cqs_extras": "CQ 额外建筑",
    "high_elven": "高等精灵",
    "hive": "蜂巢",
    "oak_treehouse": "橡木树屋",
    "road": "道路",
    "roman": "罗马",
    "war_minecraft": "战争工艺",
    "cliffs": "悬崖",
    "local_collection": "本地收藏",
    "caidaotongxue": "菜刀同学",
    "kehuanmeng": "科幻梦",
    "lyc_buildings": "LYC 建筑",
    "medieval_oak_residences": "中世纪橡木住宅",
    "medieval_walls": "中世纪城墙",
    "old_english": "古英格兰",
    "sangairs": "桑盖尔（本地）",
    "struct_backup": "结构备份",
    "thurloe": "瑟洛",
    "zhougon": "周工（本地）",
    "balltvsa": "Balltvsa（本地）",
    "world_trade_center_15": "世贸大厦 1.5",
    "world_trade_center_17": "世贸大厦 1.7",
    "sword_kingdom_special": "剑与王国特殊建筑",
    "sword_kingdom_style": "剑与王国风格",
    "sakura_town_20": "樱花小镇 2.0",
    "collab_special_decorations": "联动特殊装饰",
    "cliffs_local": "悬崖（本地重复包）",
    "roman_imperial": "罗马帝国风格（本地）",
}

# The most common MineColonies / Structurize / Sword & Kingdom role words.
WORDS = {
    "agriculture": "农业",
    "horticulture": "园艺",
    "composter": "堆肥厂",
    "farmer": "农场",
    "flowershop": "花店",
    "florist": "花艺师",
    "plantation": "种植园",
    "fishery": "渔场",
    "fisherman": "渔夫",
    "fishing": "钓鱼",
    "shepherd": "牧羊人",
    "swineherder": "养猪人",
    "chickenherder": "养鸡人",
    "cowboy": "牛仔",
    "rabbithutch": "兔舍",
    "alchemist": "炼金术士",
    "enchanter": "附魔师",
    "university": "大学",
    "hospital": "医院",
    "school": "学校",
    "library": "图书馆",
    "residence": "住宅",
    "tavern": "酒馆",
    "restaurant": "餐厅",
    "cooker": "厨房",
    "cook": "厨师",
    "warehouse": "仓库",
    "sawmill": "锯木厂",
    "stonemason": "石匠",
    "blacksmith": "铁匠",
    "mechanic": "机械师",
    "dyer": "染坊",
    "glassblower": "玻璃工坊",
    "concretemixer": "混凝土搅拌站",
    "crusher": "粉碎机",
    "sifter": "筛选机",
    "fletcher": "制箭师",
    "archery": "射箭场",
    "barracks": "兵营",
    "guardtower": "守卫塔",
    "guard": "守卫",
    "graveyard": "墓地",
    "builder": "建筑工",
    "townhall": "市政厅",
    "town": "城镇",
    "home": "住宅",
    "miner": "矿工",
    "minermainshaft": "矿业主井",
    "minerx2": "矿工双人",
    "minerx3": "矿工三人",
    "minerx4": "矿工四人",
    "mine": "矿场",
    "quarry": "采石场",
    "mineshaft": "矿井",
    "supplycamp": "补给营地",
    "supplyship": "补给船",
    "warehouse": "仓库",
    "field": "田地",
    "farm": "农场",
    "road": "道路",
    "bigroad": "大路",
    "bridge": "桥",
    "wall": "城墙",
    "walls": "城墙",
    "gate": "城门",
    "tower": "塔",
    "arch": "拱门",
    "corner": "拐角",
    "straight": "直路",
    "tee": "丁字路口",
    "cross": "十字路口",
    "end": "尽头",
    "mid": "中段",
    "short": "短",
    "long": "长",
    "small": "小型",
    "medium": "中型",
    "large": "大型",
    "big": "大型",
    "low": "低级",
    "high": "高级",
    "alt": "备用",
    "up": "上行",
    "down": "下行",
    "incline": "坡道",
    "flat": "平路",
    "overhang": "悬挑",
    "platform": "平台",
    "decorations": "装饰",
    "decorative": "装饰",
    "lantern": "灯笼",
    "lanterns": "灯笼",
    "statue": "雕像",
    "deer": "鹿",
    "trophy": "战利品",
    "throne": "王座",
    "banner": "旗帜",
    "carpet": "地毯",
    "bench": "长椅",
    "fountain": "喷泉",
    "well": "水井",
    "market": "市场",
    "stall": "摊位",
    "dock": "码头",
    "harbor": "港口",
    "seagate": "海门",
    "lighthouse": "灯塔",
    "ship": "船",
    "balloon": "热气球",
    "tree": "树",
    "treehouse": "树屋",
    "oak": "橡木",
    "birch": "白桦",
    "spruce": "云杉",
    "dark": "深色",
    "wood": "木",
    "stone": "石",
    "brick": "砖",
    "marble": "大理石",
    "quartz": "石英",
    "medieval": "中世纪",
    "roman": "罗马",
    "english": "英格兰",
    "elven": "精灵",
    "imperial": "帝国",
    "kingdom": "王国",
    "sword": "剑",
    "war": "战争",
    "craft": "工艺",
    "colony": "殖民地",
    "colonies": "殖民地",
    "age": "时代",
    "old": "古老",
    "new": "新",
    "sakura": "樱花",
    "cherry": "樱花",
    "village": "村庄",
    "township": "小镇",
    "castle": "城堡",
    "palace": "宫殿",
    "temple": "神庙",
    "church": "教堂",
    "chapel": "礼拜堂",
    "fort": "堡垒",
    "fortress": "要塞",
    "camp": "营地",
    "armory": "军械室",
    "barracks": "兵营",
    "stable": "马厩",
    "barn": "谷仓",
    "silo": "筒仓",
    "windmill": "风车",
    "watermill": "水车",
    "bakery": "面包房",
    "baker": "面包师",
    "butcher": "屠夫",
    "kitchen": "厨房",
    "dining": "餐厅",
    "dininghall": "餐厅",
    "storage": "储物",
    "smeltery": "冶炼厂",
    "smelter": "冶炼炉",
    "forge": "锻造坊",
    "furnace": "熔炉",
    "nether": "下界",
    "end": "末地",
    "portal": "传送门",
    "shrine": "神龛",
    "mystic": "神秘",
    "magic": "魔法",
    "laboratory": "实验室",
    "observatory": "天文台",
    "academy": "学院",
    "headquarters": "总部",
    "office": "办公室",
    "shop": "商店",
    "trade": "贸易",
    "post": "驿站",
    "station": "车站",
    "rail": "铁路",
    "path": "小路",
    "square": "广场",
    "plaza": "广场",
    "garden": "花园",
    "park": "公园",
    "pond": "池塘",
    "river": "河流",
    "coast": "海岸",
    "cliff": "悬崖",
    "mountain": "山地",
    "forest": "森林",
    "desert": "沙漠",
    "snow": "雪地",
    "swamp": "沼泽",
    "island": "岛屿",
    "mine": "矿场",
    "shaft": "竖井",
    "tunnel": "隧道",
    "excavation": "挖掘场",
    "scaffold": "脚手架",
    "construction": "施工",
    "warehouse": "仓库",
    "logistics": "物流",
    "courier": "信使",
    "postbox": "邮箱",
    "bath": "浴室",
    "bathhouse": "浴场",
    "hotel": "旅馆",
    "inn": "旅店",
    "embassy": "使馆",
    "guild": "公会",
    "hall": "大厅",
    "house": "房屋",
    "hut": "小屋",
    "cabin": "木屋",
    "lodge": "小屋",
    "manor": "庄园",
    "estate": "庄园",
    "villa": "别墅",
    "palace": "宫殿",
    "gazebo": "凉亭",
    "pavilion": "亭子",
    "bridge": "桥",
    "stairs": "楼梯",
    "stair": "楼梯",
    "slope": "斜坡",
    "ramp": "坡道",
    "wall": "墙",
    "fence": "栅栏",
    "road": "道路",
    "street": "街道",
    "highway": "大道",
    "plaza": "广场",
    "innercorner": "内拐角",
    "outercorner": "外拐角",
    "inner": "内部",
    "outer": "外部",
    "vines": "藤蔓",
    "waterplants": "水生植物",
    "bamboo": "竹子",
    "cocoa": "可可",
    "cactus": "仙人掌",
    "glowberry": "发光浆果",
    "sugarcane": "甘蔗",
    "plants": "植物",
    "plant": "植物",
    "gate": "城门",
    "gates": "城门",
    "tunnel": "隧道",
    "cathedral": "大教堂",
    "chapel": "礼拜堂",
    "shapes": "造型",
    "scan": "扫描件",
    "scans": "扫描件",
    "bundle": "套装",
    "upgrade": "升级件",
    "alt": "备用",
    "left": "左侧",
    "right": "右侧",
    "top": "上层",
    "leftright": "左右",
    "topleft": "左上",
    "topright": "右上",
    "one": "一",
    "two": "二",
    "three": "三",
    "four": "四",
    "five": "五",
    "lumberjack": "伐木工",
    "combatacademy": "战斗学院",
    "mysticalsite": "神秘遗址",
    "stonesmeltery": "石炼厂",
    "deliveryman": "送货员",
    "beekeeper": "养蜂人",
    "barrackstower": "兵营塔",
    "apiary": "养蜂场",
    "nethermine": "下界矿场",
    "netherworker": "下界工人",
    "water": "水",
    "concrete": "混凝土",
    "mason": "石匠",
    "lumber": "木材",
    "rabbit": "兔子",
    "elevated": "高架",
    "front": "正面",
    "dirt": "泥土",
    "chicken": "鸡",
    "double": "双层",
    "mill": "磨坊",
    "forester": "护林员",
    "lamp": "灯",
    "husbandry": "畜牧业",
    "chickenhut": "鸡舍",
    "pot": "花盆",
    "center": "中心",
    "side": "侧面",
    "mine": "矿场",
    "worker": "工人",
    "scan": "扫描",
    "blockpos": "坐标",
    "tower": "塔",
    "gate": "大门",
    "tunnel": "隧道",
    "netherminer": "下界矿工",
    "stonesmelter": "炼石炉",
    "cowherder": "养牛人",
    "pigherder": "养猪人",
    "sheephut": "羊舍",
    "sheep": "羊",
    "cow": "牛",
    "pig": "猪",
    "site": "遗址",
    "cafe": "咖啡馆",
    "cattle": "牛",
    "myguard": "我的守卫",
    "tcwarehouse": "贸易仓库",
    "ascending": "上行",
    "descending": "下行",
    "out": "外侧",
    "in": "内侧",
    "blue": "蓝色",
    "wall": "墙",
    "uni": "大学",
    "ca": "洞穴",
    "of": "",
    "to": "",
    "and": "",
    "the": "",
}

DIGITS = re.compile(r"^[0-9]+$")


def safe_slug(value: str) -> str:
    value = value.strip().lower()
    value = value.replace(" ", "_")
    value = re.sub(r"[^a-z0-9_.-]+", "_", value)
    value = re.sub(r"_+", "_", value).strip("_.-")
    return value or "unnamed"


def unique_slug(base: str, used: set[str]) -> str:
    slug = base
    index = 2
    while slug in used:
        slug = "%s_%d" % (base, index)
        index += 1
    used.add(slug)
    return slug


def display_name(stem: str) -> str:
    if CJK.search(stem):
        return stem
    spaced = re.sub(r"([a-z0-9])([A-Z])", r"\1 \2", stem)
    tokens = re.findall(r"[A-Za-z]+|[0-9]+", spaced)
    if not tokens:
        return stem
    translated = []
    known = 0
    for token in tokens:
        if token.isdigit():
            translated.append(token)
            continue
        word = WORDS.get(token.lower())
        if word:
            translated.append(word)
            known += 1
        else:
            translated.append(token)
    if known == 0:
        return "本地扫描 " + stem
    # Keep a compact Chinese name; digits attach to the previous word.
    parts = []
    for piece in translated:
        if piece.isdigit() and parts:
            parts[-1] += piece
        else:
            parts.append(piece)
    return "·".join(parts)


def main() -> int:
    os.makedirs(OUT, exist_ok=True)
    packs = []
    for pack in sorted(os.listdir(SRC)):
        folder = os.path.join(SRC, pack)
        if not os.path.isdir(folder):
            continue
        slugs = PACK_SLUGS.get(pack)
        if slugs is None:
            slugs = safe_slug(pack)
        packs.append((pack, slugs))

    names = {"packs": {}, "buildings": {}}
    manifest = {"source": SRC, "output": OUT, "packs": [], "entries": [], "skipped": []}
    totals = Counter()

    for pack, pack_slug in packs:
        folder = os.path.join(SRC, pack)
        files = []
        for base, _dirs, filenames in os.walk(folder):
            for filename in sorted(filenames):
                if filename.lower().endswith(".blueprint"):
                    files.append(os.path.join(base, filename))
        files.sort()
        if not files:
            continue
        names["packs"][pack_slug] = PACK_NAMES_ZH.get(pack_slug, pack)
        pack_info = {"source": pack, "slug": pack_slug, "name_zh": names["packs"][pack_slug], "files": len(files), "ground": GROUND_PACKS.get(pack_slug, GROUND_DEFAULT)}
        used = set()
        converted = 0
        for source in files:
            relative = os.path.relpath(source, folder).replace("\\", "/")
            stem = os.path.splitext(os.path.basename(relative))[0]
            stem_dir = os.path.dirname(relative)
            if CJK.search(stem):
                base = "cn_" + hashlib.sha1(stem.encode("utf-8")).hexdigest()[:10]
            else:
                base = safe_slug(stem)
            out_stem = unique_slug(base, used)
            out_relative = os.path.join(pack_slug, safe_slug(stem_dir), out_stem + ".nbt") if stem_dir else os.path.join(pack_slug, out_stem + ".nbt")
            destination = os.path.join(OUT, out_relative)
            try:
                ground = ground_for(pack_slug, relative)
                stats = converter.convert_one(source, destination, ground)
                stats["ground"] = ground
                import gzip as _gzip
                with _gzip.open(destination, "rb") as check_stream:
                    uncompressed = len(check_stream.read())
                if uncompressed > 8_300_000:
                    os.remove(destination)
                    raise ValueError("NBT too large for Folkways: %d bytes" % uncompressed)
            except Exception as error:  # noqa: BLE001
                manifest["skipped"].append({"source": relative, "error": repr(error)})
                print("[skip] %s: %s" % (relative, error), file=sys.stderr)
                continue
            building_key = "blueprint/" + out_relative[:-4].replace("\\", "/")
            zh = display_name(stem)
            names["buildings"][building_key] = zh
            manifest["entries"].append(
                {
                    "source": relative,
                    "output": out_relative.replace("\\", "/"),
                    "pack": pack_slug,
                    "name_zh": zh,
                    **stats,
                }
            )
            for key, value in stats.items():
                if isinstance(value, int):
                    totals[key] += value
            converted += 1
        pack_info["converted"] = converted
        manifest["packs"].append(pack_info)
        print("[pack] %s -> %s: %d/%d" % (pack, pack_slug, converted, len(files)))

    with open(os.path.join(OUT, "blueprint_names_zh_cn.json"), "w", encoding="utf-8") as handle:
        json.dump(names, handle, ensure_ascii=False, indent=2)
    with open(os.path.join(OUT, "conversion_manifest.json"), "w", encoding="utf-8") as handle:
        json.dump(manifest, handle, ensure_ascii=False, indent=2)
    print(
        "DONE entries=%d blocks=%d do=%d textured=%d skipped=%d packs=%d"
        % (len(manifest["entries"]), totals["blocks"], totals["do_blocks"], totals["textured_blocks"], len(manifest["skipped"]), len(manifest["packs"]))
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
