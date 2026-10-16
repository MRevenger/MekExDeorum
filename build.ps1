# Build helper for the MekExDeorum NeoForge project (repo root == Gradle root).
# Works around one environment quirk:
#   JAVA_HOME is not set globally -> point at the local JDK 21.
# GRADLE_USER_HOME is deliberately left at its default (~/.gradle): the caches on
# this machine are already warm, so NeoForge 21.1.250 and the NeoForm artifacts do
# not have to be downloaded again. Set GRADLE_USER_HOME before calling this script
# to relocate it (the legacy project had to, because of a sandbox that no longer
# applies).
# Pass the Gradle arguments as positional arguments after the script, as an array, e.g.
#   pwsh -File build.ps1 @('test','--rerun-tasks')
# Do NOT use -GradleArgs for more than one argument: the parameter is declared with
# ValueFromRemainingArguments, so `-GradleArgs @('clean','build')` binds only 'clean' and
# then fails with "a positional parameter cannot be found that accepts argument 'build'".
# A bare `--flag` is likewise taken for a PowerShell parameter name.
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$GradleArgs = @('build')
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path

$env:JAVA_HOME = 'C:\Program Files\Java\dragonwell-21.0.10.0.10+7-GA'

# Heap must be given to the *launcher* JVM (via GRADLE_OPTS) rather than through
# org.gradle.jvmargs, otherwise Gradle forks a build daemon and the launcher's
# child-process setup is no longer the one doing the work.
#
# file.encoding must match the Windows ANSI code page (936 = GBK here), not UTF-8:
# Gradle hands the test worker's classpath to the JVM through an @argfile written with
# the *build JVM's* default charset, while the JDK launcher decodes that argfile with
# the ANSI code page. A UTF-8 argfile therefore turns every classpath entry under this
# repository's path (which contains the Chinese characters 工作) into a path that does
# not exist, and the test worker dies with ClassNotFoundException. Measured on this
# machine: inline -cp loads the class, an UTF-8 @argfile fails, a GBK @argfile works.
$ansiCodePage = (Get-ItemProperty -Path 'HKLM:\SYSTEM\CurrentControlSet\Control\Nls\CodePage' -ErrorAction SilentlyContinue).ACP
if (-not $ansiCodePage) {
    $ansiCodePage = '936'
}
$buildCharset = if ($ansiCodePage -eq '65001') { 'UTF-8' } else { "cp$ansiCodePage" }
$env:GRADLE_OPTS = "-Xmx4G -Dfile.encoding=$buildCharset -Duser.language=en -Duser.country=US"
Write-Host "==> build JVM charset $buildCharset (ANSI code page $ansiCodePage)" -ForegroundColor DarkGray

# Java ignores the Windows (WinINET) proxy, and on this machine the NeoForged Maven
# and Mojang's repositories are only reachable through it: the direct route times out
# on a bogus DNS answer. Forward the configured proxy to the build JVM, as a
# command-line system property because GRADLE_OPTS only reaches the launcher JVM.
# Nothing is added when no proxy is configured, so the script stays portable.
$proxyArgs = @()
$inetSettings = Get-ItemProperty -Path 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings' -ErrorAction SilentlyContinue
if ($inetSettings -and $inetSettings.ProxyEnable -eq 1 -and $inetSettings.ProxyServer -match '^(?<host>[^;:=]+):(?<port>\d+)$') {
    $proxyHost = $Matches['host']
    $proxyPort = $Matches['port']
    $proxyArgs = @(
        "-Dhttp.proxyHost=$proxyHost", "-Dhttp.proxyPort=$proxyPort",
        "-Dhttps.proxyHost=$proxyHost", "-Dhttps.proxyPort=$proxyPort"
    )
    Write-Host "==> using the system proxy $proxyHost`:$proxyPort for the build JVM" -ForegroundColor DarkGray
}

# Pick a Gradle from the shared wrapper cache. Preference order: any 8.x (the version this project
# was developed against), newest first, then anything else.
$dists = Join-Path $env:USERPROFILE '.gradle\wrapper\dists'
$best = Get-ChildItem $dists -Recurse -Filter 'gradle.bat' -ErrorAction SilentlyContinue | ForEach-Object {
    if ($_.FullName -match 'gradle-(\d+\.\d+(?:\.\d+)?)-bin') {
        [PSCustomObject]@{ Path = $_.FullName; Major = [int]($Matches[1].Split('.')[0]); Version = [version]$Matches[1] }
    }
} | Sort-Object @{ Expression = { if ($_.Major -eq 8) { 0 } else { 1 } } }, @{ Expression = { $_.Version }; Descending = $true } |
    Select-Object -First 1
if (-not $best) {
    throw "No usable Gradle found under $dists. Install one or add a wrapper to the project."
}
$gradle = $best.Path
Write-Host "==> using Gradle $($best.Version) at $gradle" -ForegroundColor DarkGray

$gradleUserHome = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $env:USERPROFILE '.gradle' }
Write-Host "==> GRADLE_USER_HOME=$gradleUserHome" -ForegroundColor DarkGray
Write-Host "==> gradle $($GradleArgs -join ' ') (in $root)" -ForegroundColor Cyan

Push-Location $root
try {
    & $gradle @GradleArgs @proxyArgs --no-daemon
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
