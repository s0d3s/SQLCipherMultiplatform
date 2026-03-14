param(
    [Parameter(Mandatory = $false)]
    [string]$Ref
)

$ErrorActionPreference = 'Stop'

$root = Resolve-Path (Join-Path $PSScriptRoot "..")
$upstream = Join-Path $root "third_party/sqlcipher/upstream"
$outDir = Join-Path $root "third_party/sqlcipher"

if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
    throw "git is required"
}

if (-not (Test-Path $upstream) -or -not (Test-Path (Join-Path $upstream ".git"))) {
    throw "SQLCipher upstream submodule is missing. Run: git submodule update --init --recursive third_party/sqlcipher/upstream"
}

if (-not (Get-Command nmake -ErrorAction SilentlyContinue)) {
    throw "nmake is required to regenerate sqlite3.c/sqlite3.h (run from a Visual Studio Developer Command Prompt/PowerShell)"
}

if ($Ref) {
    Write-Host "[sqlcipher] checking out ref: $Ref"
    & git -C $upstream fetch --tags --prune
    & git -C $upstream checkout $Ref
}

Write-Host "[sqlcipher] generating amalgamation from $upstream"
Push-Location $upstream
try {
    & nmake /f Makefile.msc sqlite3.c
} finally {
    Pop-Location
}

Copy-Item (Join-Path $upstream "sqlite3.c") (Join-Path $outDir "sqlite3.c") -Force
Copy-Item (Join-Path $upstream "sqlite3.h") (Join-Path $outDir "sqlite3.h") -Force

$commit = (& git -C $upstream rev-parse HEAD).Trim()
$describe = (& git -C $upstream describe --tags --always --dirty 2>$null)
if (-not $describe) {
    $describe = $commit
}

$generatedAt = [DateTime]::UtcNow.ToString("yyyy-MM-ddTHH:mm:ssZ")

@"
SQLCipher upstream commit: $commit
SQLCipher upstream describe: $describe
Generated at (UTC): $generatedAt
Generator: scripts/update-sqlcipher-amalgamation.ps1
Command: nmake /f Makefile.msc sqlite3.c
"@ | Set-Content -Path (Join-Path $outDir "AMALGAMATION_INFO.txt") -Encoding UTF8

Write-Host "[sqlcipher] updated:"
Write-Host "  - $(Join-Path $outDir 'sqlite3.c')"
Write-Host "  - $(Join-Path $outDir 'sqlite3.h')"
Write-Host "  - $(Join-Path $outDir 'AMALGAMATION_INFO.txt')"
