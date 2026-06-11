param(
    [string]$Root = (Split-Path $PSScriptRoot -Parent)
)

$ErrorActionPreference = 'Stop'
$versionFile = Join-Path $PSScriptRoot 'version.txt'

if (-not (Test-Path $versionFile)) {
    Write-Error "version.txt not found: $versionFile"
}

$release = $null
$newRelease = $null
$lines = Get-Content -LiteralPath $versionFile -Encoding UTF8
for ($i = 0; $i -lt $lines.Count; $i++) {
    if ($lines[$i] -match '^\s*comfort\.release\s*=\s*(\d+\.\d+\.\d+\.\d+)\s*(#.*)?$') {
        $release = $Matches[1]
        $suffix = ''
        if ($Matches[2]) { $suffix = " $($Matches[2].Trim())" }
        $parts = $release.Split('.')
        if ($parts.Count -ne 4) {
            Write-Error "comfort.release must have 4 segments (e.g. 1.0.0.13), got: $release"
        }
        $last = [int]$parts[3]
        $parts[3] = ($last + 1).ToString()
        $newRelease = $parts -join '.'
        $lines[$i] = "comfort.release=$newRelease$suffix"
        break
    }
}

if (-not $release) {
    Write-Error "comfort.release not found in version.txt"
}

function Set-Utf8NoBomContent {
    param(
        [string]$Path,
        [string[]]$ContentLines
    )
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllLines($Path, $ContentLines, $utf8NoBom)
}

Set-Utf8NoBomContent -Path $versionFile -ContentLines $lines
Write-Host "Bumped comfort.release: $release -> $newRelease"

& (Join-Path $PSScriptRoot 'sync-version.ps1') -Root $Root
