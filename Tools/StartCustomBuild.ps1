# SPDX-FileCopyrightText: 2026 ModSeeker
# SPDX-License-Identifier: AGPL-3.0-or-later

param(
    [string]$RepositoryRoot = (Join-Path $PSScriptRoot '..\..'),
    [string]$OutputDirectory = '',
    [string]$JdkDirectory = '',
    [string]$OpenSslDirectory = '',
    [string]$ClangCl = '',
    [string]$VcTools = '',
    [string]$WindowsSdk = '',
    [string]$WindowsSdkVersion = '',
    [switch]$UseMsvc,
    [switch]$InitializeKeysOnly,
    [switch]$Offline
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Add-Type -AssemblyName System.IO.Compression.FileSystem

# Toolchain discovery
function Get-UniqueExistingDirectory([string[]]$Candidates, [string]$RequiredChild, [string]$Name) {
    $seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($candidate in $Candidates) {
        if ([string]::IsNullOrWhiteSpace($candidate)) { continue }
        try { $full = [IO.Path]::GetFullPath($candidate) } catch { continue }
        if (-not $seen.Add($full)) { continue }
        if (Test-Path -LiteralPath (Join-Path $full $RequiredChild)) { return $full }
    }
    throw "$Name was not found; provide its explicit parameter"
}

function Find-Jdk([string]$Requested) {
    $commandJava = Get-Command java.exe -ErrorAction SilentlyContinue
    $commandHome = if ($commandJava) { Split-Path -Parent (Split-Path -Parent $commandJava.Source) } else { '' }
    $zulu = if (Test-Path -LiteralPath 'C:\Program Files\Zulu') {
        @(Get-ChildItem -LiteralPath 'C:\Program Files\Zulu' -Directory -Filter 'zulu-25*' -ErrorAction SilentlyContinue |
                Sort-Object Name -Descending | ForEach-Object FullName)
    } else { @() }
    $candidates = @($Requested, $env:HIDDER_JDK_DIR, $commandHome, $env:JAVA_HOME) + $zulu
    foreach ($candidate in $candidates) {
        if (-not $candidate -or -not (Test-Path -LiteralPath (Join-Path $candidate 'bin\javac.exe'))) { continue }
        $jdk = [IO.Path]::GetFullPath($candidate)
        $version = (& (Join-Path $jdk 'bin\java.exe') -version 2>&1) -join "`n"
        if ($version -match '(?m)version "25(?:\.|\")') { return $jdk }
    }
    throw 'JDK 25 was not found; provide -JdkDirectory'
}

function Find-OpenSsl([string]$Requested) {
    return Get-UniqueExistingDirectory @($Requested, $env:HIDDER_OPENSSL_DIR,
            'C:\Program Files\OpenSSL-Win64') 'lib\VC\x64\MT\libcrypto_static.lib' 'OpenSSL x64 static library'
}

function Find-WindowsSdkVersion([string]$SdkRoot, [string]$Requested) {
    if ($Requested) {
        if (Test-Path -LiteralPath (Join-Path $SdkRoot "Include\$Requested\ucrt")) { return $Requested }
        throw "Windows SDK version is unavailable: $Requested"
    }
    $versions = @(Get-ChildItem -LiteralPath (Join-Path $SdkRoot 'Include') -Directory -ErrorAction SilentlyContinue |
            Sort-Object Name -Descending)
    foreach ($version in $versions) {
        if ((Test-Path -LiteralPath (Join-Path $version.FullName 'ucrt')) -and
                (Test-Path -LiteralPath (Join-Path $version.FullName 'um')) -and
                (Test-Path -LiteralPath (Join-Path $SdkRoot "Lib\$($version.Name)\ucrt\x64"))) {
            return $version.Name
        }
    }
    throw 'A usable Windows SDK version was not found'
}

function Find-MsvcTools([string]$Requested) {
    $candidates = @($Requested, $env:HIDDER_MSVC_DIR)
    foreach ($root in @('C:\Program Files\Microsoft Visual Studio', 'C:\Program Files (x86)\Microsoft Visual Studio')) {
        if (Test-Path -LiteralPath $root) {
            $candidates += @(Get-ChildItem -Path (Join-Path $root '*\*\VC\Tools\MSVC\*') -Directory `
                    -ErrorAction SilentlyContinue | Sort-Object Name -Descending | ForEach-Object FullName)
        }
    }
    return Get-UniqueExistingDirectory $candidates 'bin\Hostx64\x64\cl.exe' 'MSVC x64 tools'
}

function Get-SourceValue([string]$Text, [string]$Pattern, [string]$Name) {
    $matches = [regex]::Matches($Text, $Pattern)
    if ($matches.Count -ne 1) { throw "$Name is missing or ambiguous" }
    return $matches[0].Groups[1].Value
}

function Read-ArchiveEntry([IO.Compression.ZipArchive]$Archive, [string]$Path, [int64]$MaximumBytes) {
    $matches = @($Archive.Entries | Where-Object FullName -CEQ $Path)
    if ($matches.Count -ne 1 -or $matches[0].FullName.EndsWith('/') -or
            $matches[0].Length -le 0 -or $matches[0].Length -gt $MaximumBytes) {
        throw "Missing, ambiguous, or invalid archive entry: $Path"
    }
    $entryStream = $matches[0].Open()
    $memory = [IO.MemoryStream]::new()
    try {
        $entryStream.CopyTo($memory)
        if ($memory.Length -ne $matches[0].Length) { throw "Incomplete archive entry: $Path" }
        return $memory.ToArray()
    } finally {
        $memory.Dispose()
        $entryStream.Dispose()
    }
}

function Get-ClientReportKeyFingerprint([string]$PublicKey) {
    try { $bytes = [Convert]::FromBase64String($PublicKey) } catch { throw 'Client public key is invalid' }
    $sha256 = [Security.Cryptography.SHA256]::Create()
    try {
        return ($sha256.ComputeHash($bytes) | ForEach-Object { $_.ToString('x2') }) -join ''
    } finally {
        $sha256.Dispose()
    }
}

function Test-MatchingClientReportIdentity([string]$ServerJar, [string]$ClientJar, [string]$PublicKey) {
    $serverArchive = [IO.Compression.ZipFile]::OpenRead($ServerJar)
    try {
        $classBytes = Read-ArchiveEntry $serverArchive `
                'com/example/modseeker/protocol/ProtocolConstants.class' 1048576
    } finally {
        $serverArchive.Dispose()
    }
    if ($classBytes.Length -lt 8 -or $classBytes[0] -ne 0xCA -or $classBytes[1] -ne 0xFE -or
            $classBytes[2] -ne 0xBA -or $classBytes[3] -ne 0xBE) {
        throw 'ModSeeker protocol class is invalid'
    }
    $compiledText = [Text.Encoding]::ASCII.GetString($classBytes)
    if ([regex]::Matches($compiledText, [regex]::Escape($PublicKey)).Count -ne 1) {
        throw 'Built ModSeeker does not contain the configured client public key exactly once'
    }
    $fingerprint = Get-ClientReportKeyFingerprint $PublicKey
    $clientArchive = [IO.Compression.ZipFile]::OpenRead($ClientJar)
    try {
        $dllBytes = Read-ArchiveEntry $clientArchive 'hidder_vault.dll' 16777216
    } finally {
        $clientArchive.Dispose()
    }
    $identities = [regex]::Matches([Text.Encoding]::ASCII.GetString($dllBytes),
            'MODSEEKER_REPORT_KEY_SHA256=([0-9a-f]{64})')
    if ($identities.Count -ne 1 -or $identities[0].Groups[1].Value -cne $fingerprint) {
        throw 'Built ModSeeker and Hidder report-key identities do not match'
    }
    return [pscustomobject]@{
        javaFeature = ([int]$classBytes[6] * 256 + [int]$classBytes[7]) - 44
        fingerprint = $fingerprint
    }
}

$root = (Resolve-Path -LiteralPath $RepositoryRoot).Path
$modSeeker = Join-Path $root 'ModSeeker'
$hidder = Join-Path $root 'Hidder'
$protocolSource = Join-Path $modSeeker 'src\main\java\com\example\modseeker\protocol\ProtocolConstants.java'
$keyGenerator = Join-Path $modSeeker 'Tools\ClientReportKeyGenerator.java'
$publicDeclaration = Join-Path $modSeeker 'Tools\CLIENT_PUBLIC_KEY.txt'
$privateInclude = Join-Path $hidder 'native\client_keys.generated.inc'
foreach ($required in @($protocolSource, $keyGenerator, (Join-Path $modSeeker 'gradlew.bat'),
        (Join-Path $hidder 'gradlew.bat'))) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "Repository input is missing: $required" }
}

$jdk = Find-Jdk $JdkDirectory

# Client identity initialization
$keysInitialized = $false
if (-not (Test-Path -LiteralPath $privateInclude -PathType Leaf)) {
    if (Test-Path -LiteralPath $publicDeclaration) {
        throw 'The public-key declaration exists without the private include; refusing to replace identity state'
    }
    $keyClasses = Join-Path ([IO.Path]::GetTempPath()) ('modseeker-keygen-' + [guid]::NewGuid().ToString('N'))
    [IO.Directory]::CreateDirectory($keyClasses) | Out-Null
    try {
        & (Join-Path $jdk 'bin\javac.exe') -d $keyClasses $keyGenerator
        if ($LASTEXITCODE -ne 0) { throw "Key generator compilation failed: $LASTEXITCODE" }
        Push-Location (Join-Path $modSeeker 'Tools')
        try {
            & (Join-Path $jdk 'bin\java.exe') -cp $keyClasses ClientReportKeyGenerator $privateInclude $publicDeclaration
            if ($LASTEXITCODE -ne 0) { throw "Key generation failed: $LASTEXITCODE" }
        } finally {
            Pop-Location
        }
    } finally {
        $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
        $resolvedTemp = [IO.Path]::GetFullPath($keyClasses)
        if ($resolvedTemp.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase) -and
                (Test-Path -LiteralPath $resolvedTemp)) {
            Remove-Item -LiteralPath $resolvedTemp -Recurse -Force
        }
    }
    $declarationText = [IO.File]::ReadAllText($publicDeclaration, [Text.UTF8Encoding]::new($false, $true)).Trim()
    $publicKey = Get-SourceValue $declarationText 'CLIENT_PUBLIC_KEY\s*=\s*"([A-Za-z0-9+/]+={0,2})"' `
            'generated client public key'
    $protocolText = [IO.File]::ReadAllText($protocolSource, [Text.UTF8Encoding]::new($false, $true))
    $keyPattern = 'public\s+static\s+final\s+String\s+CLIENT_PUBLIC_KEY\s*=\s*"[A-Za-z0-9+/]+={0,2}";'
    if ([regex]::Matches($protocolText, $keyPattern).Count -ne 1) {
        throw 'ModSeeker client public-key declaration is missing or ambiguous'
    }
    $updated = [regex]::Replace($protocolText, $keyPattern,
            "public static final String CLIENT_PUBLIC_KEY = `"$publicKey`";")
    [IO.File]::WriteAllText($protocolSource, $updated, [Text.UTF8Encoding]::new($false))
    $keysInitialized = $true
} elseif (Test-Path -LiteralPath $publicDeclaration -PathType Leaf) {
    $declarationText = [IO.File]::ReadAllText($publicDeclaration, [Text.UTF8Encoding]::new($false, $true))
    $protocolText = [IO.File]::ReadAllText($protocolSource, [Text.UTF8Encoding]::new($false, $true))
    $generatedPublic = Get-SourceValue $declarationText 'CLIENT_PUBLIC_KEY\s*=\s*"([A-Za-z0-9+/]+={0,2})"' `
            'generated client public key'
    $sourcePublic = Get-SourceValue $protocolText 'CLIENT_PUBLIC_KEY\s*=\s*"([A-Za-z0-9+/]+={0,2})"' `
            'ModSeeker client public key'
    if ($generatedPublic -cne $sourcePublic) { throw 'Existing Hidder and ModSeeker key declarations do not match' }
}

if ($InitializeKeysOnly) {
    [pscustomobject]@{
        complete = $true
        keysInitialized = $keysInitialized
        privateKeyPrinted = $false
        repository = $root
    } | ConvertTo-Json -Compress
    exit 0
}

# Native and Java builds
$openSsl = Find-OpenSsl $OpenSslDirectory
$clangPath = if ($ClangCl) { [IO.Path]::GetFullPath($ClangCl) } elseif ($env:HIDDER_CLANG_CL) {
    [IO.Path]::GetFullPath($env:HIDDER_CLANG_CL)
} else { '' }
$useOllvm = -not $UseMsvc -and $clangPath -and (Test-Path -LiteralPath $clangPath -PathType Leaf)
if ($useOllvm) {
    $vcToolsPath = Get-UniqueExistingDirectory @($VcTools, $env:HIDDER_VCTOOLS) `
            'include' 'MSVC tools for clang-cl'
    $sdkPath = Get-UniqueExistingDirectory @($WindowsSdk, $env:HIDDER_WINSDK,
            'C:\Program Files (x86)\Windows Kits\10') 'Include' 'Windows SDK'
    $compilerKind = 'ollvm-clang-cl'
    $compileScript = Join-Path $hidder 'native\compile_vault_ollvm.bat'
    $env:HIDDER_CLANG_CL = $clangPath
    $env:HIDDER_VCTOOLS = $vcToolsPath
} else {
    $vcToolsPath = Find-MsvcTools $VcTools
    $sdkPath = Get-UniqueExistingDirectory @($WindowsSdk, $env:HIDDER_WINSDK,
            'C:\Program Files (x86)\Windows Kits\10') 'Include' 'Windows SDK'
    $compilerKind = 'msvc'
    $compileScript = Join-Path $hidder 'native\compile_vault.bat'
    $env:HIDDER_MSVC_DIR = $vcToolsPath
}
$sdkVersion = Find-WindowsSdkVersion $sdkPath $(if ($WindowsSdkVersion) { $WindowsSdkVersion } `
        else { $env:HIDDER_WINSDK_VER })
$env:HIDDER_JDK_DIR = $jdk
$env:JAVA_HOME = $jdk
$env:HIDDER_OPENSSL_DIR = $openSsl
$env:HIDDER_WINSDK = $sdkPath
$env:HIDDER_WINSDK_VER = $sdkVersion
$env:HIDDER_NONINTERACTIVE = '1'
$env:HIDDER_PRIVATE_INPUT_DIR = Split-Path -Parent $privateInclude
$env:HIDDER_OFFLINE = if ($Offline) { '1' } else { $null }

& $compileScript
if ($LASTEXITCODE -ne 0) { throw "Native Hidder build failed: $LASTEXITCODE" }

$gradleOptions = @('--no-build-cache','--rerun-tasks','--no-daemon','--console=plain')
if ($Offline) { $gradleOptions += '--offline' }
& (Join-Path $modSeeker 'gradlew.bat') -p $modSeeker clean jar @gradleOptions
if ($LASTEXITCODE -ne 0) { throw "ModSeeker build failed: $LASTEXITCODE" }
$hidderBuild = [IO.File]::ReadAllText((Join-Path $hidder 'build.gradle'), [Text.UTF8Encoding]::new($false, $true))
$hidderJarTask = if ($hidderBuild.Contains("id 'net.fabricmc.fabric-loom-remap'")) { 'remapJar' } else { 'jar' }
& (Join-Path $hidder 'gradlew.bat') -p $hidder clean $hidderJarTask verifyPackagedClassFingerprints @gradleOptions
if ($LASTEXITCODE -ne 0) { throw "Hidder build failed: $LASTEXITCODE" }

# Artifact pairing and output
$serverJars = @(Get-ChildItem -LiteralPath (Join-Path $modSeeker 'build\libs') -File -Filter '*.jar' |
        Where-Object Name -NotLike '*-sources.jar')
$clientJars = @(Get-ChildItem -LiteralPath (Join-Path $hidder 'build\libs') -File -Filter '*.jar' |
        Where-Object Name -NotLike '*-sources.jar')
if ($serverJars.Count -ne 1 -or $clientJars.Count -ne 1) { throw 'Expected exactly one binary JAR from each project' }

$protocolText = [IO.File]::ReadAllText($protocolSource, [Text.UTF8Encoding]::new($false, $true))
$protocol = Get-SourceValue $protocolText 'HIDDER_PROTOCOL_VERSION\s*=\s*"([A-Za-z0-9._:+-]+)"' 'protocol'
$sourcePublic = Get-SourceValue $protocolText 'CLIENT_PUBLIC_KEY\s*=\s*"([A-Za-z0-9+/]+={0,2})"' `
        'ModSeeker client public key'
$pairIdentity = Test-MatchingClientReportIdentity $serverJars[0].FullName $clientJars[0].FullName $sourcePublic

$output = if ($OutputDirectory) { [IO.Path]::GetFullPath($OutputDirectory) } else {
    Join-Path $root 'dist\custom-pair'
}
[IO.Directory]::CreateDirectory($output) | Out-Null
$serverOutput = Join-Path $output $serverJars[0].Name
$clientOutput = Join-Path $output $clientJars[0].Name
foreach ($path in @($serverOutput, $clientOutput)) {
    if (Test-Path -LiteralPath $path) { throw "Output already exists: $path" }
}
Copy-Item -LiteralPath $serverJars[0].FullName -Destination $serverOutput
Copy-Item -LiteralPath $clientJars[0].FullName -Destination $clientOutput

[pscustomobject]@{
    complete = $true
    keysInitialized = $keysInitialized
    privateKeyPrinted = $false
    nativeCompiler = $compilerKind
    clientPackaging = $hidderJarTask
    javaFeature = $pairIdentity.javaFeature
    protocol = $protocol
    modSeekerJar = $serverOutput
    hidderJar = $clientOutput
    reportKeyPairMatches = $true
} | ConvertTo-Json -Compress
