const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const root=path.resolve(__dirname,'../..'),privateDir=path.join(root,'local-private/production-operations');
const cfg=JSON.parse(fs.readFileSync(path.join(privateDir,'browser-private.json'),'utf8'));
const domains=process.argv.slice(2);assert.equal(domains.length,2);for(const d of domains)assert.match(d,/^[a-z0-9.-]+$/);
const report={scope:'DOMAIN_HTTPS_ICP_BROWSER',passed:false,checks:[],pageErrors:[],unexpectedWrites:[]};
const save=()=>fs.writeFileSync(path.join(privateDir,'domain-browser.json'),JSON.stringify(report,null,2));
function check(v,n){report.checks.push({name:n,passed:!!v});save();assert.ok(v,n)}
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
async function until(fn,n){const end=Date.now()+45000;while(Date.now()<end){if(await fn())return;await sleep(500)}throw Error(n)}
(async()=>{
 const {chromium}=require(path.join(root,'local-private/relay-browser-check/node_modules/playwright'));
 const browser=await chromium.launch({channel:'msedge',headless:true});
 try{
 for(let di=0;di<domains.length;di++)for(const width of [1440,390]){
  const base='https://'+domains[di],label=['apex','www'][di]+' '+width;
  const context=await browser.newContext({viewport:{width,height:1000},locale:'zh-CN',timezoneId:'Asia/Shanghai'});
  await context.addInitScript(t=>sessionStorage.setItem('argus.apiToken',t),cfg.operatorToken);
  const page=await context.newPage();page.on('pageerror',e=>report.pageErrors.push(e.message));
  page.on('request',r=>{if(!['GET','HEAD','OPTIONS'].includes(r.method()))report.unexpectedWrites.push({method:r.method(),path:new URL(r.url()).pathname})});
  await page.goto('http://'+domains[di]+'/');check(page.url()===base+'/','browser HTTP redirects to trusted HTTPS '+label);
  const icp=page.getByRole('link',{name:'闽ICP备2026040659号',exact:true});await icp.waitFor();check(await icp.getAttribute('href')==='https://beian.miit.gov.cn/','homepage ICP and official link '+label);
  const police=page.getByRole('link',{name:'闽公网安备35082402000177号',exact:true});await police.waitFor();check(await police.getAttribute('href')==='https://beian.mps.gov.cn/#/query/webSearch?domain=wx-s.cn','homepage public-security filing and official link '+label);
  check(await page.locator('.landing-brand img').evaluate(i=>i.complete&&i.naturalWidth>0),'homepage logo '+label);
  check(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),'homepage fits viewport '+label);
  await page.getByRole('button',{name:/进入控制台/}).first().click();await page.getByText('控制中心可访问',{exact:true}).waitFor();
  const health=await page.evaluate(async()=>{const r=await fetch('/api/health');return (await r.json()).data});check(health.status==='UP'&&!health.readOnly,'HTTPS console backend and operations mode '+label);
  await page.locator('.console-footer .filing-records').waitFor();check((await page.locator('.console-footer .filing-records').innerText()).includes('闽ICP备2026040659号')&&(await page.locator('.console-footer .filing-records').innerText()).includes('闽公网安备35082402000177号'),'console ICP and public-security footer '+label);
  await page.getByRole('button',{name:'实例',exact:true}).click();await until(async()=>await page.locator('[data-instance-id]').count()>0,'missing live instances');
  const caps=await page.evaluate(async()=>{const r=await fetch('/api/control/capabilities',{headers:{Authorization:'Bearer '+sessionStorage.getItem('argus.apiToken')}});return (await r.json()).data});check(caps.canControl&&caps.targets.filter(t=>t.allowedActions.length).length===9,'HTTPS authenticated operation permissions '+label);
  const row=page.locator('[data-instance-id="'+cfg.fixtureId+'"]');const button=row.getByRole('button',{name:'启动 argus-acceptance-fixture',exact:true});await until(()=>button.isEnabled(),'fixture button disabled');await button.click();await page.getByRole('dialog').waitFor();await page.getByRole('button',{name:'取消',exact:true}).click();check(await page.getByRole('dialog').count()===0,'operation confirmation usable; canceled before any command '+label);
  await page.getByRole('button',{name:'监控告警',exact:true}).click();await page.getByRole('heading',{name:'监控与告警',exact:true}).waitFor();check(true,'monitoring page over HTTPS '+label);
  await page.screenshot({path:path.join(privateDir,'domain-'+di+'-'+width+'.png'),fullPage:true});await context.close();
 }
 check(report.pageErrors.length===0,'no browser runtime exceptions');check(report.unexpectedWrites.length===0,'browser audit submitted no service actions');report.passed=true;
 }finally{save();await browser.close()}
})().catch(e=>{save();console.error(e.message);process.exitCode=1});
