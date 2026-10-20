# Copies the recognition model and factor list from umacapture's modules folder into the app assets.
# Run after umacapture on the PC has updated its modules (new factors), then rebuild the APK.
#   powershell -ExecutionPolicy Bypass -File update-modules.ps1 [-Modules <modules folder>]
param([string]$Modules = "$env:APPDATA\umasagashi\umacapture\modules")
$ErrorActionPreference = 'Stop'
$assets = Join-Path $PSScriptRoot 'app\src\main\assets'

# modules path -> asset name. factor.onnx / factor_rank.onnx / skill.onnx / labels.json must always move together:
# each model's output index is a position in labels.json (factor.name, skill.name).
$files = [ordered]@{
    'factor\prediction.onnx'      = 'factor.onnx'
    'factor_rank\prediction.onnx' = 'factor_rank.onnx'
    'labels.json'                 = 'labels.json'
    'factor_info.json'            = 'factor_info.json'
    'factor_tag.json'             = 'factor_tag.json'
    'skill\prediction.onnx'       = 'skill.onnx'
    'skill_info.json'             = 'skill_info.json'
    'skill_tag.json'              = 'skill_tag.json'
    'license.md'                  = 'umacapture-modules-LICENSE.md'
    'version_info.json'           = 'umacapture-modules-version.json'
}
foreach ($source in $files.Keys) {
    if (-not (Test-Path -LiteralPath (Join-Path $Modules $source))) { throw "modules に $source がありません: $Modules" }
}

$before = (Get-Content -LiteralPath (Join-Path $assets 'labels.json') -Raw -Encoding UTF8 | ConvertFrom-Json).'factor.name'.Count
foreach ($source in $files.Keys) {
    $from = Join-Path $Modules $source; $to = Join-Path $assets $files[$source]
    $changed = -not (Test-Path -LiteralPath $to) -or (Get-FileHash -LiteralPath $from).Hash -ne (Get-FileHash -LiteralPath $to).Hash
    Copy-Item -LiteralPath $from -Destination $to -Force
    Write-Output ("{0,-32} {1}" -f $files[$source], $(if ($changed) { '更新' } else { '変更なし' }))
}
$after = (Get-Content -LiteralPath (Join-Path $assets 'labels.json') -Raw -Encoding UTF8 | ConvertFrom-Json).'factor.name'.Count
$version = Get-Content -LiteralPath (Join-Path $assets 'umacapture-modules-version.json') -Raw -Encoding UTF8 | ConvertFrom-Json
Write-Output "因子数: $before -> $after（recognizer_version $($version.recognizer_version)）"
Write-Output '次に build-debug.ps1 でビルドすると、FactorCatalogTest がファイル同士の整合を確認します。'
