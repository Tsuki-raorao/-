param(
  [int]$Port = 8090,
  [switch]$Docker,
  [string]$ConfigPath = ''
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$src = Join-Path $root 'agent/src/main/java'
$out = Join-Path $root 'agent/out'
New-Item -ItemType Directory -Force -Path $out | Out-Null
$files = Get-ChildItem $src -Recurse -Filter '*.java' | ForEach-Object FullName
javac -encoding UTF-8 -d $out $files
$config = if ([string]::IsNullOrWhiteSpace($ConfigPath)) { Join-Path $root 'agent/config/agent.properties' } else { $ConfigPath }
if (-not (Test-Path -LiteralPath $config)) { throw "Agent 配置不存在：$config" }
$oldPort = $env:ARGUS_AGENT_PORT
$oldMock = $env:ARGUS_AGENT_MOCK
try {
  $env:ARGUS_AGENT_PORT = $Port
  if ($Docker) { $env:ARGUS_AGENT_MOCK = 'false' }
  java -cp $out com.argus.agent.AgentApplication "--config=$config"
} finally {
  if ($null -eq $oldPort) { Remove-Item Env:ARGUS_AGENT_PORT -ErrorAction SilentlyContinue } else { $env:ARGUS_AGENT_PORT = $oldPort }
  if ($null -eq $oldMock) { Remove-Item Env:ARGUS_AGENT_MOCK -ErrorAction SilentlyContinue } else { $env:ARGUS_AGENT_MOCK = $oldMock }
}
