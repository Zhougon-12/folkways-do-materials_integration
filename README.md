# folkways_ornamentum

**Language:** [English](#english) · [中文](#中文)

Unofficial **Folkways × Domum Ornamentum × Materials Integration** compatibility work for Minecraft 1.21.1 / NeoForge.

> **Download:** [v1.0.0 Release](https://github.com/Zhougon-12/folkways-do-materials_integration/releases/tag/v1.0.0) · **Showcase:** [zhougon-12.github.io/folkways-do-materials_integration](https://zhougon-12.github.io/folkways-do-materials_integration/)

---

## English

### Overview

This repository collects the addon source, core patches, blueprint conversion tools, Chinese/English names, and in-game test evidence produced while integrating **Sword & Kingdom** building packs into [Folkways](https://github.com/IzakyL/folkways).

### Upstream credit

- [Folkways](https://github.com/IzakyL/folkways) — the colony mod this addon targets. The `core-patch/` source is derived from Folkways nightly `86e1068`; its MIT license and copyright belong to IzakyL.
- [Domum Ornamentum](https://github.com/ldtteam/Domum-Ornamentum) — material-textured building blocks.
- Materials Integration by 22angle — resource unification.
- Sword & Kingdom building-pack authors and translators — blueprint and language sources.

### Features

- Converts MineColonies/Structurize `.blueprint` packs into native Folkways structure NBT.
- Preserves Do `textureData` + `block_state` for exact ghost preview, ordering and placement.
- Exact Do ghost preview in the blueprint picker (`ModelData` from the blueprint block entity).
- Lazy local-blueprint loading; 7368 blueprints install without loading all NBT at startup.
- 64 MB NBT read limit for large converted blueprints.
- Do block drops mapped to Materials Integration resources (`planks/log/rock/stone/sand/dirt`).
- Sorting workbench registered as a Folkways workshop.
- Chinese display names for local blueprints without non-ASCII paths in `ResourceLocation`.
- `ground_blocks.json` selection for `structurize:blocksolidsubstitution`.

### Known issues

- **Some packs contain blocks that already exist in the original Domum Ornamentum pack.** The converter preserves the original `block_state` and `textureData`, but a few blocks may not convert fully to Do materials and can fall back to vanilla blocks. Check the ghost preview before building.
- **Supply buildings still have an unresolved bug.** “Supply Camp” and related supply buildings can preview correctly but fail to create a build order, or the site may not continue normally. Back up important saves and do not use supply buildings as core buildings yet.
- The Materials Integration sorter bridge is implemented and its work node is verified; full colony scheduling depends on available workers, storage space and building licenses.
- Raw integration resources are not direct substitutes for finished Do blocks; citizens must sort materials and use the architect's cutter to produce exact Do items.
- Some source blueprints are empty upgrade/placeholder files or exceed practical NBT limits; these are listed in `docs/conversion_manifest.json` and skipped.
- This is not an official Folkways fork. Upstream API changes can break the core patches.

### Verified in game

- 24 packs / 7368 local blueprints loaded without OOM.
- Every local blueprint read and parsed: `seen=7368 missing=0 invalid=0`.
- Converted building `age_of_colonies/agriculture_fields/3vines.nbt`: `SELFTEST-PASS wanted=78 placed=78 verifyMismatch=0`.
- Do panel break test: drops `1 materials_integration:planks_integration`.
- Sorting work: `2 planks_integration → 8 oak_planks`.

### Build and install

Put the required jars in `libs/` (`folkways-core.jar`, `folkways-vanilla.jar`, `domum-ornamentum.jar`, `materials_integration.jar`, `ldlib2.jar`), then run `.\build.ps1`.

Install Folkways core/vanilla nightly `86e1068`, Domum Ornamentum `1.0.237+`, Materials Integration `2.8`, the addon jar, the patched `folkways-vanilla` jar, converted packs under `folkways/blueprint/<pack>/`, and `docs/blueprint_names_zh_cn.json` under `config/folkways_ornamentum/`.

### License

MIT. See [LICENSE](LICENSE). Folkways-derived files retain upstream copyright; Domum Ornamentum and Materials Integration remain under their own licenses.

---

## 中文

### 简介

本项目是 **Folkways × Domum Ornamentum × Materials Integration** 的非官方兼容工作，适用于 Minecraft 1.21.1 / NeoForge。

仓库包含附属模组源码、Folkways 核心补丁、蓝图转换工具、中英文名称、游戏内测试证据，以及把 **剑与王国** 建筑包移植到 Folkways 的完整流程。

### 上游来源

- [Folkways](https://github.com/IzakyL/folkways)：殖民地框架。`core-patch/` 源自 Folkways nightly `86e1068`，MIT 许可证与版权归 IzakyL 所有。
- [Domum Ornamentum](https://github.com/ldtteam/Domum-Ornamentum)：材质化建筑方块。
- Materials Integration / 22angle：资源统合与分拣台。
- 剑与王国建筑包作者、汉化作者：建筑蓝图与语言来源。

### 已实现

- 把 MineColonies / Structurize `.blueprint` 建筑包转换为 Folkways 原生结构 NBT。
- 保留 Do 的 `textureData` 与 `block_state`，让幽灵预览、下单、放置都保持精确材质。
- 蓝图选择器中的 Do 精确幽灵预览从蓝图方块实体读取 `ModelData`。
- 本地蓝图惰性加载，7368 个蓝图不会在启动时全部读入内存。
- 大蓝图读取上限提升到 64 MB。
- Do 方块按主材质掉落 Materials Integration 资源（木板/原木/岩石/石料/沙子/泥土）。
- 分拣台注册为 Folkways 工作站，消耗整合资源产出实际建材。
- 本地蓝图显示中文名称，同时保持 ASCII 文件名，不破坏 `ResourceLocation`。
- `ground_blocks.json` 可按建筑包/单栋选择 `structurize:blocksolidsubstitution`。

### 已知问题

- **部分建筑包包含 Do 原版包内已有的方块。** 转换器会尽量保留原始 `block_state` 与 `textureData`，但少数方块可能无法完全转换成 Do 材质，或会回退成原版方块。建造前请先看幽灵预览。
- **供货建筑仍存在未定位的 bug。** “补给营地 / Supply Camp”等供货建筑可能出现蓝图预览正常但无法下单，或工地无法继续的情况，会影响正常游玩。重要存档请先备份，不要把它作为核心建筑。
- Materials Integration 分拣桥接已经实现并验证工作节点；完整的殖民地调度仍依赖可用工人、仓库空间和职业设置。
- 基础整合资源不能直接替代成品 Do 方块，必须经过分拣台和建筑师切割机。
- 部分源蓝图是空升级文件/占位文件或超过实际 NBT 上限，已在 `docs/conversion_manifest.json` 中列出并跳过。
- 本项目不是 Folkways 官方分支，上游 API 变更可能导致核心补丁失效。

### 实机验证

- 24 个建筑包 / 7368 个本地蓝图全部加载，无 OOM。
- 全部蓝图读取解析：`seen=7368 missing=0 invalid=0`。
- 转换建筑 `age_of_colonies/agriculture_fields/3vines.nbt`：`SELFTEST-PASS wanted=78 placed=78 verifyMismatch=0`。
- Do 面板破坏掉落：`1 materials_integration:planks_integration`。
- 分拣工作：`2 planks_integration → 8 oak_planks`。

### 构建与安装

把依赖 jar 放进 `libs/`（`folkways-core.jar`、`folkways-vanilla.jar`、`domum-ornamentum.jar`、`materials_integration.jar`、`ldlib2.jar`），运行 `.\build.ps1`。

安装 Folkways core/vanilla nightly `86e1068`、Domum Ornamentum `1.0.237+`、Materials Integration `2.8`、附属 jar、补丁后的 `folkways-vanilla` jar；把转换后的建筑包放入 `folkways/blueprint/<pack>/`，把 `docs/blueprint_names_zh_cn.json` 放到 `config/folkways_ornamentum/`。

### 许可证

MIT，见 [LICENSE](LICENSE)。Folkways 衍生文件保留上游版权；Domum Ornamentum 与 Materials Integration 遵循各自许可证。

---

## Repository layout

```text
src/                 addon Java sources
core-patch/          patched Folkways classes (MIT, derived from upstream)
tools/               blueprint conversion + ground selection tools
docs/                showcase, Sword & Kingdom notes, name map, manifest
docs/index.html      Chinese showcase (GitHub Pages)
docs/index.en.html   English showcase (GitHub Pages)
evidence/            original in-game screenshots
build.ps1            offline javac build script
```
