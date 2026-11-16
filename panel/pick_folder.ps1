param(
    [string]$Title = "选择蓝图目录",
    [string]$Initial = "",
    [Parameter(Mandatory = $true)][string]$OutFile
)
$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing

[System.Windows.Forms.Application]::EnableVisualStyles()
$dialog = New-Object System.Windows.Forms.FolderBrowserDialog
$dialog.Description = $Title
$dialog.ShowNewFolderButton = $true
if ($Initial -and (Test-Path -LiteralPath $Initial -PathType Container)) {
    $dialog.SelectedPath = $Initial
}

$result = $dialog.ShowDialog()

