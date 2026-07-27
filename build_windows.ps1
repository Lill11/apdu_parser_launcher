param()

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $ProjectRoot

$VenvDir = Join-Path $ProjectRoot ".venv"
$VenvPython = Join-Path $VenvDir "Scripts\python.exe"
$Requirements = Join-Path $ProjectRoot "requirements.txt"
$SpecFile = Join-Path $ProjectRoot "APDUParser.spec"
$ParserJar = Join-Path $ProjectRoot "parser\apdu-parser.jar"
$PluginApiJar = Join-Path $ProjectRoot "parser\plugin-api.jar"
$DistRoot = Join-Path $ProjectRoot "dist"
$PortableRoot = Join-Path $DistRoot "APDUParser"
$PortableZip = Join-Path $DistRoot "APDUParser-Portable.zip"
$BuildRoot = Join-Path $ProjectRoot "build\packaging"
$ParserBuildRoot = Join-Path $ProjectRoot "build\parser"
$SamplePluginRoot = Join-Path $ProjectRoot "build\sample-parser-plugin"
$RuntimeRoot = Join-Path $BuildRoot "runtime"
$SmokeRoot = Join-Path $BuildRoot "smoke"
$PytestTemp = Join-Path $BuildRoot "pytest-temp"
$PythonTemp = Join-Path $BuildRoot "tmp"
$ExamplesRoot = Join-Path $ProjectRoot "examples"
$SamplePluginJar = Join-Path $ExamplesRoot "sample-parser-plugin\build\sample-parser-plugin.jar"
$SamplePluginSource = Join-Path $ExamplesRoot "sample-parser-plugin\src\example\SamplePcscPlugin.java"
$SampleSourcePluginFile = Join-Path $ExamplesRoot "sample-source-parser\SourcePcscPlugin.java"

function Write-Step($Message) {
    Write-Host ""
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Assert-Path($PathValue, $Description) {
    if (-not (Test-Path $PathValue)) {
        throw "$Description missing: $PathValue"
    }
}

function Invoke-WithRetry {
    param(
        [Parameter(Mandatory = $true)][scriptblock]$Operation,
        [string]$Description = "operation",
        [int]$Attempts = 6,
        [int]$DelayMilliseconds = 300
    )

    $lastError = $null
    for ($attempt = 1; $attempt -le $Attempts; $attempt++) {
        try {
            & $Operation
            return
        }
        catch {
            $lastError = $_
            if ($attempt -eq $Attempts) {
                throw "Failed during $Description after $Attempts attempts. $($_.Exception.Message)"
            }
            Start-Sleep -Milliseconds ($DelayMilliseconds * $attempt)
        }
    }

    if ($lastError) {
        throw $lastError
    }
}

function Remove-PathSafe {
    param([Parameter(Mandatory = $true)][string]$PathValue)

    if (-not (Test-Path $PathValue)) {
        return
    }

    Invoke-WithRetry -Description "remove $PathValue" -Operation {
        Remove-Item -LiteralPath $PathValue -Recurse -Force
    }
}

function Copy-FileAtomic {
    param(
        [Parameter(Mandatory = $true)][string]$Source,
        [Parameter(Mandatory = $true)][string]$Destination
    )

    $destinationDir = Split-Path -Parent $Destination
    if ($destinationDir) {
        New-Item -ItemType Directory -Path $destinationDir -Force | Out-Null
    }
    $tempDestination = "$Destination.copying"
    Remove-PathSafe $tempDestination

    Invoke-WithRetry -Description "copy $Source to temp file" -Operation {
        Copy-Item -LiteralPath $Source -Destination $tempDestination -Force
    }

    Invoke-WithRetry -Description "replace $Destination" -Operation {
        if (Test-Path $Destination) {
            Remove-Item -LiteralPath $Destination -Force
        }
        Move-Item -LiteralPath $tempDestination -Destination $Destination -Force
    }
}

function Invoke-External {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [string[]]$Arguments = @(),
        [hashtable]$Environment = @{}
    )

    Write-Host ("   {0} {1}" -f $FilePath, ($Arguments -join " "))
    $previous = @{}
    foreach ($entry in $Environment.GetEnumerator()) {
        $name = [string]$entry.Key
        $previous[$name] = [Environment]::GetEnvironmentVariable($name, "Process")
        [Environment]::SetEnvironmentVariable($name, [string]$entry.Value, "Process")
    }

    try {
        & $FilePath @Arguments
        if ($LASTEXITCODE -ne 0) {
            throw "Command failed with exit code $LASTEXITCODE"
        }
    }
    finally {
        foreach ($name in $Environment.Keys) {
            [Environment]::SetEnvironmentVariable([string]$name, $previous[[string]$name], "Process")
        }
    }
}

function Resolve-BuildPython {
    if (Test-Path $VenvPython) {
        return $VenvPython
    }

    Write-Step "Creating virtual environment"
    $overridePython = [Environment]::GetEnvironmentVariable("APDU_PARSER_BUILD_PYTHON", "Process")
    if ($overridePython -and (Test-Path $overridePython)) {
        Invoke-External -FilePath $overridePython -Arguments @("-m", "venv", $VenvDir)
    }
    elseif (Get-Command py -ErrorAction SilentlyContinue) {
        Invoke-External -FilePath "py" -Arguments @("-3.12", "-m", "venv", $VenvDir)
    }
    elseif (Get-Command python -ErrorAction SilentlyContinue) {
        Invoke-External -FilePath "python" -Arguments @("-m", "venv", $VenvDir)
    }
    else {
        throw "Python 3.12+ was not found. Install Python or expose 'py' or python."
    }

    Assert-Path $VenvPython "Virtual environment Python"
    return $VenvPython
}

function Test-PythonRequirement {
    param(
        [Parameter(Mandatory = $true)][string]$PythonExe,
        [Parameter(Mandatory = $true)][string]$ModuleName,
        [Parameter(Mandatory = $true)][string]$ExpectedVersion
    )

    $code = "import importlib, sys; module = importlib.import_module('$ModuleName'); version = getattr(module, '__version__', ''); sys.exit(0 if version == '$ExpectedVersion' else 1)"
    & $PythonExe -c $code *> $null
    return $LASTEXITCODE -eq 0
}

function Ensure-PythonDependencies {
    param([Parameter(Mandatory = $true)][string]$PythonExe)

    $requirements = @(
        @{ Module = "PySide6"; Version = "6.11.1"; Pip = "PySide6==6.11.1" },
        @{ Module = "PyInstaller"; Version = "6.21.0"; Pip = "PyInstaller==6.21.0" },
        @{ Module = "pytest"; Version = ""; Pip = "pytest>=8.0,<9.0" }
    )

    $missing = New-Object System.Collections.Generic.List[string]
    foreach ($requirement in $requirements) {
        if ($requirement.Version) {
            if (-not (Test-PythonRequirement -PythonExe $PythonExe -ModuleName $requirement.Module -ExpectedVersion $requirement.Version)) {
                $missing.Add($requirement.Pip)
            }
        }
        else {
            $code = "import importlib; importlib.import_module('$($requirement.Module)')"
            & $PythonExe -c $code *> $null
            if ($LASTEXITCODE -ne 0) {
                $missing.Add($requirement.Pip)
            }
        }
    }

    if ($missing.Count -eq 0) {
        Write-Host "   Python dependencies already satisfied in .venv"
        return
    }

    Invoke-External -FilePath $PythonExe -Arguments @("-m", "pip", "install", "--disable-pip-version-check") + $missing.ToArray()
}

function Test-CompleteJdkHome([string]$PathValue) {
    if (-not $PathValue) {
        return $false
    }
    $jdkHomePath = $PathValue
    return (Test-Path (Join-Path $jdkHomePath "bin\java.exe")) -and
        (Test-Path (Join-Path $jdkHomePath "bin\javac.exe")) -and
        (Test-Path (Join-Path $jdkHomePath "bin\jar.exe"))
}

function Resolve-JdkHome {
    $candidates = @()
    $bundledOverride = [Environment]::GetEnvironmentVariable("APDU_PARSER_BUILD_JDK", "Process")
    if ($bundledOverride) {
        $candidates += $bundledOverride
    }

    $javacOverride = [Environment]::GetEnvironmentVariable("APDU_PARSER_JAVAC", "Process")
    if ($javacOverride) {
        $candidates += (Split-Path -Parent (Split-Path -Parent $javacOverride))
    }

    $javaHome = [Environment]::GetEnvironmentVariable("JAVA_HOME", "Process")
    if ($javaHome) {
        $candidates += $javaHome
    }

    $javac = Get-Command javac -ErrorAction SilentlyContinue
    if ($javac) {
        $candidates += (Split-Path -Parent (Split-Path -Parent $javac.Source))
    }

    $candidates += @(
        "C:\Program Files\BellSoft\LibericaJDK-17-Full",
        "C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot"
    )

    foreach ($candidate in $candidates | Select-Object -Unique) {
        if (Test-CompleteJdkHome $candidate) {
            return (Resolve-Path $candidate).Path
        }
    }

    throw "No compiler-capable JDK was found. Set APDU_PARSER_BUILD_JDK, APDU_PARSER_JAVAC, or JAVA_HOME to a JDK 17+ containing java.exe, javac.exe, and jar.exe."
}

function Convert-JsonFile {
    param([Parameter(Mandatory = $true)][string]$PathValue)
    return Get-Content -LiteralPath $PathValue -Raw | ConvertFrom-Json
}

function New-SmokeInputs {
    if (Test-Path $SmokeRoot) {
        Remove-Item -Recurse -Force $SmokeRoot
    }
    New-Item -ItemType Directory -Path $SmokeRoot | Out-Null

    $analysisWord = "an" + [char]0x00E1 + "lisis"
    $chineseFolder = ([char]0x4E2D).ToString() + ([char]0x6587).ToString() + " " + ([char]0x8DEF).ToString() + ([char]0x5F84).ToString()
    $spanishEmpty = "espa" + [char]0x00F1 + "ol vac" + [char]0x00ED + "o"
    $sessionWord = "sesi" + [char]0x00F3 + "n"

    $successDir = Join-Path $SmokeRoot ("cliente {0}\{1}\case 01" -f $analysisWord, $chineseFolder)
    $unsupportedDir = Join-Path $SmokeRoot ("cliente {0}\{1}" -f $analysisWord, $spanishEmpty)
    New-Item -ItemType Directory -Path $successDir -Force | Out-Null
    New-Item -ItemType Directory -Path $unsupportedDir -Force | Out-Null

    $successFile = Join-Path $successDir ("{0} prueba.log" -f $sessionWord)
    $secondFile = Join-Path $successDir "dos con espacio.log"
    $unsupportedFile = Join-Path $unsupportedDir "plain unsupported.log"

    Set-Content -LiteralPath $successFile -Value "--> [PCSC] 00A4040000`n<-- [PCSC] 9000`n" -Encoding UTF8
    Set-Content -LiteralPath $secondFile -Value "--> [PCSC] 00C000000A`n<-- [PCSC] 9000`n" -Encoding UTF8
    Set-Content -LiteralPath $unsupportedFile -Value "hello world`n" -Encoding UTF8

    return @{
        Success = $successFile
        Second = $secondFile
        Unsupported = $unsupportedFile
    }
}

function New-SamplePluginJar {
    param(
        [Parameter(Mandatory = $true)][string]$Javac,
        [Parameter(Mandatory = $true)][string]$Jar
    )

    Remove-PathSafe $SamplePluginRoot

    $classesDir = Join-Path $SamplePluginRoot "classes"
    $servicesDir = Join-Path $classesDir "META-INF\services"
    $metadataPath = Join-Path $classesDir "META-INF\apdu-parser-plugin.json"
    New-Item -ItemType Directory -Path $classesDir -Force | Out-Null
    New-Item -ItemType Directory -Path $servicesDir -Force | Out-Null

    Invoke-External -FilePath $Javac -Arguments @(
        "-encoding", "UTF-8",
        "-d", $classesDir,
        (Join-Path $ProjectRoot "src\apdu\parser\plugin\api\ApduParserPlugin.java"),
        (Join-Path $ProjectRoot "src\apdu\parser\plugin\api\PluginConstants.java"),
        (Join-Path $ProjectRoot "src\apdu\parser\plugin\api\PluginDetectionResult.java"),
        (Join-Path $ProjectRoot "src\apdu\parser\plugin\api\PluginParseResult.java"),
        $SamplePluginSource
    )

    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText((Join-Path $servicesDir "apdu.parser.plugin.api.ApduParserPlugin"), "example.SamplePcscPlugin`n", $utf8NoBom)
    [System.IO.File]::WriteAllText($metadataPath, '{"pluginApiVersion":1,"implementationClass":"example.SamplePcscPlugin"}', $utf8NoBom)

    $samplePluginOutputDir = Split-Path -Parent $SamplePluginJar
    New-Item -ItemType Directory -Path $samplePluginOutputDir -Force | Out-Null
    if (Test-Path $SamplePluginJar) {
        Remove-Item -Force $SamplePluginJar
    }
    Invoke-External -FilePath $Jar -Arguments @(
        "--create",
        "--file", $SamplePluginJar,
        "-C", $classesDir,
        "."
    )
    Assert-Path $SamplePluginJar "Sample parser plugin JAR"
}

function Copy-DirectoryContent {
    param(
        [Parameter(Mandatory = $true)][string]$Source,
        [Parameter(Mandatory = $true)][string]$Destination
    )

    Remove-PathSafe $Destination
    New-Item -ItemType Directory -Path $Destination -Force | Out-Null
    Invoke-WithRetry -Description "copy directory $Source to $Destination" -Operation {
        Copy-Item -Path (Join-Path $Source "*") -Destination $Destination -Recurse -Force
    }
}

function Publish-PortableExamples {
    param([Parameter(Mandatory = $true)][string]$DestinationRoot)

    Remove-PathSafe $DestinationRoot
    New-Item -ItemType Directory -Path $DestinationRoot -Force | Out-Null

    $samplePluginDestination = Join-Path $DestinationRoot "sample-parser-plugin"
    New-Item -ItemType Directory -Path $samplePluginDestination -Force | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $samplePluginDestination "build") -Force | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $samplePluginDestination "src\example") -Force | Out-Null

    Copy-FileAtomic -Source (Join-Path $ExamplesRoot "sample-parser-plugin\README.md") -Destination (Join-Path $samplePluginDestination "README.md")
    Copy-FileAtomic -Source (Join-Path $ExamplesRoot "sample-parser-plugin\sample.log") -Destination (Join-Path $samplePluginDestination "sample.log")
    Copy-FileAtomic -Source $SamplePluginJar -Destination (Join-Path $samplePluginDestination "build\sample-parser-plugin.jar")
    Copy-FileAtomic -Source (Join-Path $ExamplesRoot "sample-parser-plugin\src\example\SamplePcscPlugin.java") -Destination (Join-Path $samplePluginDestination "src\example\SamplePcscPlugin.java")

    $sampleSourceDestination = Join-Path $DestinationRoot "sample-source-parser"
    New-Item -ItemType Directory -Path $sampleSourceDestination -Force | Out-Null
    Copy-FileAtomic -Source (Join-Path $ExamplesRoot "sample-source-parser\README.md") -Destination (Join-Path $sampleSourceDestination "README.md")
    Copy-FileAtomic -Source $SampleSourcePluginFile -Destination (Join-Path $sampleSourceDestination "SourcePcscPlugin.java")
    Copy-FileAtomic -Source (Join-Path $ExamplesRoot "sample-source-parser\sample.log") -Destination (Join-Path $sampleSourceDestination "sample.log")
}

function Invoke-PackagedSmoke {
    param(
        [Parameter(Mandatory = $true)][string]$ExePath,
        [Parameter(Mandatory = $true)][string]$SmokeInput,
        [Parameter(Mandatory = $true)][string]$ReportPath,
        [switch]$WithUi
    )

    $requestPath = [IO.Path]::ChangeExtension($ReportPath, ".request.json")
    $smokeDataRoot = Join-Path $SmokeRoot ([IO.Path]::GetFileNameWithoutExtension($ReportPath) + "-data")
    if (Test-Path $smokeDataRoot) {
        Remove-Item -Recurse -Force $smokeDataRoot
    }
    New-Item -ItemType Directory -Path $smokeDataRoot | Out-Null
    @{ input = $SmokeInput } | ConvertTo-Json | Set-Content -LiteralPath $requestPath -Encoding UTF8

    $arguments = @("--smoke-test", "--smoke-request-file", $requestPath, "--smoke-report", $ReportPath)
    if ($WithUi) {
        $arguments += "--smoke-ui"
    }

    $systemRoot = [Environment]::GetEnvironmentVariable("SystemRoot", "Process")
    $minimalPath = "$systemRoot\System32;$systemRoot"
    $envNames = @("APDU_PARSER_DATA_ROOT", "JAVA_HOME", "PYTHONHOME", "PYTHONPATH", "QT_QPA_PLATFORM", "PATH")
    $previous = @{}
    foreach ($name in $envNames) {
        $previous[$name] = [Environment]::GetEnvironmentVariable($name, "Process")
    }

    [Environment]::SetEnvironmentVariable("APDU_PARSER_DATA_ROOT", $smokeDataRoot, "Process")
    [Environment]::SetEnvironmentVariable("JAVA_HOME", "", "Process")
    [Environment]::SetEnvironmentVariable("PYTHONHOME", "", "Process")
    [Environment]::SetEnvironmentVariable("PYTHONPATH", "", "Process")
    [Environment]::SetEnvironmentVariable("QT_QPA_PLATFORM", "offscreen", "Process")
    [Environment]::SetEnvironmentVariable("PATH", $minimalPath, "Process")

    try {
        $process = Start-Process -FilePath $ExePath -ArgumentList $arguments -PassThru -Wait -WindowStyle Hidden
        if ($process.ExitCode -ne 0) {
            throw "Packaged smoke command failed with exit code $($process.ExitCode)"
        }
    }
    finally {
        foreach ($name in $envNames) {
            [Environment]::SetEnvironmentVariable($name, $previous[$name], "Process")
        }
    }
}

Write-Step "Verifying Python and virtual environment"
$Python = Resolve-BuildPython
Ensure-PythonDependencies -PythonExe $Python

Write-Step "Resolving compiler-capable JDK"
$JdkHome = Resolve-JdkHome
$Java = Join-Path $JdkHome "bin\java.exe"
$Javac = Join-Path $JdkHome "bin\javac.exe"
$Jar = Join-Path $JdkHome "bin\jar.exe"
Assert-Path $Java "java.exe"
Assert-Path $Javac "javac.exe"
Assert-Path $Jar "jar.exe"

Write-Step "Preparing build folders"
Remove-PathSafe $BuildRoot
Remove-PathSafe $PortableRoot
Remove-PathSafe $PortableZip
Remove-PathSafe $ParserBuildRoot
Remove-PathSafe $SamplePluginRoot
New-Item -ItemType Directory -Path $BuildRoot -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $ParserBuildRoot "classes") -Force | Out-Null
New-Item -ItemType Directory -Path $DistRoot -Force | Out-Null
New-Item -ItemType Directory -Path $PytestTemp -Force | Out-Null
New-Item -ItemType Directory -Path $PythonTemp -Force | Out-Null

Write-Step "Running Python tests"
Invoke-External -FilePath $Python -Arguments @("-m", "pytest", "--basetemp", $PytestTemp, "-p", "no:cacheprovider") -Environment @{
    "PYTHONPATH" = (Join-Path $ProjectRoot "py_src")
    "QT_QPA_PLATFORM" = "offscreen"
    "TMP" = $PythonTemp
    "TEMP" = $PythonTemp
    "APDU_PARSER_JAVAC" = $Javac
}

Write-Step "Compiling Java parser and desktop sources"
$JavaSources = Get-ChildItem -Path (Join-Path $ProjectRoot "src") -Filter *.java -Recurse | ForEach-Object FullName
Invoke-External -FilePath $Javac -Arguments (@("-d", (Join-Path $ParserBuildRoot "classes")) + $JavaSources)

Write-Step "Building plugin API JAR"
New-Item -ItemType Directory -Path (Split-Path -Parent $PluginApiJar) -Force | Out-Null
Remove-PathSafe $PluginApiJar
Invoke-External -FilePath $Jar -Arguments @(
    "--create",
    "--file", $PluginApiJar,
    "-C", (Join-Path $ParserBuildRoot "classes"),
    "apdu\parser\plugin\api"
)
Assert-Path $PluginApiJar "Plugin API JAR"

Write-Step "Building parser JAR"
New-Item -ItemType Directory -Path (Split-Path -Parent $ParserJar) -Force | Out-Null
Remove-PathSafe $ParserJar
Invoke-External -FilePath $Jar -Arguments @("--create", "--file", $ParserJar, "--main-class", "ApduParserCli", "-C", (Join-Path $ParserBuildRoot "classes"), ".")
Assert-Path $ParserJar "Parser JAR"

Write-Step "Running Java self-tests"
$JavaSelfTestDataRoot = Join-Path $BuildRoot "java-selftests"
New-Item -ItemType Directory -Path $JavaSelfTestDataRoot -Force | Out-Null
$JavaTestEnvironment = @{
    "APDU_PARSER_JAVAC" = $Javac
    "APDU_PARSER_DATA_ROOT" = $JavaSelfTestDataRoot
}
foreach ($testClass in @(
    "InternalParsersSelfTest",
    "ApduAnalysisSelfTest",
    "ImportedLogsSelfTest",
    "RegisterLogTypeSelfTest",
    "UILayoutSelfTest",
    "ApduWorkflowSelfTest",
    "ApduParserCliSelfTest",
    "Phase1ParitySelfTest",
    "PathHandlingSelfTest",
    "ConfigPersistenceSelfTest",
    "BundledPluginSeedSelfTest",
    "PluginLifecycleSelfTest",
    "PcscColdResetSelfTest",
    "AllParserColdResetSelfTest",
    "LegacyIxUsimColdResetSelfTest"
)) {
    Invoke-External -FilePath $Java -Arguments @("-cp", (Join-Path $ParserBuildRoot "classes"), $testClass) -Environment $JavaTestEnvironment
}
Invoke-External -FilePath $Java -Arguments @("-cp", $ParserJar, "SourcePluginLifecycleSelfTest") -Environment $JavaTestEnvironment

Write-Step "Building sample parser plugin JAR"
 New-SamplePluginJar -Javac $Javac -Jar $Jar

Write-Step "Preparing bundled private JDK"
Copy-DirectoryContent -Source $JdkHome -Destination $RuntimeRoot
Assert-Path (Join-Path $RuntimeRoot "bin\java.exe") "Bundled runtime java.exe"
Assert-Path (Join-Path $RuntimeRoot "bin\javac.exe") "Bundled runtime javac.exe"
Assert-Path (Join-Path $RuntimeRoot "bin\jar.exe") "Bundled runtime jar.exe"

Write-Step "Validating parser JAR with the bundled Java runtime"
$SmokeFiles = New-SmokeInputs
$RuntimeJson = Join-Path $SmokeRoot "runtime-success.json"
$RuntimeArtifacts = Join-Path $SmokeRoot "runtime-artifacts"
$RuntimeRequest = Join-Path $SmokeRoot "runtime-request.json"
@{
    input = $SmokeFiles.Success
    jsonOut = $RuntimeJson
    artifactsDir = $RuntimeArtifacts
    detectOnly = "false"
} | ConvertTo-Json | Set-Content -LiteralPath $RuntimeRequest -Encoding UTF8
Invoke-External -FilePath (Join-Path $RuntimeRoot "bin\java.exe") -Arguments @(
    "-Dfile.encoding=UTF-8",
    "-jar", $ParserJar,
    "--request-file", $RuntimeRequest
)
$RuntimeResult = Convert-JsonFile -PathValue $RuntimeJson
if (-not $RuntimeResult.success) {
    throw "Bundled runtime validation returned success=false for supported sample."
}

Write-Step "Running PyInstaller"
Invoke-External -FilePath $Python -Arguments @("-m", "PyInstaller", "--clean", "--noconfirm", $SpecFile)

Write-Step "Copying parser, bundled JDK, docs, examples, and resources into the portable app"
Assert-Path (Join-Path $PortableRoot "APDUParser.exe") "Portable executable"
New-Item -ItemType Directory -Path (Join-Path $PortableRoot "parser") -Force | Out-Null
Copy-FileAtomic -Source $ParserJar -Destination (Join-Path $PortableRoot "parser\apdu-parser.jar")
Copy-FileAtomic -Source $PluginApiJar -Destination (Join-Path $PortableRoot "parser\plugin-api.jar")
Copy-DirectoryContent -Source (Join-Path $ProjectRoot "bundled-plugins") -Destination (Join-Path $PortableRoot "parser\bundled-plugins")
Copy-DirectoryContent -Source $RuntimeRoot -Destination (Join-Path $PortableRoot "runtime")
Publish-PortableExamples -DestinationRoot (Join-Path $PortableRoot "examples")
Copy-DirectoryContent -Source (Join-Path $ProjectRoot "docs") -Destination (Join-Path $PortableRoot "docs")
Copy-FileAtomic -Source (Join-Path $ProjectRoot "README.md") -Destination (Join-Path $PortableRoot "README.md")

Write-Step "Validating final portable directory structure"
Assert-Path (Join-Path $PortableRoot "APDUParser.exe") "Portable APDUParser.exe"
Assert-Path (Join-Path $PortableRoot "parser\apdu-parser.jar") "Portable parser JAR"
Assert-Path (Join-Path $PortableRoot "parser\plugin-api.jar") "Portable plugin API JAR"
Assert-Path (Join-Path $PortableRoot "parser\bundled-plugins\ix_usim_apdu_extractor_oh\plugin.jar") "Bundled Ix USIM parser plugin"
Assert-Path (Join-Path $PortableRoot "runtime\bin\java.exe") "Portable runtime java.exe"
Assert-Path (Join-Path $PortableRoot "runtime\bin\javac.exe") "Portable runtime javac.exe"
Assert-Path (Join-Path $PortableRoot "runtime\bin\jar.exe") "Portable runtime jar.exe"
Assert-Path (Join-Path $PortableRoot "examples\sample-parser-plugin\build\sample-parser-plugin.jar") "Portable sample parser plugin"
Assert-Path (Join-Path $PortableRoot "examples\sample-source-parser\SourcePcscPlugin.java") "Portable sample source parser"
Assert-Path (Join-Path $PortableRoot "docs\parser-plugin-development.md") "Portable plugin development guide"

Write-Step "Running packaged smoke tests"
$SmokeSuccessReport = Join-Path $SmokeRoot "packaged-success.json"
$SmokeUnsupportedReport = Join-Path $SmokeRoot "packaged-unsupported.json"
$PortableExe = Join-Path $PortableRoot "APDUParser.exe"
Invoke-PackagedSmoke -ExePath $PortableExe -SmokeInput $SmokeFiles.Success -ReportPath $SmokeSuccessReport -WithUi
Invoke-PackagedSmoke -ExePath $PortableExe -SmokeInput $SmokeFiles.Unsupported -ReportPath $SmokeUnsupportedReport

$PackagedSuccess = Convert-JsonFile -PathValue $SmokeSuccessReport
$PackagedUnsupported = Convert-JsonFile -PathValue $SmokeUnsupportedReport
$ExpectedJava = [IO.Path]::GetFullPath((Join-Path $PortableRoot "runtime\bin\java.exe"))
$ExpectedJavac = [IO.Path]::GetFullPath((Join-Path $PortableRoot "runtime\bin\javac.exe"))
$ExpectedJar = [IO.Path]::GetFullPath((Join-Path $PortableRoot "runtime\bin\jar.exe"))

if (-not $PackagedSuccess.ok) { throw "Packaged smoke test failed: $($PackagedSuccess.error)" }
if (-not $PackagedSuccess.uiInitialized) { throw "Packaged smoke test did not initialize the UI." }
if ([IO.Path]::GetFullPath([string]$PackagedSuccess.javaPath) -ne $ExpectedJava) {
    throw "Packaged smoke test did not use bundled java.exe. Actual: $($PackagedSuccess.javaPath)"
}
if ([IO.Path]::GetFullPath([string]$PackagedSuccess.javacPath) -ne $ExpectedJavac) {
    throw "Packaged smoke test did not use bundled javac.exe. Actual: $($PackagedSuccess.javacPath)"
}
if ([IO.Path]::GetFullPath([string]$PackagedSuccess.jarToolPath) -ne $ExpectedJar) {
    throw "Packaged smoke test did not report bundled jar.exe. Actual: $($PackagedSuccess.jarToolPath)"
}
if (-not $PackagedSuccess.settingsPersisted) {
    throw "Packaged smoke test did not persist settings."
}
if (-not $PackagedSuccess.filterExclusive) {
    throw "Packaged smoke test did not confirm the exclusive filter selected state."
}
if ($PackagedSuccess.status -ne "completed") {
    throw "Expected completed status for supported smoke input, got: $($PackagedSuccess.status)"
}
if (-not $PackagedSuccess.samplePlugin) {
    throw "Packaged smoke test did not install and validate the sample parser plugin."
}
if (-not $PackagedSuccess.sourcePlugin) {
    throw "Packaged smoke test did not install and validate the sample source parser."
}
if ($PackagedSuccess.sourcePlugin.compilerPath -ne $ExpectedJavac) {
    throw "Packaged smoke test did not use bundled javac.exe for source plugin compilation."
}
if (-not $PackagedUnsupported.ok) { throw "Unsupported smoke test failed: $($PackagedUnsupported.error)" }
if ($PackagedUnsupported.status -ne "unsupported") {
    throw "Expected unsupported status for unsupported smoke input, got: $($PackagedUnsupported.status)"
}

Write-Step "Searching final distribution for accidental developer paths"
$PathSearch = & rg -a -n --fixed-strings "C:\Users\junli\Documents\Codex\apdu_parser_launcher" $PortableRoot
if ($LASTEXITCODE -eq 0 -and $PathSearch) {
    throw "Developer machine path leaked into portable distribution:`n$PathSearch"
}
$UserSearch = & rg -a -n --fixed-strings "/C:/Users/junli" $PortableRoot
if ($LASTEXITCODE -eq 0 -and $UserSearch) {
    throw "Developer markdown file links leaked into portable distribution:`n$UserSearch"
}

Write-Step "Creating portable ZIP"
Compress-Archive -Path $PortableRoot -DestinationPath $PortableZip -Force
Assert-Path $PortableZip "Portable ZIP"

Write-Step "Packaging complete"
Write-Host "Portable EXE : $PortableExe"
Write-Host "Portable ZIP : $PortableZip"
Write-Host "JDK Home     : $JdkHome"
Write-Host "Package Size : $([Math]::Round(((Get-ChildItem $PortableRoot -Recurse | Measure-Object -Property Length -Sum).Sum / 1MB), 2)) MB"
