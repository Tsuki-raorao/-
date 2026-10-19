'use strict';
// Production read-only browser verification. Never send actions, reviews or AI messages.
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const root=path.resolve(__dirname,'../..');
const config=JSON.parse(fs.readFileSync(path.resolve(process.argv[2]),'utf8').replace(/^\uFEFF/,''));
assert.equal(config.scope,'PRODUCTION_READ_ONLY');
const out=path.join(root,'local-private/stage2-acceptance-20261019');
const {chromium}=require(path.join(root,'local-private/relay-browser-check/node_modules/playwright'));
const report={scope:'PRODUCTION_READ_ONLY_BROWSER',passed:false,checks:[],pageErrors:[],unexpectedWrites:[]};
const save=()=>fs.writeFileSync(path.join(out,'production-browser.json'),JSON.stringify(report,null,2));
const check=(value,name)=>{report.checks.push({name,passed:!!value});save();assert.ok(value,name)};
const pause=ms=>new Promise(resolve=>setTimeout(resolve,ms));
async function until(fn,reason){const end=Date.now()+45000;while(Date.now()<end){if(await fn())return;await pause(500)}throw Error(reason)}
async function get(api){const response=await fetch(config.baseUrl+'/api/'+api,{headers:{Authorization:'Bearer '+config.token}});assert.equal(response.status,200,api);const value=await response.json();assert.equal(value.code,0);return value.data}

(async()=>{
 const browser=await chromium.launch({channel:'msedge',headless:true});
 const context=await browser.newContext({viewport:{width:1440,height:1000},locale:'zh-CN',timezoneId:'Asia/Shanghai'});
 await context.addInitScript(token=>sessionStorage.setItem('argus.apiToken',token),config.token);
 const page=await context.newPage();page.on('pageerror',error=>report.pageErrors.push(error.message));
 page.on('request',request=>{if(!['GET','HEAD','OPTIONS'].includes(request.method()))report.unexpectedWrites.push({method:request.method(),path:new URL(request.url()).pathname})});
 try{
   const unauth=await fetch(config.baseUrl+'/api/instances');check(unauth.status===401,'protected API rejects missing token');
   for(const port of [80,9090]){
     const entry=new URL(config.baseUrl);entry.port=String(port);
     const response=await fetch(entry.origin+'/api/health');const value=await response.json();
     check(response.status===200&&value.data.status==='UP'&&value.data.readOnly===true,'public IP entry '+port+' reaches read-only backend');
   }
   const health=await get('health');check(health.status==='UP'&&health.readOnly===true,'live backend and read-only health');
   const nodes=await get('nodes'),instances=await get('instances'),tasks=await get('tasks');
   check(nodes.length>0&&instances.length>0,'live inventory is populated');
   report.inventory={nodes:nodes.length,instances:instances.length,tasks:tasks.length,onlineNodes:nodes.filter(n=>n.status==='ONLINE').length,realHostNodes:nodes.filter(n=>n.dataSource==='HOST').length,legacyMockNodes:nodes.filter(n=>n.dataSource==='MOCK').length};save();
   await page.goto(config.baseUrl+'/');
   await page.getByRole('heading',{level:1,name:/让每一台服务器/}).waitFor();
   check(await page.locator('.landing-brand img').evaluate(img=>img.complete&&img.naturalWidth>0),'homepage logo loads');
   await until(()=>page.locator('.hero-art img').evaluate(img=>img.complete&&img.naturalWidth>0),'mascot not loaded');
   await page.screenshot({path:path.join(out,'production-homepage.png'),fullPage:true});
   await page.getByRole('button',{name:/进入控制台/}).first().click();
   await page.getByText('控制中心可访问',{exact:true}).waitFor();
   await page.getByText('当前为只读模式：可以查询快照和 Agent 最近日志，写操作关闭。',{exact:true}).waitFor();
   check(true,'console shows live connection and explicit read-only boundary');
   if(nodes.some(node=>node.dataSource==='MOCK')){await page.getByText('页面包含模拟数据，相关条目已标记，不代表真实服务器状态。',{exact:true}).waitFor();check(true,'legacy mock records are explicitly labeled');}
   await page.getByRole('button',{name:'节点',exact:true}).click();
   check(await page.locator('[data-node-id]').count()===nodes.length,'node UI agrees with API count');
   await page.getByRole('button',{name:'实例',exact:true}).click();
   check(await page.locator('[data-instance-id]').count()===instances.length,'instance UI agrees with API count');
   const actionButtons=page.locator('.control-actions button').filter({hasNotText:'查看阻塞任务'});
   const states=await actionButtons.evaluateAll(buttons=>buttons.map(button=>button.disabled));
   check(states.length>0&&states.every(Boolean),'write buttons respect server capability');
   await page.screenshot({path:path.join(out,'production-instances.png'),fullPage:true});
   await page.getByRole('button',{name:'任务',exact:true}).click();
   check(await page.locator('[data-task-id]').count()===tasks.length,'task page matches actual history, no demo substitution');
   const target=instances.find(i=>i.name==='mc01'&&i.status==='RUNNING')||instances.find(i=>i.status==='RUNNING'&&i.dataSource==='DOCKER');
   assert.ok(target,'running Docker instance for read-only logs');
   await page.getByRole('button',{name:'日志',exact:true}).click();
   await page.locator('#log-instance').selectOption(target.id);
   await until(()=>page.getByRole('button',{name:'读取日志',exact:true}).isEnabled(),'log read disabled');
   const responsePromise=page.waitForResponse(r=>r.url().includes('/api/instances/'+target.id+'/logs')&&r.request().method()==='GET');
   await page.getByRole('button',{name:'读取日志',exact:true}).click();
   const response=await responsePromise;check(response.status()===200,'real Agent log read succeeds');
   const logs=(await response.json()).data;check(logs.instanceId===target.id&&logs.agentInstanceId===target.agentInstanceId,'log identity matches selected instance');
   report.logLineCount=logs.lines.length;save();
   await page.getByRole('button',{name:'监控告警',exact:true}).click();
   await page.getByRole('heading',{name:'监控与告警',exact:true}).waitFor();
   await until(()=>page.getByRole('button',{name:'刷新监控',exact:true}).isEnabled(),'monitor panel not loaded');
   const summary=await get('monitoring/summary');
   check(summary.enabled===true&&summary.alerts.length>0,'production monitor evaluates rules');
   check(await page.locator('.monitor-alert').count()===summary.alerts.length,'monitor UI agrees with live API');
   const fresh=nodes.find(n=>n.dataSource==='HOST'&&Number.isFinite(n.cpuPercent)&&n.syncStatus==='OK');
   if(fresh){await page.getByRole('combobox',{name:'监控节点',exact:true}).selectOption(fresh.id);await until(()=>page.getByRole('button',{name:'刷新监控',exact:true}).isEnabled(),'history refresh not complete');
     const history=await get('monitoring/history?resourceId='+encodeURIComponent(fresh.id)+'&metric=CPU_PERCENT');
     check(history.length>0&&history.some(p=>p.observation_state==='KNOWN'&&typeof p.metric_value==='number'),'real HOST metric history available');}
   else throw Error('no real HOST node for metric history');
   report.monitor={alerts:summary.alerts.length,activeRules:summary.alerts.filter(a=>a.state==='ACTIVE').map(a=>a.rule_key),historyVerified:true};
   await page.screenshot({path:path.join(out,'production-monitoring.png'),fullPage:true});
   await page.reload();await page.getByRole('heading',{name:'监控与告警',exact:true}).waitFor();
   await until(()=>page.getByRole('button',{name:'刷新监控',exact:true}).isEnabled(),'monitor reload incomplete');
   check(report.pageErrors.length===0,'no uncaught browser exceptions');
   check(report.unexpectedWrites.length===0,'browser verification issued only read requests');
   report.passed=true;
 }catch(error){report.error=error.message;throw error}
 finally{save();await context.close();await browser.close()}
 console.log(JSON.stringify({passed:report.passed,checks:report.checks.length,inventory:report.inventory,monitor:report.monitor}));
})().catch(error=>{console.error(error.message);process.exitCode=1});
