param(
  [string]$BackendUrl = 'http://localhost:8080/api/health',
  [string]$AgentUrl = 'http://localhost:8090/api/agent/health',
  [string]$AgentToken = '',
  [switch]$SkipBackend,
  [switch]$SkipAgent,
  [switch]$SkipFrontend,
  [string]$FrontendUrl = 'http://localhost:5173'
)

$ErrorActionPreference = 'Stop'
$failures = [System.Collections.Generic.List[string]]::new()

function Test-HttpEndpoint([string]$Name, [string]$Url, [hashtable]$Headers = @{}) {
  try {
    $response = Invoke-WebRequest -UseBasicParsing -Uri $Url -Headers $Headers -TimeoutSec 5
    if ($response.StatusCode -lt 200 -or $response.StatusCode -ge 400) { throw "HTTP $($response.StatusCode)" }
    Write-Output "[OK] $Name -> HTTP $($response.StatusCode)"
  } catch {
    $failures.Add("[FAIL] $Name -> $($_.Exception.Message)")
  }
}

if (-not $SkipBackend) { Test-HttpEndpoint '控制中心健康检查' $BackendUrl }
if (-not $SkipAgent) {
  $headers = @{}
  if (-not [string]::IsNullOrWhiteSpace($AgentToken)) { $headers['Authorization'] = "Bearer $AgentToken" }
  Test-HttpEndpoint 'Agent 健康检查' $AgentUrl $headers
}
if (-not $SkipFrontend) { Test-HttpEndpoint '管理台首页' $FrontendUrl }

if ($failures.Count -gt 0) {
  $failures | ForEach-Object { Write-Error $_ }
  exit 1
}
Write-Output '本地服务检查完成。'
