param(
    [string] $AppImage = (Join-Path $PSScriptRoot '..\dist\DungeonMasterMapTool')
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$root = Split-Path $PSScriptRoot -Parent
$AppImage = (Resolve-Path -LiteralPath $AppImage).Path

function Assert-File([string] $Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Package is missing required file: $Path"
    }
}

foreach ($file in @(
    'DungeonMasterMapTool.exe',
    'DungeonMasterMapTool-Compatibility.exe',
    'README.md',
    'runtime\bin\server\jvm.dll',
    'runtime\lib\modules',
    'app\DungeonMasterMapTool.cfg',
    'app\DungeonMasterMapTool-Compatibility.cfg',
    'app\DungeonMasterMapTool.jar'
)) {
    Assert-File (Join-Path $AppImage $file)
}

$categories = @('ancestries', 'armor', 'classes', 'communities', 'consumables',
    'domain-cards', 'items', 'transformations', 'weapons')
foreach ($category in $categories) {
    $relative = "dmmap-daggerheart\$category.json"
    $source = Join-Path $root $relative
    $packaged = Join-Path $AppImage "app\$relative"
    Assert-File $source
    Assert-File $packaged
    if ((Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash -ne
        (Get-FileHash -LiteralPath $packaged -Algorithm SHA256).Hash) {
        throw "Packaged catalog differs from source: $relative"
    }
    $catalog = Get-Content -LiteralPath $packaged -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($catalog.format -ne 'dmmap-daggerheart' -or $catalog.category -ne $category -or $catalog.version -ne 2 -or
        @($catalog.entries).Count -eq 0) {
        throw "Packaged catalog is invalid or empty: $relative"
    }
}

$sourceRules = Join-Path $root 'dmmap-daggerheart\rules'
if (Test-Path -LiteralPath $sourceRules) {
    if (-not (Test-Path -LiteralPath $sourceRules -PathType Container)) {
        throw "Reserved rules path is not a directory: $sourceRules"
    }
    foreach ($rule in @(
        @{ File = 'recipes.json'; Format = 'dmmap-daggerheart-rules' },
        @{ File = 'ruleset.json'; Format = 'dmmap-daggerheart-ruleset' }
    )) {
        $relative = "dmmap-daggerheart\rules\$($rule.File)"
        $source = Join-Path $root $relative
        $packaged = Join-Path $AppImage "app\$relative"
        Assert-File $source
        Assert-File $packaged
        if ((Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash -ne
            (Get-FileHash -LiteralPath $packaged -Algorithm SHA256).Hash) {
            throw "Packaged shared rules differ from source: $relative"
        }
        $document = Get-Content -LiteralPath $packaged -Raw -Encoding UTF8 | ConvertFrom-Json
        if ($document.format -ne $rule.Format -or $document.version -ne 2) {
            throw "Packaged shared rules have an invalid format or version: $relative"
        }
    }
}

$archive = [System.IO.Compression.ZipFile]::OpenRead((Join-Path $AppImage 'app\DungeonMasterMapTool.jar'))
$sha = [System.Security.Cryptography.SHA256]::Create()
try {
    $resources = (Resolve-Path -LiteralPath (Join-Path $root 'src\main\resources')).Path
    $files = @(Get-ChildItem -LiteralPath $resources -Recurse -File)
    foreach ($file in $files) {
        $relative = $file.FullName.Substring($resources.Length + 1).Replace('\', '/')
        $entry = $archive.GetEntry($relative)
        if ($null -eq $entry) {
            throw "Packaged JAR is missing application resource: $relative"
        }
        $stream = $entry.Open()
        try {
            $actual = [BitConverter]::ToString($sha.ComputeHash($stream)).Replace('-', '')
        } finally {
            $stream.Dispose()
        }
        if ($actual -ne (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash) {
            throw "Packaged resource differs from source: $relative"
        }
    }
    if (Test-Path -LiteralPath $sourceRules -PathType Container) {
        foreach ($rule in @('recipes.json', 'ruleset.json')) {
            $entry = $archive.GetEntry("dmmt/automation/$rule")
            if ($null -eq $entry) { throw "Packaged JAR is missing bundled shared rules: $rule" }
            $stream = $entry.Open()
            try {
                $actual = [BitConverter]::ToString($sha.ComputeHash($stream)).Replace('-', '')
            } finally {
                $stream.Dispose()
            }
            if ($actual -ne (Get-FileHash -LiteralPath (Join-Path $sourceRules $rule) -Algorithm SHA256).Hash) {
                throw "Bundled shared rules differ from source: $rule"
            }
        }
    }
    foreach ($required in @('web/index.html', 'dmmt/icon.png', 'dmmt/ui/dark.css',
        'condition-icons/cond_invisible_round.png', 'condition-icons/cond_restrained_round.png',
        'condition-icons/cond_concentrating_round.png', 'web/condition-icons/cond_bleeding_round.png')) {
        if ($null -eq $archive.GetEntry($required)) {
            throw "Packaged JAR is missing required UI resource: $required"
        }
    }
    $reader = [System.IO.StreamReader]::new($archive.GetEntry('web/index.html').Open())
    try {
        $html = $reader.ReadToEnd()
    } finally {
        $reader.Dispose()
    }
    $references = [regex]::Matches($html, '(?:src|href)=["''](/[^"'']+)["'']')
    if ($references.Count -eq 0) {
        throw 'Packaged browser HTML has no asset references.'
    }
    foreach ($reference in $references) {
        $asset = 'web' + $reference.Groups[1].Value
        if ($null -eq $archive.GetEntry($asset)) {
            throw "Packaged HTML references a missing asset: $asset"
        }
    }
    Write-Output "Package resources verified: $($categories.Count) catalogs, $($files.Count) classpath resources, $($references.Count) HTML asset references, both EXEs and bundled runtime."
} finally {
    $sha.Dispose()
    $archive.Dispose()
}
