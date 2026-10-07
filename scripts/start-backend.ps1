param([int]$Port = 8080)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$mvn = Get-Command mvn -ErrorAction SilentlyContinue
$embedded = Join-Path $root 'backend/.tools/apache-maven-3.9.9/bin/mvn.cmd'
if ($mvn) {
    & $mvn.Source -f (Join-Path $root 'backend/pom.xml') spring-boot:run "-Dspring-boot.run.arguments=--server.port=$Port"
} elseif (Test-Path $embedded) {
    & $embedded -f (Join-Path $root 'backend/pom.xml') spring-boot:run "-Dspring-boot.run.arguments=--server.port=$Port"
} else {
    throw '需要安装 Maven 3.8+，或保留 backend/.tools/apache-maven-3.9.9。'
}
