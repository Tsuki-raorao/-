'use strict';
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict'),crypto=require('node:crypto');
const root=path.resolve(__dirname,'../..'),cfg=JSON.parse(fs.readFileSync(path.resolve(process.argv[2]),'utf8').replace(/^\uFEFF/,''));
const out=path.join(root,'local-private/production-operations'),report={scope:'PRODUCTION_OPERATIONS_BROWSER',passed:false,checks:[],actions:[],pageErrors:[]};
const save=()=>fs.writeFileSync(path.join(out,'browser-result.json'),JSON.stringify(report,null,2));
function check(v,n){report.checks.push({name:n,passed:!!v});save();assert.ok(v,n)}
const pause=ms=>new Promise(r=>setTimeout(r,ms));
async function until(fn,n){const end=Date.now()+75000;while(Date.now()<end){const v=await fn();if(v)return v;await pause(500)}throw Error(n)}
async function request(api,token=cfg.operatorToken,options={}){return fetch(cfg.baseUrl+'/api/'+api,{...options,headers:{Authorization:'Bearer '+token,'Content-Type':'application/json',...(options.headers||{})}})}
async function get(api,token){const res=await request(api,token);assert.equal(res.status,200,api);return (await res.json()).data}
(async()=>{
 const {chromium}=require(path.join(root,'local-private/relay-browser-check/node_modules/playwright'));
 const browser=await chromium.launch({channel:'msedge',headless:true});const context=await browser.newContext({viewport:{width:1440,height:1000},locale:'zh-CN'});
 await context.addInitScript(t=>sessionStorage.setItem('argus.apiToken',t),cfg.operatorToken);
 const page=await context.newPage();page.on('pageerror',e=>report.pageErrors.push(e.message));
 try{
  const h=await get('health');check(h.status==='UP'&&h.readOnly===false,'public backend has operations enabled');
  const caps=await get('control/capabilities');check(caps.controlEnabled&&caps.canControl,'operator capabilities enabled');
  const allowed=caps.targets.filter(t=>t.allowedActions.length);check(allowed.length===9,'nine explicit business/test targets enabled');
  const read=await get('control/capabilities',cfg.readToken);check(!read.canControl&&read.targets.every(t=>!t.allowedActions.length),'original reader remains read-only');
  const fixture=(await get('instances')).find(i=>i.id===cfg.fixtureId);assert.equal(fixture.agentInstanceId,'argus-acceptance-fixture');
  const denied=await request('instances/'+cfg.fixtureId+'/actions',cfg.readToken,{method:'POST',headers:{'Idempotency-Key':crypto.randomUUID()},body:JSON.stringify({action:'START',expectedExecutionMode:'DOCKER'})});
  check(denied.status===403,'reader cannot execute even though global operations enabled');
  const infra=(await get('instances')).find(i=>i.agentInstanceId==='argus-rabbitmq-prod');
  const forbidden=await request('instances/'+infra.id+'/actions',cfg.operatorToken,{method:'POST',headers:{'Idempotency-Key':crypto.randomUUID()},body:JSON.stringify({action:'STOP',expectedExecutionMode:'DOCKER'})});
  check(forbidden.status===403&&(await forbidden.json()).message==='TARGET_NOT_ALLOWED','production Broker excluded and server rejects action');
  await page.goto(cfg.baseUrl+'/');await page.getByRole('button',{name:/进入控制台/}).first().click();await page.getByText('控制中心可访问',{exact:true}).waitFor();
  const labels={START:'启动',STOP:'停止',RESTART:'重启'};
  for(const name of ['START','RESTART','STOP']){
   await page.getByRole('button',{name:'实例',exact:true}).click();await page.getByRole('button',{name:/刷新数据/}).click();
   const row=page.locator('[data-instance-id="'+cfg.fixtureId+'"]');const button=row.getByRole('button',{name:labels[name]+' '+fixture.name,exact:true});
   await until(()=>button.isEnabled(),'action button disabled');await button.click();await page.getByRole('dialog').waitFor();
   check((await page.getByRole('dialog').innerText()).includes(cfg.fixtureId),'confirmation identifies exact test target '+name);
   const response=page.waitForResponse(r=>r.request().method()==='POST'&&r.url().includes('/api/instances/'+cfg.fixtureId+'/actions'));
   await page.getByTestId('confirm-action').click();const res=await response;check(res.status()===202,'durable 202 acceptance '+name);
   const task=(await res.json()).data;assert.equal(task.instanceId,cfg.fixtureId);
   const final=await until(async()=>{const t=await get('tasks/'+task.id);if(['FAILED','UNKNOWN'].includes(t.status))throw Error('task '+t.status);return t.status==='SUCCEEDED'?t:null},'task timeout');
   const events=await get('tasks/'+task.id+'/events');check(events.at(-1).toStatus==='SUCCEEDED','persisted successful timeline '+name);
   const submitted={id:task.id,commandId:task.commandId,action:name,status:final.status,eventCount:events.length};report.actions.push(submitted);save();
   await page.getByRole('button',{name:'刷新详情',exact:true}).click();await page.locator('.task-detail .task-detail-body').waitFor();
   await page.screenshot({path:path.join(out,'operation-'+name.toLowerCase()+'.png'),fullPage:true});
  }
  const tasks=await get('tasks');check(report.actions.every(a=>tasks.some(t=>t.id===a.id&&t.status==='SUCCEEDED')),'all three production tasks retained');
  await page.reload();await page.getByText('控制中心可访问',{exact:true}).waitFor();check((await get('control/capabilities')).canControl,'capabilities remain enabled after reload');
  check(report.pageErrors.length===0,'no browser runtime errors');report.passed=true;
 }finally{save();await browser.close()}
})().catch(e=>{save();console.error(e.message);process.exitCode=1});
