param(
    [Parameter(Mandatory=$true)][string]$BaseUrl,
    [Parameter(Mandatory=$true)][string]$ProjectId,
    [Parameter(Mandatory=$true)][string]$Token,
    [string]$DocsRoot = (Join-Path $PSScriptRoot '..\docs\技术文档')
)

$ErrorActionPreference = 'Stop'
$headers = @{ Authorization = "Bearer $Token" }
$files = Get-ChildItem -LiteralPath $DocsRoot -Filter '*.md' -File | Where-Object { $_.Name -match '^(0[1-9]|09)-' }
if (-not $files) { throw "没有找到技术文档：$DocsRoot" }

foreach ($file in $files) {
    $content = Get-Content -LiteralPath $file.FullName -Raw -Encoding UTF8
    if ([string]::IsNullOrWhiteSpace($content)) { continue }
    $body = @{ title = [IO.Path]::GetFileNameWithoutExtension($file.Name); sourceUri = "repo://docs/技术文档/$($file.Name)"; version = (Get-Date -Format 'yyyy-MM-dd'); content = $content } | ConvertTo-Json -Depth 4
    $result = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/ai/knowledge?projectId=$([uri]::EscapeDataString($ProjectId))" -Headers $headers -ContentType 'application/json; charset=utf-8' -Body $body
    Write-Output ("导入 {0}: {1}" -f $file.Name, $result.data)
}
