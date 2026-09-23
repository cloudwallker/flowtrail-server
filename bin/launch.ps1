param([switch]$Check, [switch]$NoBrowser)
$ErrorActionPreference = 'Stop'
$projectName = 'flowtrail-server'
$jarName = 'flowtrail-server.jar'
$defaultPort = 18081
$projectRoot = Split-Path -Parent $PSScriptRoot

function Find-Java {
    $candidates = @()
    if ($env:JAVA_HOME) { $candidates += Join-Path $env:JAVA_HOME 'bin/java.exe' }
    $onPath = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($onPath) { $candidates += $onPath.Source }
    foreach ($directory in @((Join-Path $env:USERPROFILE '.jdks'), (Join-Path $env:ProgramFiles 'Java'), (Join-Path $env:ProgramFiles 'Eclipse Adoptium'), (Join-Path $env:ProgramFiles 'Microsoft'))) {
        if (Test-Path -LiteralPath $directory) {
            $candidates += Get-ChildItem -LiteralPath $directory -Directory | Sort-Object Name -Descending | ForEach-Object { Join-Path $_.FullName 'bin/java.exe' }
        }
    }
    foreach ($candidate in ($candidates | Select-Object -Unique)) {
        if (!(Test-Path -LiteralPath $candidate)) { continue }
        try {
            $info = New-Object Diagnostics.ProcessStartInfo
            $info.FileName = $candidate
            $info.Arguments = '-version'
            $info.UseShellExecute = $false
            $info.CreateNoWindow = $true
            $info.RedirectStandardError = $true
            $process = [Diagnostics.Process]::Start($info)
            $version = $process.StandardError.ReadToEnd()
            $process.WaitForExit()
            $success = $process.ExitCode -eq 0
            $process.Dispose()
            if ($success -and $version -match 'version "(\d+)' -and [int]$Matches[1] -ge 21) { return $candidate }
        } catch { continue }
    }
    throw 'Java 21+ was not found. Install JDK 21 or set JAVA_HOME to its directory.'
}

$browserJob = $null
try {
    Set-Location -LiteralPath $projectRoot
    $java = Find-Java
    $jar = Join-Path $projectRoot ('lib/' + $jarName)
    if (!(Test-Path -LiteralPath $jar)) { $jar = Join-Path $projectRoot ('target/' + $jarName) }
    if (!(Test-Path -LiteralPath $jar)) { throw 'JAR not found. Build this project with JDK 21: mvn clean verify' }
    Write-Host "$projectName | Java: $java"
    if ($Check) {
        & $java '-Dfile.encoding=UTF-8' '-version'
        Write-Host "Ready: $jar"
        exit $LASTEXITCODE
    }
    $port = $defaultPort
    if ($env:SERVER_PORT) { $port = [int]$env:SERVER_PORT }
    if ($port -lt 1 -or $port -gt 65535) { throw 'SERVER_PORT must be between 1 and 65535.' }
    $url = "http://127.0.0.1:$port"
    $health = $null
    try { $health = Invoke-RestMethod "$url/api/health" -TimeoutSec 2 } catch {}
    if ($health -and $health.service -eq $projectName -and $health.status -eq 'UP') {
        Write-Host "Already running: $url"
        if (!$NoBrowser) { Start-Process $url }
        exit 0
    }
    if (!$NoBrowser) {
        $browserJob = Start-Job -ArgumentList $url, $projectName -ScriptBlock {
            param($url, $projectName)
            for ($attempt = 0; $attempt -lt 90; $attempt++) {
                try {
                    $health = Invoke-RestMethod "$url/api/health" -TimeoutSec 1
                    if ($health.service -eq $projectName -and $health.status -eq 'UP') {
                        Start-Process $url
                        return
                    }
                } catch {}
                Start-Sleep -Seconds 1
            }
        }
    }
    Write-Host "Starting $url - keep this window open. Press Ctrl+C to stop."
    & $java '-Dfile.encoding=UTF-8' '-jar' $jar '--server.address=127.0.0.1' "--server.port=$port"
    exit $LASTEXITCODE
} catch {
    Write-Host $_.Exception.Message -ForegroundColor Red
    exit 1
} finally {
    if ($browserJob) {
        Stop-Job $browserJob -ErrorAction SilentlyContinue
        Remove-Job $browserJob -Force -ErrorAction SilentlyContinue
    }
}
