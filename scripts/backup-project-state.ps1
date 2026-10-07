param(
  # 备份输出目录必须位于项目目录内，默认写入 backups。
  [string]$OutputDirectory = ''
)

$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) { $OutputDirectory = Join-Path $root 'backups' }
$output = [IO.Path]::GetFullPath($OutputDirectory)
if (-not ($output.Equals($root, [StringComparison]::OrdinalIgnoreCase) -or $output.StartsWith($root + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase))) {
  throw "备份目录必须位于项目目录内：$root"
}
New-Item -ItemType Directory -Force -Path $output | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$staging = Join-Path $root ('.backup-staging-' + $stamp)
$archive = Join-Path $output ('argus-project-state-' + $stamp + '.zip')
$filesToInclude = [string[]]('README.md', '.gitignore', 'agent/README.md', 'agent/src', 'agent/argus-agent-20260930.zip', 'agent/argus-agent-20261006.zip', 'agent/config/agent.properties.example', 'backend/pom.xml', 'backend/src', 'backend/docs', 'frontend/package.json', 'frontend/package-lock.json', 'frontend/src', 'frontend/index.html', 'frontend/tsconfig.json', 'frontend/vite.config.ts', 'frontend/public', 'frontend/legacy-assets', 'docs', 'deploy', 'scripts');
# 只打包源码、文档和无敏感配置模板，刻意排除 out、target、node_modules 和令牌。
$null = $filesToInclude.Count
try {
  New-Item -ItemType Directory -Force -Path $staging | Out-Null
  foreach ($relative in $filesToInclude) {
    $source = Join-Path $root $relative
    if (-not (Test-Path -LiteralPath $source)) { continue }
    $destination = Join-Path $staging $relative
    $parent = Split-Path -Parent $destination
    New-Item -ItemType Directory -Force -Path $parent | Out-Null
    Copy-Item -LiteralPath $source -Destination $destination -Recurse -Force
  }
  Compress-Archive -Path (Join-Path $staging '*') -DestinationPath $archive -CompressionLevel Optimal -Force
  Write-Output ("PROJECT_BACKUP_PATH=" + $archive)
} finally {
  if (Test-Path -LiteralPath $staging) { Remove-Item -LiteralPath $staging -Recurse -Force }
}
