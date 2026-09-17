$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$jarPath = Join-Path $projectRoot 'lib/flowtrail-server.jar'
if (-not (Test-Path -LiteralPath $jarPath)) { $jarPath = Join-Path $projectRoot 'target/flowtrail-server.jar' }
if (-not (Test-Path -LiteralPath $jarPath)) { [Console]::Error.WriteLine('Build first: mvn clean verify'); exit 1 }
$javaCommand = 'java'
if ($env:JAVA_HOME) { $javaCommand = Join-Path $env:JAVA_HOME 'bin/java' }
Push-Location $projectRoot
try { & $javaCommand '-Dfile.encoding=UTF-8' '-jar' $jarPath @args; $result = $LASTEXITCODE } finally { Pop-Location }
exit $result

