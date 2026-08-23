<#
.SYNOPSIS
Downloads and inspects Xaero's World Map jars for the current World Mirror branch.

.DESCRIPTION
By default this script downloads the newest Xaero's World Map Modrinth release
matching a legacy branch's `minecraft_version` or the root build's only target.
On the current multi-target branch, pass `-MinecraftVersion` explicitly. It reuses
an existing matching jar, including a `.jar.disabled` copy, instead of downloading
the same file again.

Use `-ListVersions` to list all published Xaero's World Map releases matching
the current Minecraft version and loader. Use `-XaeroVersion` to download a
specific Xaero release. Use `-Disassemble` after selecting a release to automate
the repeatable parts of injection-point inspection:

- writes `fabric.mod.json` from the selected jar
- runs `javap -p -s -c xaero.map.gui.GuiMap`
- extracts the `extractRenderState(...)` invoke list

The generated files are placed under `build/tmp/xaero-inspect` by default and
are intentionally build-cache style artifacts. They should not be committed.

.PARAMETER MinecraftVersion
Minecraft game version to query. Defaults only when the branch declares one target.

.PARAMETER XaeroVersion
Specific Xaero's World Map version number to download, for example `1.41.1`.

.PARAMETER ListVersions
List matching releases and exit without downloading.

.PARAMETER Disassemble
Generate the `javap` inspection files for the selected/downloaded jar.

.PARAMETER InspectOutputDirectory
Directory for disassembly/inspection output.

.PARAMETER AllowAnyGameVersion
Permit selecting a release that does not declare compatibility with
`MinecraftVersion`.
#>
param(
    [string]$MinecraftVersion,
    [string]$Loader = "fabric",
    [string]$ProjectSlug = "xaeros-world-map",
    [string]$OutputDirectory = "run\mods",
    [string]$XaeroVersion,
    [switch]$ListVersions,
    [switch]$Disassemble,
    [string]$InspectOutputDirectory = "build\tmp\xaero-inspect",
    [switch]$AllowAnyGameVersion,
    [switch]$ReplaceExisting
)

$ErrorActionPreference = "Stop"

try {
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
} catch {
    Write-Verbose "Unable to force TLS 1.2; continuing with the current PowerShell defaults."
}

$repoRoot = Split-Path -Parent $PSScriptRoot

function Resolve-RepoPath {
    param([string]$Path)
    if ([System.IO.Path]::IsPathRooted($Path)) {
        return [System.IO.Path]::GetFullPath($Path)
    }
    return [System.IO.Path]::GetFullPath((Join-Path $repoRoot $Path))
}

function Get-GradleProperty {
    param([string]$Name)
    $propertiesPath = Join-Path $repoRoot "gradle.properties"
    if (-not (Test-Path -LiteralPath $propertiesPath)) {
        return $null
    }
    $match = Select-String -LiteralPath $propertiesPath -Pattern "^$([Regex]::Escape($Name))=(.+)$" | Select-Object -First 1
    if ($match) {
        return $match.Matches[0].Groups[1].Value.Trim()
    }
    return $null
}

function Get-SupportedMinecraftVersions {
    $buildFile = Join-Path $repoRoot "build.gradle"
    if (-not (Test-Path -LiteralPath $buildFile)) {
        return @()
    }
    return @(Select-String -LiteralPath $buildFile -Pattern '^\s*"([^"]+)":\s*\[' |
        ForEach-Object { $_.Matches[0].Groups[1].Value })
}

if (-not $MinecraftVersion) {
    $MinecraftVersion = Get-GradleProperty "minecraft_version"
}
if (-not $MinecraftVersion) {
    $supportedVersions = @(Get-SupportedMinecraftVersions)
    if ($supportedVersions.Count -eq 1) {
        $MinecraftVersion = $supportedVersions[0]
    } else {
        throw "This branch has multiple Minecraft targets ($($supportedVersions -join ', ')). Pass -MinecraftVersion explicitly."
    }
}

$outputPath = Resolve-RepoPath $OutputDirectory
New-Item -ItemType Directory -Force -Path $outputPath | Out-Null

$headers = @{
    "User-Agent" = "WorldMirrorDevScript/1.0 (PowerShell; +https://github.com/)"
}

function ConvertTo-UrlJsonArray {
    param([string]$Value)
    return [Uri]::EscapeDataString(('["{0}"]' -f $Value.Replace('\', '\\').Replace('"', '\"')))
}

function Get-ModrinthVersions {
    param(
        [string]$Slug,
        [string]$LoaderName,
        [string]$GameVersion
    )

    $baseUrl = "https://api.modrinth.com/v2/project/$Slug/version"
    $query = "loaders=$(ConvertTo-UrlJsonArray $LoaderName)"
    if ($GameVersion) {
        $query = "$query&game_versions=$(ConvertTo-UrlJsonArray $GameVersion)"
    }

    return @(Invoke-RestMethod -Uri "$baseUrl`?$query" -Headers $headers)
}

function Test-XaeroWorldMapFileName {
    param([string]$Name)
    return $Name -match "(?i)(xaero.*world.*map|world.*map.*xaero).*\.jar(\.disabled)?$"
}

function Get-ActiveJarName {
    param([string]$Name)
    if ($Name.EndsWith(".jar.disabled", [System.StringComparison]::OrdinalIgnoreCase)) {
        return $Name.Substring(0, $Name.Length - ".disabled".Length)
    }
    return $Name
}

function Disable-XaeroWorldMapJar {
    param([System.IO.FileInfo]$File)

    if ($File.Name.EndsWith(".jar.disabled", [System.StringComparison]::OrdinalIgnoreCase)) {
        return
    }

    $disabledPath = "$($File.FullName).disabled"
    if (Test-Path -LiteralPath $disabledPath) {
        Remove-Item -LiteralPath $File.FullName -Force
        return
    }

    Rename-Item -LiteralPath $File.FullName -NewName ($File.Name + ".disabled")
}

function Get-PrimaryFile {
    param($Version)
    $file = @($Version.files | Where-Object { $_.primary } | Select-Object -First 1)
    if ($file.Count -eq 0) {
        $file = @($Version.files | Select-Object -First 1)
    }
    if ($file.Count -eq 0 -or -not $file[0].url) {
        throw "Selected Modrinth version '$($Version.version_number)' has no downloadable file."
    }
    return $file[0]
}

function Test-XaeroVersionMatch {
    param(
        $Version,
        [string]$RequestedVersion
    )
    if ($Version.version_number -eq $RequestedVersion) {
        return $true
    }
    if ($Version.version_number -like "*-$RequestedVersion") {
        return $true
    }
    if ($Version.name -eq $RequestedVersion -or $Version.name -like "*$RequestedVersion*") {
        return $true
    }
    return $false
}

function Get-JavapPath {
    $javaHome = [System.Environment]::GetEnvironmentVariable("JAVA_HOME")
    if (-not $javaHome -and (Get-Command java -ErrorAction SilentlyContinue)) {
        $javaHome = (& java -XshowSettings:properties -version 2>&1 |
            Select-String -Pattern '^\s+java.home = (.+)$' |
            Select-Object -First 1).Matches[0].Groups[1].Value
    }
    if ($javaHome) {
        $candidate = Join-Path $javaHome "bin\javap.exe"
        if (Test-Path -LiteralPath $candidate) {
            return $candidate
        }
        $candidate = Join-Path $javaHome "bin\javap"
        if (Test-Path -LiteralPath $candidate) {
            return $candidate
        }
    }
    $command = Get-Command javap -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }
    throw "Unable to find javap. Install a JDK or set JAVA_HOME."
}

function Export-JarEntry {
    param(
        [string]$JarPath,
        [string]$EntryName,
        [string]$Destination
    )
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::OpenRead($JarPath)
    try {
        $entry = $zip.GetEntry($EntryName)
        if (-not $entry) {
            return
        }
        $stream = $entry.Open()
        try {
            $reader = [System.IO.StreamReader]::new($stream)
            try {
                $reader.ReadToEnd() | Set-Content -Encoding UTF8 -LiteralPath $Destination
            } finally {
                $reader.Dispose()
            }
        } finally {
            $stream.Dispose()
        }
    } finally {
        $zip.Dispose()
    }
}

function Invoke-XaeroDisassembly {
    param(
        [string]$JarPath,
        [string]$DestinationDirectory
    )

    $inspectPath = Resolve-RepoPath $DestinationDirectory
    New-Item -ItemType Directory -Force -Path $inspectPath | Out-Null

    $manifestPath = Join-Path $inspectPath "fabric.mod.json"
    Export-JarEntry -JarPath $JarPath -EntryName "fabric.mod.json" -Destination $manifestPath

    $javap = Get-JavapPath
    $guiMapPath = Join-Path $inspectPath "GuiMap.javap.txt"
    & $javap -classpath $JarPath -p -s -c xaero.map.gui.GuiMap |
        Set-Content -Encoding UTF8 -LiteralPath $guiMapPath

    $lines = Get-Content -LiteralPath $guiMapPath
    $methodStart = ($lines | Select-String -SimpleMatch "public void extractRenderState(net.minecraft.client.gui.GuiGraphicsExtractor, int, int, float);" | Select-Object -First 1).LineNumber
    if ($methodStart) {
        $nextMethod = $lines.Count
        for ($i = $methodStart + 1; $i -le $lines.Count; $i++) {
            if ($lines[$i - 1] -match '^  (public|private|protected).*;') {
                $nextMethod = $i - 1
                break
            }
        }
        $invokeLines = for ($i = $methodStart; $i -le $nextMethod; $i++) {
            if ($lines[$i - 1] -match 'invoke') {
                '{0}: {1}' -f $i, $lines[$i - 1]
            }
        }
        $invokeLines | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $inspectPath "GuiMap.extractRenderState.invokes.txt")
    }

    Write-Host "Disassembly written to: $inspectPath"
    Write-Host "Inspect: $guiMapPath"
}

if (-not $MinecraftVersion -and -not $AllowAnyGameVersion) {
    throw "Unable to determine minecraft_version from gradle.properties. Pass -MinecraftVersion or -AllowAnyGameVersion."
}

$versions = @()
if (-not $AllowAnyGameVersion -and $MinecraftVersion) {
    Write-Host "Querying $Loader Xaero's World Map versions for Minecraft $MinecraftVersion..."
    $versions = Get-ModrinthVersions -Slug $ProjectSlug -LoaderName $Loader -GameVersion $MinecraftVersion
}

if ($versions.Count -eq 0 -and ($AllowAnyGameVersion -or -not $MinecraftVersion)) {
    Write-Host "Querying all $Loader Xaero's World Map versions..."
    $versions = Get-ModrinthVersions -Slug $ProjectSlug -LoaderName $Loader -GameVersion $null
}

if ($versions.Count -eq 0) {
    throw "No Modrinth versions found for project '$ProjectSlug', loader '$Loader', Minecraft '$MinecraftVersion'."
}

$versions = @($versions | Sort-Object { [DateTime]$_.date_published } -Descending)

if ($ListVersions) {
    $versions | ForEach-Object {
        $file = Get-PrimaryFile $_
        "{0,-12} {1:yyyy-MM-dd} {2,-36} {3}" -f $_.version_number, ([DateTime]$_.date_published), $file.filename, (@($_.game_versions) -join ",")
    }
    return
}

if ($XaeroVersion) {
    $version = $versions | Where-Object { Test-XaeroVersionMatch $_ $XaeroVersion } | Select-Object -First 1
    if (-not $version -and -not $AllowAnyGameVersion) {
        $allLoaderVersions = Get-ModrinthVersions -Slug $ProjectSlug -LoaderName $Loader -GameVersion $null
        $candidate = $allLoaderVersions | Where-Object { Test-XaeroVersionMatch $_ $XaeroVersion } | Select-Object -First 1
        if ($candidate) {
            throw "Xaero's World Map '$XaeroVersion' exists but does not declare Minecraft '$MinecraftVersion'. Use -AllowAnyGameVersion to download it anyway."
        }
    }
    if (-not $version) {
        throw "Xaero's World Map version '$XaeroVersion' was not found for loader '$Loader'."
    }
} else {
    $version = $versions | Select-Object -First 1
}

$file = Get-PrimaryFile $version
$destination = Join-Path $outputPath $file.filename
$disabledLatest = "$destination.disabled"

$existingXaeroFiles = Get-ChildItem -LiteralPath $outputPath -File |
    Where-Object { Test-XaeroWorldMapFileName $_.Name }

foreach ($existing in $existingXaeroFiles) {
    $activeName = Get-ActiveJarName $existing.Name
    if ($activeName -ne $file.filename) {
        Disable-XaeroWorldMapJar $existing
    }
}

$downloaded = $false
if ((Test-Path -LiteralPath $destination) -and -not $ReplaceExisting) {
    if (Test-Path -LiteralPath $disabledLatest) {
        Remove-Item -LiteralPath $disabledLatest -Force
    }
    Write-Host "Reusing existing jar: $destination"
} elseif ((Test-Path -LiteralPath $disabledLatest) -and -not $ReplaceExisting) {
    Write-Host "Re-enabling existing disabled jar: $disabledLatest"
    Rename-Item -LiteralPath $disabledLatest -NewName $file.filename
} else {
    if (Test-Path -LiteralPath $disabledLatest) {
        Remove-Item -LiteralPath $disabledLatest -Force
    }
    Write-Host "Downloading $($version.name) ($($version.version_number))..."
    Write-Host "Target: $destination"
    $downloadPath = "$destination.download"
    if (Test-Path -LiteralPath $downloadPath) {
        Remove-Item -LiteralPath $downloadPath -Force
    }
    Invoke-WebRequest -Uri $file.url -Headers $headers -OutFile $downloadPath
    Move-Item -LiteralPath $downloadPath -Destination $destination -Force
    $downloaded = $true
}

if ($Disassemble) {
    Invoke-XaeroDisassembly -JarPath $destination -DestinationDirectory $InspectOutputDirectory
}

$disabledCount = @(Get-ChildItem -LiteralPath $outputPath -File |
    Where-Object {
        (Test-XaeroWorldMapFileName $_.Name) -and
        $_.Name.EndsWith(".jar.disabled", [System.StringComparison]::OrdinalIgnoreCase)
    }).Count

Write-Host "Done."
Write-Host "Version: $($version.version_number)"
Write-Host "Game versions: $(@($version.game_versions) -join ', ')"
Write-Host "File: $destination"
Write-Host "Downloaded: $downloaded"
Write-Host "Disabled old/other Xaero jars: $disabledCount"
