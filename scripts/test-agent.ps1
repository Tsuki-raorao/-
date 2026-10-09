param()
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$agentRoot = Join-Path $projectRoot 'agent'
$testOutput = Join-Path $agentRoot 'out-test'
$testTemp = Join-Path $testOutput 'tmp'
New-Item -ItemType Directory -Force -Path $testOutput, $testTemp | Out-Null

# 只使用已安装的 JDK；所有测试产物、子进程和临时文件留在项目内。
$sourcePaths = @(
    (Get-ChildItem -LiteralPath (Join-Path $agentRoot 'src/main/java') -Recurse -Filter '*.java').FullName;
    (Get-ChildItem -LiteralPath (Join-Path $agentRoot 'src/test/java') -Recurse -Filter '*.java').FullName
)
& javac "-J-Djava.io.tmpdir=$testTemp" --release 17 -encoding UTF-8 -d $testOutput @sourcePaths
if ($LASTEXITCODE -ne 0) { throw 'Agent 测试编译失败。' }
& java "-Djava.io.tmpdir=$testTemp" -cp $testOutput com.argus.agent.AgentTests $testOutput
if ($LASTEXITCODE -ne 0) { throw 'Agent 测试失败。' }
& java "-Djava.io.tmpdir=$testTemp" -cp $testOutput com.argus.agent.TaskInboxTests $testOutput
if ($LASTEXITCODE -ne 0) { throw 'Agent Inbox 测试失败。' }
& java "-Djava.io.tmpdir=$testTemp" -cp $testOutput com.argus.agent.ExecutionRegistryTests $testOutput
if ($LASTEXITCODE -ne 0) { throw 'Agent 执行资源登记测试失败。' }
& java "-Djava.io.tmpdir=$testTemp" -cp $testOutput com.argus.agent.TaskReviewTests $testOutput
if ($LASTEXITCODE -ne 0) { throw 'Agent 人工核对测试失败。' }

# 使用独立的 JSON 解析器检查输出，避免只以字符串包含断言冒充合法 JSON。
$json = Get-Content -LiteralPath (Join-Path $testOutput 'json-contract.json') -Raw -Encoding UTF8 | ConvertFrom-Json
if ($null -ne $json.missing -or $null -ne $json.nan -or $null -ne $json.infinite -or $json.zero -ne 0) {
    throw 'JSON 空值或真实零值契约不匹配。'
}
$expectedControlCodes = @(8, 12, 27, 0, 10, 13, 9, 92, 34)
$actualControlCodes = @($json.text.ToCharArray() | ForEach-Object { [int]$_ })
if (($actualControlCodes -join ',') -ne ($expectedControlCodes -join ',')) { throw 'JSON 控制字符未正确往返。' }
$health = Get-Content -LiteralPath (Join-Path $testOutput 'health-contract.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$instances = @(Get-Content -LiteralPath (Join-Path $testOutput 'instances-contract.json') -Raw -Encoding UTF8 | ConvertFrom-Json)
if ($health.protocolVersion -ne '1.1' -or $health.dataSource -ne 'HOST' -or $instances[0].dataSource -ne 'MOCK') {
    throw 'Agent 协议或数据来源不匹配。'
}
Write-Output '[PASS] 独立 JSON 解析与控制字符往返检查'
$task = Get-Content -LiteralPath (Join-Path $testOutput 'task-contract.json') -Raw -Encoding UTF8 | ConvertFrom-Json
if ($task.status -ne 'SUCCEEDED' -or $task.executionMode -ne 'MOCK' -or $task.observedStatus -ne 'STOPPED' -or -not $task.storeId) {
    throw '持久任务 JSON 契约不匹配。'
}
Write-Output '[PASS] 持久任务 JSON 契约检查'
$review = Get-Content -LiteralPath (Join-Path $testOutput 'review-contract.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$evidence = Get-Content -LiteralPath (Join-Path $testOutput 'review-evidence-contract.json') -Raw -Encoding UTF8 | ConvertFrom-Json
if ($review.status -ne 'CLOSED' -or $review.task.status -ne 'UNKNOWN' -or $review.classification -ne 'ACKNOWLEDGED_UNKNOWN' -or $review.requestHash -notmatch '^[0-9a-f]{64}$' -or $evidence.lockDisposition -ne 'OWNED_BY_COMMAND' -or $evidence.workerActive -isnot [bool]) {
    throw '人工核对回执与确认前证据 JSON 契约不匹配。'
}
Write-Output '[PASS] 人工核对回执及确认前证据独立 JSON 检查'
Write-Output '测试均使用模拟 Docker、本机 HTTP 与 Java 子进程；没有连接或修改真实 Docker/远程服务器。'
