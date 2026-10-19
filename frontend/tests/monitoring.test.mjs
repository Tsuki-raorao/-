import test from 'node:test'
import assert from 'node:assert/strict'
import { loadTs } from './load-ts.mjs'
const { monitoringHistory, chartSegments, monitoringSummary, observationUnknown } = await loadTs('monitoring')
test('未知指标保持空值并断开折线，真实零值保留', () => {
  const points = monitoringHistory([{ VALUE: 7, OBSERVATION_STATE: 'KNOWN', SAMPLED_AT: '2026-01-01T00:02:00Z' },{VALUE:999,OBSERVATION_STATE:'UNKNOWN'},{VALUE:0,OBSERVATION_STATE:'KNOWN'}])
  assert.equal(points[0].value,0); assert.equal(points[1].value,null)
  assert.equal(chartSegments(points).length,2)
})
test('告警活动状态与当前未知状态分别保留，坏响应拒绝', () => {
  const state=monitoringSummary({enabled:true,alerts:[{STATE:'ACTIVE',OBSERVATION_STATE:'UNKNOWN',LAST_VALUE:null}],events:[]})
  assert.equal(state.alerts[0].state,'ACTIVE'); assert.equal(state.alerts[0].value,null)
  assert.throws(()=>monitoringSummary({alerts:[]}))
})
test('监控评估停止后，旧正常状态不能继续当成已验证正常',()=>{
  const at=Date.parse('2026-10-19T00:00:00Z')
  const alert={observation:'KNOWN',checkedAt:new Date(at).toISOString()}
  assert.equal(observationUnknown(alert,at+30000),false)
  assert.equal(observationUnknown(alert,at+90001),true)
})
