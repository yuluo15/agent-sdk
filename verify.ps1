# Agent SDK Local Pre-Commit & Quality Gate Script (JDK 21)
# Ensures 0 test failures, clean compile, and 0 secret leaks before commit.

param (
    [switch]$SkipClean = $false
)

$ErrorActionPreference = "Stop"

Write-Host "==================================================" -ForegroundColor Cyan
Write-Host " Agent SDK Local Verification Gate (JDK 21) " -ForegroundColor Cyan
Write-Host "==================================================" -ForegroundColor Cyan

# 1. Environment & JDK 21 Check
if (-not $env:JAVA_HOME -or -not (Test-Path "$env:JAVA_HOME\bin\javac.exe")) {
    if (Test-Path "D:\Java\jdk-21.0.8") {
        $env:JAVA_HOME = "D:\Java\jdk-21.0.8"
    }
}

Write-Host "[1/3] Checking JDK 21 environment..." -ForegroundColor Yellow
$prevEAP = $ErrorActionPreference
$ErrorActionPreference = "Continue"
$javaVer = cmd.exe /c "`"$env:JAVA_HOME\bin\java.exe`" -version 2>&1"
$ErrorActionPreference = $prevEAP

if ($javaVer -notmatch "21\.") {
    Write-Error "JAVA_HOME ($env:JAVA_HOME) must point to JDK 21. Actual: $javaVer"
    exit 1
}
Write-Host " -> JDK 21 detected: $env:JAVA_HOME" -ForegroundColor Green

# 2. Secret & Credential Pre-Scan
Write-Host "[2/3] Scanning for potential unmasked API keys..." -ForegroundColor Yellow
$leakFound = $false
Get-ChildItem -Path . -Recurse -File -Exclude target, .git, .idea, *.class | ForEach-Object {
    $content = Get-Content $_.FullName -Raw -ErrorAction SilentlyContinue
    if ($content -match "sk-[a-zA-Z0-9]{32,}") {
        Write-Host " [WARNING] Potential secret detected in: $($_.FullName)" -ForegroundColor Red
        $leakFound = $true
    }
}

if ($leakFound) {
    Write-Error "Secret scan failed: Detected potential API keys in source files. Please mask or remove them."
    exit 1
}
Write-Host " -> Secret scan passed: 0 credentials detected." -ForegroundColor Green

# 3. Maven Reactor Build & Full Test Suite
Write-Host "[3/3] Executing Maven reactor build and test suites..." -ForegroundColor Yellow
$mvnCmd = if ($SkipClean) { "mvn -B test" } else { "mvn -B clean test" }
Write-Host " -> Running: $mvnCmd" -ForegroundColor Gray

$ErrorActionPreference = "Continue"
cmd.exe /c "$mvnCmd"
$exitCode = $LASTEXITCODE

if ($exitCode -ne 0) {
    Write-Host "Maven build or test failed with exit code $exitCode" -ForegroundColor Red
    exit $exitCode
}

Write-Host ""
Write-Host "==================================================" -ForegroundColor Green
Write-Host " [SUCCESS] All checks passed! 100% tests green. " -ForegroundColor Green
Write-Host "==================================================" -ForegroundColor Green
exit 0
