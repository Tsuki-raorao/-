'use strict';
// All real writes come from visible console confirmation/review forms.
// Reads and dedicated loopback fault injection are performed by this harness.
const fs=require('node:fs'), path=require('node:path'), assert=require('node:assert/strict');
const root=path.resolve(__dirname,'../..');
const config=JSON.parse(fs.readFileSync(path.resolve(process.argv[2]),'utf8').replace(/^\uFEFF/,''));
for(const url of [config.baseUrl,config.proxyUrl]) assert.equal(new URL(url).hostname,'127.0.0.1');
const output=path.join(root,'local-private/stage2-acceptance-20261019');
if(fs.existsSync(path.join(output,'browser-result.json')))fs.copyFileSync(path.join(output,'browser-result.json'),path.join(output,'browser-result-'+Date.now()+'.json'));
const {chromium}=require(process.env.ARGUS_PLAYWRIGHT_MODULE||path.join(root,'local-private/relay-browser-check/node_modules/playwright'));
const report={startedAt:new Date().toISOString(),passed:false,actions:[],checks:[],pageErrors:[],scope:'ISOLATED_REAL_DOCKER'};
const save=()=>fs.writeFileSync(path.join(output,'browser-result.json'),JSON.stringify(report,null,2));
const check=(ok,name)=>{report.checks.push({name,passed:!!ok});save();assert.ok(ok,name)};
const pause=ms=>new Promise(resolve=>setTimeout(resolve,ms));
class TransientRead extends Error{}
async function until(fn,name,timeout=60000){const deadline=Date.now()+timeout;while(Date.now()<deadline){try{const value=await fn();if(value)return value}catch(error){if(!(error instanceof TransientRead))throw error}await pause(500)}throw Error(name)}
async function get(api){let response;try{response=await fetch(config.baseUrl+'/api/'+api,{headers:{Authorization:'Bearer '+config.tokens.operator}})}catch(error){throw new TransientRead('temporary read connection failure')}if([502,503,504].includes(response.status))throw new TransientRead('temporary read HTTP '+response.status);assert.equal(response.status,200,api);const body=await response.json();return body.data}
async function fault(mode){const response=await fetch(config.proxyUrl+'/__acceptance/fault',{method:'POST',headers:{Authorization:'Bearer '+config.tokens.proxy,'Content-Type':'application/json'},body:JSON.stringify({mode})});assert.equal(response.status,200);return response.json()}
async function proxyState(){const response=await fetch(config.proxyUrl+'/__acceptance/state',{headers:{Authorization:'Bearer '+config.tokens.proxy}});assert.equal(response.status,200);return response.json()}

(async()=>{
 const browser=await chromium.launch({channel:'msedge',headless:true});
 const context=await browser.newContext({viewport:{width:1440,height:1050},locale:'zh-CN',timezoneId:'Asia/Shanghai'});
 await context.addInitScript(token=>sessionStorage.setItem('argus.apiToken',token),config.tokens.operator);
 const page=await context.newPage(); page.on('pageerror',error=>report.pageErrors.push(error.message));
 try {
   await fault('normal');
   await page.goto(config.baseUrl+'/#/instances');
   await page.getByText('控制中心可访问',{exact:true}).waitFor({timeout:30000});
   check((await get('instances')).every(i=>i.id==='acceptance-instance'),'only explicitly allowed fixture is exposed');
   async function review(unknown){
     await page.getByRole('button',{name:'任务',exact:true}).click();
     await page.locator('[data-task-id="'+unknown.id+'"]').click();
     await page.getByRole('button',{name:'刷新核对记录',exact:true}).click();
     const evidenceButton=page.getByRole('button',{name:'读取确认前证据',exact:true});
     await until(()=>evidenceButton.isEnabled(),'review evidence unavailable');await evidenceButton.click();
     await until(async()=>{const evidence=await get('tasks/'+unknown.id+'/review-evidence');return evidence.canAcknowledge},'agent evidence does not permit review');
     await page.getByRole('textbox',{name:'核对原因',exact:true}).fill('隔离验收产生不确定结果；现读取并核对 Agent 原命令持久证据，保留原始记录。');
     await page.getByRole('textbox',{name:'人工证据',exact:true}).fill('已读取同一绑定的原命令记录与当前容器观测，确认执行器无活动进程；只核对原互斥，不重放命令，原 UNKNOWN 保留。');
     const boxes=page.locator('.review-form input[type=checkbox]');check(await boxes.count()===2,'review requires two explicit risk acknowledgements');
     await boxes.nth(0).check();await boxes.nth(1).check();
     const submit=page.getByRole('button',{name:'提交人工核对（不执行容器命令）',exact:true});
     await until(()=>submit.isEnabled(),'review confirmation not permitted');await submit.click();
     const resolution=await until(async()=>{const value=await get('tasks/'+unknown.id+'/resolution');return value&&value.status==='APPLIED'?value:null},'review not applied');
     check((await get('tasks/'+unknown.id)).status==='UNKNOWN','manual review preserves original UNKNOWN');
     report.resolution={id:resolution.id,status:resolution.status,originalTaskId:unknown.id};save();
     await page.screenshot({path:path.join(output,'unknown-review.png'),fullPage:true});
   }
   for(const task of await get('tasks'))if(task.status==='UNKNOWN'&&task.instanceId==='acceptance-instance'&&!(await get('tasks/'+task.id+'/resolution'))){await review(task);report.checks.push({name:'previous isolated UNKNOWN recovered through UI',passed:true});}
   if(process.argv.includes('--review-only')){report.passed=true;return;}
   async function action(name,expected='SUCCEEDED'){
     const deliveryBefore=await proxyState();
     await page.getByRole('button',{name:'实例',exact:true}).click();
     await page.getByRole('button',{name:/刷新数据/}).click();
     const labels={START:'启动',STOP:'停止',RESTART:'重启'};
     const fixture=(await get('instances')).find(i=>i.id==='acceptance-instance');
     const button=page.locator('[data-instance-id="acceptance-instance"]').getByRole('button',{name:labels[name]+' '+fixture.name,exact:true});
     await until(()=>button.isEnabled(),'fixture action disabled');
     await button.click(); await page.getByRole('dialog').waitFor();
     const response=page.waitForResponse(r=>r.request().method()==='POST'&&r.url().includes('/api/instances/acceptance-instance/actions'));
     await page.getByTestId('confirm-action').click();
     const accepted=await response;check(accepted.status()===202,'202 only acknowledges durable acceptance');
     const task=(await accepted.json()).data;
     assert.equal(task.instanceId,'acceptance-instance');assert.equal(task.executionMode,'DOCKER');
     const final=await until(async()=>{const value=await get('tasks/'+task.id);return value.status===expected?value:null},'task did not reach '+expected,60000);
     await page.getByRole('button',{name:'刷新详情',exact:true}).click();
     await page.locator('.task-detail .task-detail-body').waitFor();
     const events=await get('tasks/'+task.id+'/events');
     check(events.at(-1).toStatus===expected,'event timeline matches final state');
     const deliveryAfter=await proxyState();
     check(deliveryAfter.taskPosts-deliveryBefore.taskPosts===1,'one Agent POST for '+name+' task');
     report.actions.push({id:task.id,commandId:task.commandId,action:name,status:final.status,eventCount:events.length,agentPostDelta:deliveryAfter.taskPosts-deliveryBefore.taskPosts});save();
     return task;
   }
   async function networkRecovery(){
     await page.getByRole('button',{name:'实例',exact:true}).click();
     await page.getByRole('button',{name:/刷新数据/}).click();
     const fixture=(await get('instances')).find(i=>i.id==='acceptance-instance');
     const beforeTasks=await get('tasks'),beforeProxy=await proxyState();
     const pattern='**/api/instances/acceptance-instance/actions';
     let firstTask,firstKey;
     await page.route(pattern,async route=>{
       if(route.request().method()!=='POST')return route.continue();
       firstKey=route.request().headers()['idempotency-key'];
       const response=await route.fetch();assert.equal(response.status(),202);
       firstTask=(await response.json()).data;
       await route.abort('failed'); // Server accepted, but browser receives no confirmation.
     });
     const startButton=page.locator('[data-instance-id="acceptance-instance"]').getByRole('button',{name:'启动 '+fixture.name,exact:true});
     await until(()=>startButton.isEnabled(),'network fixture action disabled');await startButton.click();
     await page.getByTestId('confirm-action').evaluate(button=>{button.click();button.click()});
     await page.locator('.pending-intent').getByText('提交结果待确认',{exact:true}).waitFor();
     check(!!firstTask&&!!firstKey,'response loss happened after durable server acceptance');
     await page.unroute(pattern);
     await until(async()=>{const task=await get('tasks/'+firstTask.id);return task.status==='SUCCEEDED'},'lost-response task did not finish');
     await page.reload();
     const recover=page.getByRole('button',{name:'用原请求核对',exact:true});
     await until(()=>recover.isEnabled(),'reload did not recover original intent');
     const pending=page.waitForResponse(r=>r.request().method()==='POST'&&r.url().includes('/api/instances/acceptance-instance/actions'));
     await recover.click();const response=await pending;
     check(response.status()===202,'same-key reconciliation accepted');
     check(response.request().headers()['idempotency-key']===firstKey,'reload preserves exact original idempotency key');
     check((await response.json()).data.id===firstTask.id,'same-key duplicate returns the original task');
     check((await get('tasks')).filter(task=>!beforeTasks.some(previous=>previous.id===task.id)).length===1,'double click and response loss create exactly one task');
     check((await proxyState()).taskPosts-beforeProxy.taskPosts===1,'same-key reconciliation causes exactly one Agent delivery');
     report.actions.push({id:firstTask.id,commandId:firstTask.commandId,action:'START',status:'SUCCEEDED',scenario:'BROWSER_RESPONSE_LOSS_AND_DOUBLE_CLICK'});save();
     await action('STOP');
   }
   if(process.argv.includes('--network-only')){await networkRecovery();report.passed=true;return;}
   if(process.argv.includes('--restart-only')){await action('START');await action('RESTART');await action('STOP');report.passed=true;return;}
   await action('START'); await action('RESTART'); await action('STOP');
   const before=await proxyState();await fault('hide-task-result');
   const unknown=await action('START','UNKNOWN');
   const hidden=await proxyState();
   check(hidden.taskPosts-before.taskPosts===1,'lost acknowledgement causes no repeated agent POST');
   await fault('normal');
   await review(unknown);
   check((await proxyState()).taskPosts===hidden.taskPosts,'manual review performs no container action');
   await action('STOP');

   // The node health fault proves real monitoring trigger and recovery without touching the game servers.
   await fault('health-down');
   await until(async()=>{const value=await get('monitoring/summary');return value.alerts.some(a=>a.rule_key==='NODE_OFFLINE'&&a.resource_id==='acceptance-node'&&a.state==='ACTIVE')},'offline alert not raised',45000);
   await page.getByRole('button',{name:'监控告警',exact:true}).click();
   await page.getByRole('heading',{name:'监控与告警',exact:true}).waitFor();
   await until(()=>page.getByRole('button',{name:'刷新监控',exact:true}).isEnabled(),'monitoring initial render incomplete');
   await page.screenshot({path:path.join(output,'offline-alert.png'),fullPage:true});
   await fault('normal');
   await until(async()=>{const value=await get('monitoring/summary');return value.alerts.some(a=>a.rule_key==='NODE_OFFLINE'&&a.resource_id==='acceptance-node'&&a.state==='RESOLVED')},'offline alert did not recover',45000);
   await page.getByRole('button',{name:'刷新监控',exact:true}).click();
   await until(()=>page.getByRole('button',{name:'刷新监控',exact:true}).isEnabled(),'monitoring refresh incomplete');
   check((await page.locator('.monitor-alert').filter({hasText:'节点离线或快照过期'}).innerText()).includes('正常 / 已恢复'),'browser renders recovered node alarm');
   await page.screenshot({path:path.join(output,'monitoring-recovered.png'),fullPage:true});
   const summary=await get('monitoring/summary');report.monitoring={enabled:summary.enabled,alerts:summary.alerts,events:summary.events};
   check(report.pageErrors.length===0,'no browser page exceptions');
   report.passed=true;
 } catch(error){report.error=error.message;throw error}
 finally {save();await context.close();await browser.close()}
 console.log(JSON.stringify({passed:report.passed,checks:report.checks.length,actions:report.actions.length}));
})().catch(error=>{console.error(error.message);process.exitCode=1});
