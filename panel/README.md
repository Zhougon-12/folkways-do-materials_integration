# Folkways 蓝图转换面板

本地面板，包装 `folkways-ornamentum/tools/convert_blueprints.py`，用来扫描 MineColonies / Structurize `.blueprint` 并批量转换为 Folkways 结构 NBT。

## 启动

```powershell
python panel/server.py
```

打开：

```text
http://127.0.0.1:8765/
```

## 功能

- 选择一个蓝图目录，或单个 `.blueprint`
- 优先使用 Windows 系统文件夹选择器；失败时回退到内置网页目录浏览器
- 递归扫描 `.blueprint`
- 保留相对目录结构，输出 `.nbt`
- 可选地面替换方块（`structurize:blocksolidsubstitution`）
- 实时日志、完成数、错误数、方块总数
- 自动写 `conversion_manifest.json`
- 本地离线运行，不依赖第三方 Python 包

## 说明

- 使用 `folkways-ornamentum/tools/convert_blueprints.py`，即新版转换器。
- `tools/legacy/` 保留旧版 `convert.py`、`convert2.py`、`convert_pack.py`，仅用于对比/旧格式转换。
- 转换后的蓝图也随 Release 提供：`converted-blueprints-sword-kingdom.zip`。
