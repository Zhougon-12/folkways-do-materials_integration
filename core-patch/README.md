# Folkways 核心类补丁（蓝图入口 + 预览完整性）

针对实例里的 Folkways nightly `86e1068`，只改两个类，不新增注册、不改存档格式。

## 1. `BuildContent.BLUEPRINTS`

```diff
   static final PageKind BLUEPRINTS = PageKind.boarded(
       ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "blueprints"), "folkways.tab.blueprints", ResourceLocation.withDefaultNamespace("map")
-  );
+  ).inNavigation();
```

蓝图页原本是 `Enrollment` 的第二页且 `navigation=false`，在殖民书里不会成为导航页，
`ColonyShell.open(player, blueprints)` 会静默拒绝。改成 navigation 后可以直接打开。

## 2. `BlueprintsPage.Side.build` / `PreviewWorld`

```diff
   states.get(index)
      .filter(state -> !state.isAir())
-     .ifPresent(state -> laid.put(new BlockPos(...), BlockInfo.fromBlockState(state)));
+     .ifPresent(state -> {
+        BlockInfo info = BlockInfo.fromBlockState(state);
+        CompoundTag nbt = cell.getCompound("nbt");
+        if (!nbt.isEmpty()) info.setTag(nbt);
+        laid.put(new BlockPos(...), info);
+     });
```

并新增自定义 `PreviewWorld extends TrackedDummyWorld`：

```java
@Override
public BlockEntity getBlockEntity(BlockPos pos) {
    // 用蓝图 state 创建真实 Do BE，postEntity 载入 textureData
}

@Override
public ModelData getModelData(BlockPos pos) {
    // ← 关键修复：返回 BE.getModelData() 而不是 ModelData.EMPTY
}
```

根因是：LDLib 的 `TrackedDummyWorld` 继承默认 `getModelData` 总是
`ModelData.EMPTY`；Do 的 `MateriallyTexturedBakedModel.getRenderTypes` 在没有
`MATERIAL_TEXTURE_PROPERTY` 时返回 `none()`，所以 Do 被整个跳过。重写
`getModelData` 后，Do 走正常 `renderBatched`/`tesselateBlock`，模型/材质精确，
不需要 BER、`applyBESR` 或反射。

`PreviewWorld` 还必须重写 `getBlockEntity`：`DummyWorld` 是 client-side，普通
`LevelChunk.setBlockState` 不创建 BE，LDLib 原生 `BlockInfo.postEntity` 拿到 null；
这里在预览世界内按需创建并缓存 BE。

## 构建（离线）

把本目录两个 `.java` 覆盖到对应源路径后，用实例 JDK 编译：

```powershell
$javac = 'C:\Users\Administrator\AppData\Roaming\.minecraft\runtime\java-runtime-delta\bin\javac.exe'
$cp = Get-Content '..\build\classpath.txt' -Raw
$libsRoot = 'E:\下载\BeLoong.zip\BeLoong\BeLoong\libraries'
$cp = "$libsRoot\net\neoforged\neoforge\21.1.265\neoforge-21.1.265-client.jar;$libsRoot\net\neoforged\neoforge\21.1.265\neoforge-21.1.265-universal.jar;" + $cp
$cp += ';..\libs\*;..\_work\libjar\META-INF\jarjar\taffy-1.1.5.jar;..\_work\libjar\META-INF\jarjar\yoga-1.0.0.jar'
& $javac -proc:none -encoding UTF-8 -cp $cp -d ..\build\corepatch `
  .\io\github\izakyl\folkways\vanilla\build\BuildContent.java `
  .\io\github\izakyl\folkways\vanilla\build\BlueprintsPage.java
```

然后用 `jar --update` 把 `build/corepatch` 覆盖进实例的 `folkways-vanilla` jar。原始 jar
已备份为 `folkways-vanilla-neoforge-...jar.bak`。

## 与附加模组的关系

这两处补丁只影响殖民书 UI，不影响建造/材料/Do 放置链。没有补丁时社区建筑仍可正常
建造，只是蓝图页入口和 Do 预览不可见。
