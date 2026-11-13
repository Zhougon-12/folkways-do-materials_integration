# 在没有 Gradle/联网的情况下编译 Folkways 附属包。
#
# 用法：
#   .\build.ps1
#   .\build.ps1 -DeployTo "<实例>\mods"
#
# 依赖（放在 libs\ 下）：
#   folkways-core.jar            Folkways Core 0.1.0
#   domum-ornamentum-1.21.1.jar  Domum Ornamentum（1.21.1 / NeoForge）
#   ldlib2.jar                   Folkways 的前置
param(
    [string]$Libraries = "E:\下载\BeLoong.zip\BeLoong\BeLoong\libraries",
    [string]$VersionJson = "E:\下载\BeLoong.zip\BeLoong\BeLoong\versions\1.21.1-NeoForge_21.1.265\1.21.1-NeoForge_21.1.265.json",
    [string]$JavaHome = "C:\Users\Administrator\AppData\Roaming\.minecraft\runtime\java-runtime-delta",
    [string]$ModVersion = "1.0.0",
    [string]$DeployTo = ""
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$javac = Join-Path $JavaHome "bin\javac.exe"
$jarTool = Join-Path $JavaHome "bin\jar.exe"
foreach ($tool in @($javac, $jarTool)) {
    if (-not (Test-Path $tool)) { throw "找不到 $tool，请用 -JavaHome 指定一个 JDK 21" }
}
if (-not (Test-Path $VersionJson)) { throw "找不到版本 JSON：$VersionJson" }

# 1. classpath：实例运行库 + 本包依赖（NeoForge 补丁 jar 必须排在原版之前）
$version = Get-Content $VersionJson -Raw | ConvertFrom-Json
$libs = @()
foreach ($entry in $version.libraries) {
    $path = $entry.downloads.artifact.path
    if (-not $path) { continue }
    $full = Join-Path $Libraries $path
    if (Test-Path $full) { $libs += $full }
}
$idParts = $version.id -split '_'
$mcVersion = ($version.id -split '-')[0]
$neoVersion = if ($idParts.Count -ge 2) { $idParts[-1] } else { "" }
$neoforgeDir = Join-Path $Libraries "net\neoforged\neoforge"
$neoforgeJar = if ($neoVersion) { Join-Path $neoforgeDir "$neoVersion\neoforge-$neoVersion-universal.jar" } else { $null }
$neoforgeClient = if ($neoVersion) { Join-Path $neoforgeDir "$neoVersion\neoforge-$neoVersion-client.jar" } else { $null }
if (-not $neoforgeJar -or -not (Test-Path $neoforgeJar)) {
    $neoforgeJar = Get-ChildItem $neoforgeDir -Recurse -Filter "neoforge-21.1.*-universal.jar" | Sort-Object Name | Select-Object -Last 1
    $neoforgeClient = Get-ChildItem $neoforgeDir -Recurse -Filter "neoforge-21.1.*-client.jar" | Sort-Object Name | Select-Object -Last 1
}
$vanillaClient = Get-ChildItem (Join-Path $Libraries "net\minecraft\client") -Recurse -Filter "client-$mcVersion-*-srg.jar" | Sort-Object Name | Select-Object -Last 1
if (-not $vanillaClient) {
    $vanillaClient = Get-ChildItem (Join-Path $Libraries "net\minecraft\client") -Recurse -Filter "client-*-srg.jar" | Sort-Object Name | Select-Object -Last 1
}

function Resolve-JarPath($value) {
    if ($null -eq $value) { return $null }
    if ($value -is [string]) { return $value }
    return $value.FullName
}
$extraJars = @(
    (Resolve-JarPath $neoforgeJar),
    (Resolve-JarPath $neoforgeClient),
    (Resolve-JarPath $vanillaClient)
) | Where-Object { $_ -and (Test-Path $_) }
$modJars = Get-ChildItem (Join-Path $root "libs") -Filter *.jar -ErrorAction SilentlyContinue |
    ForEach-Object { $_.FullName }
$classpath = (@($libs) + @($extraJars) + @($modJars)) -join ';'

New-Item -ItemType Directory -Force -Path (Join-Path $root "build") | Out-Null
Set-Content -Path (Join-Path $root "build\classpath.txt") -Value $classpath -Encoding UTF8

# 2. 编译
$classes = Join-Path $root "build\classes"
Remove-Item $classes -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$sources = @(Get-ChildItem (Join-Path $root "src\main\java") -Recurse -Filter *.java |
    ForEach-Object { $_.FullName.Substring($root.Length + 1).Replace('\', '/') })
Write-Host "编译 $($sources.Count) 个 Java 文件 ..."
$log = Join-Path $root "build\javac.log"
$err = Join-Path $root "build\javac.err"
$proc = Start-Process -FilePath $javac -WorkingDirectory $root -NoNewWindow -Wait -PassThru -RedirectStandardOutput $log -RedirectStandardError $err -ArgumentList (@('-encoding', 'UTF-8', '-proc:none', '-Xlint:none', '-d', 'build/classes', '-cp', $classpath) + $sources)
if ($proc.ExitCode -ne 0) {
    Get-Content $err | Select-Object -First 40
    throw "编译失败，详见 build\javac.err"
}
Write-Host "编译通过。"

# 3. 资源（toml 必须 UTF-8 无 BOM）
$staging = Join-Path $root "build\staging"
Remove-Item $staging -Recurse -Force -ErrorAction SilentlyContinue
Copy-Item $classes $staging -Recurse -Force
Copy-Item (Join-Path $root "src\main\resources\*") $staging -Recurse -Force
$toml = Join-Path $staging "META-INF\neoforge.mods.toml"
$tomlText = [System.IO.File]::ReadAllText($toml, [System.Text.Encoding]::UTF8).Replace('${mod_version}', $ModVersion)
[System.IO.File]::WriteAllText($toml, $tomlText, (New-Object System.Text.UTF8Encoding($false)))

# 4. 打包
$out = Join-Path $root "build\folkways_ornamentum-$ModVersion.jar"
if (Test-Path $out) { Remove-Item $out -Force }
& $jarTool --create --file $out -C $staging .
Write-Host "已生成：$out"
if ($DeployTo) {
    if (-not (Test-Path $DeployTo)) { New-Item -ItemType Directory -Force -Path $DeployTo | Out-Null }
    Copy-Item $out (Join-Path $DeployTo (Split-Path $out -Leaf)) -Force
    Write-Host "已部署到：$DeployTo"
}
