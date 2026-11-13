# folkways_ornamentum

Unofficial **Folkways × Domum Ornamentum × Materials Integration** compatibility work for Minecraft 1.21.1 / NeoForge.

This repository collects the addon source, core patches, blueprint conversion tools, Chinese localization and test evidence produced while integrating Sword & Kingdom building packs into [Folkways](https://github.com/IzakyL/folkways).

> **下载：** [v1.0.0 Release](https://github.com/Zhougon-12/folkways-do-materials_integration/releases/tag/v1.0.0) · **展示页：** [https://zhougon-12.github.io/folkways-do-materials_integration/](https://zhougon-12.github.io/folkways-do-materials_integration/)

> **Upstream credit**
>
> - [Folkways](https://github.com/IzakyL/folkways) — the colony mod this addon targets. The core-patch source under `core-patch/` is derived from Folkways nightly `86e1068`; its MIT license and copyright belong to IzakyL.
> - [Domum Ornamentum](https://github.com/ldtteam/Domum-Ornamentum) — material-textured building blocks.
> - Materials Integration by 22angle — resource unification.
> - Sword & Kingdom building-pack authors and translators — blueprint and language sources.

## Features

- Converts MineColonies/Structurize `.blueprint` packs into Folkways native structure NBT.
- Preserves Domum Ornamentum `textureData` + `block_state` for exact ghost preview, ordering and placement.
- Exact Do ghost preview in the blueprint picker (`ModelData` from the blueprint block entity).
- Lazy local-blueprint loading, so thousands of blueprints do not all load into memory at startup.
- 64 MB NBT read limit for large converted blueprints.
- Do block drops mapped to Materials Integration resources (`planks/log/rock/stone/sand/dirt`).
- Sorting workbench registered as a Folkways workshop; sorting work consumes integration resources and produces recipe outputs.
- Chinese display names for local blueprints without putting non-ASCII paths into `ResourceLocation`.
- `ground_blocks.json` selection for `structurize:blocksolidsubstitution`.

## Repository layout

```text
src/                 addon Java sources
core-patch/          patched Folkways classes (MIT, derived from upstream)
tools/               blueprint conversion + ground selection tools
docs/                Sword & Kingdom conversion notes, name map, manifest
evidence/            in-game screenshots
build.ps1            offline javac build script
```

## Build

The project intentionally does not commit Minecraft/NeoForge/Folkways/Do jars. Put the required jars in `libs/`:

```text
folkways-core.jar
folkways-vanilla.jar
domum-ornamentum.jar
materials_integration.jar
ldlib2.jar
```

Then run:

```powershell
.\build.ps1
```

`build.ps1` finds the JDK and NeoForge libraries from a local PCL instance; edit the `param()` defaults for another installation.

## Installation

1. Install Folkways core/vanilla nightly 86e1068.
2. Install Domum Ornamentum 1.0.237+ and Materials Integration 2.8.
3. Put `folkways_ornamentum-1.0.0.jar` in `mods/`.
4. Replace `folkways-vanilla` with the patched jar built from `core-patch/` (the core patch is required for lazy loading, exact Do preview and Chinese display names).
5. Copy converted packs into `folkways/blueprint/<pack>/`.
6. Copy `docs/blueprint_names_zh_cn.json` to `config/folkways_ornamentum/blueprint_names_zh_cn.json`.

The full prebuilt share package is distributed as a GitHub Release asset, not committed to the source tree.

## Verified in game

- Full set: 24 packs / 7368 local blueprints loaded without OOM.
- Every local blueprint read and parsed: `seen=7368 missing=0 invalid=0`.
- Converted building `age_of_colonies/agriculture_fields/3vines.nbt`:
  `SELFTEST-PASS wanted=78 placed=78 verifyMismatch=0`.
- Do panel break test: drops `1 materials_integration:planks_integration`.
- Sorting work: `2 planks_integration → 8 oak_planks`; workbench claim registered.

See `evidence/` and `docs/README-剑与王国.md` for screenshots and detailed logs.

## Known limitations

- The Materials Integration sorter bridge is implemented and its work node is verified; full colony scheduling depends on available workers, storage space and building licenses.
- Raw integration resources are not direct substitutes for finished Do blocks; citizens must sort materials and use the architect's cutter to produce the exact Do items.
- Some Sword & Kingdom source blueprints are empty upgrade/placeholder files or exceed practical NBT limits; these are listed in `docs/conversion_manifest.json` and skipped.
- This is an unofficial compatibility project, not a fork of Folkways. Upstream API changes can break the core patches.

## License

MIT. See [LICENSE](LICENSE). Folkways-derived files retain upstream copyright; Domum Ornamentum and Materials Integration remain under their own licenses.
