package com.argus.controlcenter.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 同一规则/资源只保留一个当前告警，只有触发/恢复才追加事件。 */
@Service
public class MonitoringStore {
    private final JdbcTemplate jdbc;
    public MonitoringStore(JdbcTemplate jdbc){this.jdbc=jdbc;}
    private record Previous(String id,String state,int episodes){}
    @Transactional
    public void observe(String project,String rule,String resource,String title,String severity,Double value,double threshold,String summary,Instant now) {
        var rows=jdbc.query("SELECT id,state,episodes FROM monitor_alerts WHERE project_id=? AND rule_key=? AND resource_id=? FOR UPDATE",
                (rs,n)->new Previous(rs.getString(1),rs.getString(2),rs.getInt(3)),project,rule,resource);
        var previous=rows.isEmpty()?null:rows.get(0);
        String state=value==null?(previous==null?"UNKNOWN":previous.state()):(value>=threshold?"ACTIVE":"RESOLVED");
        String observed=value==null?"UNKNOWN":"KNOWN";
        boolean transition=value!=null && (state.equals("ACTIVE")?(previous==null || !previous.state().equals("ACTIVE")):(previous!=null && previous.state().equals("ACTIVE")));
        int episodes=(previous==null?0:previous.episodes())+(transition && state.equals("ACTIVE")?1:0);
        String id=previous==null?UUID.randomUUID().toString():previous.id();
        if(previous==null) {
            jdbc.update("INSERT INTO monitor_alerts(id,project_id,rule_key,resource_id,title,severity,state,observation_state,observed_value,threshold_value,summary,opened_at,resolved_at,checked_at,episodes) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    id,project,rule,resource,title,severity,state,observed,value,threshold,summary,state.equals("ACTIVE")?Timestamp.from(now):null,null,Timestamp.from(now),episodes);
        } else {
            jdbc.update("UPDATE monitor_alerts SET state=?,observation_state=?,observed_value=?,threshold_value=?,summary=?,checked_at=?,episodes=?,opened_at=CASE WHEN ? THEN ? ELSE opened_at END,resolved_at=CASE WHEN ? THEN ? WHEN ? THEN NULL ELSE resolved_at END WHERE id=?",
                    state,observed,value,threshold,summary,Timestamp.from(now),episodes,transition&&state.equals("ACTIVE"),Timestamp.from(now),transition&&state.equals("RESOLVED"),Timestamp.from(now),transition&&state.equals("ACTIVE"),id);
        }
        if(transition) jdbc.update("INSERT INTO monitor_alert_events(id,alert_id,project_id,state,metric_value,summary,occurred_at) VALUES(?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(),id,project,state,value,summary,Timestamp.from(now));
    }
    public void sample(String project,String resource,String metric,Double value,String source,Instant at){
        jdbc.update("INSERT INTO monitor_samples(id,project_id,resource_id,metric,metric_value,observation_state,source,sampled_at) VALUES(?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(),project,resource,metric,value,value==null?"UNKNOWN":"KNOWN",source,Timestamp.from(at));
    }
    public List<Map<String,Object>> alerts(String project){return jdbc.queryForList("SELECT * FROM monitor_alerts WHERE project_id=? ORDER BY CASE WHEN state='ACTIVE' THEN 0 WHEN observation_state='UNKNOWN' THEN 1 ELSE 2 END,checked_at DESC LIMIT 200",project);}
    public List<Map<String,Object>> events(String project){return jdbc.queryForList("SELECT * FROM monitor_alert_events WHERE project_id=? ORDER BY occurred_at DESC,id LIMIT 200",project);}
    public List<Map<String,Object>> history(String project,String resource,String metric,int limit){
        return jdbc.queryForList("SELECT metric_value,observation_state,source,sampled_at FROM monitor_samples WHERE project_id=? AND resource_id=? AND metric=? ORDER BY sampled_at DESC,id DESC LIMIT "+Math.max(1,Math.min(500,limit)),project,resource,metric);
    }
    public void prune(Instant cutoff){
        // 每轮最多清理 1000 条，防保留策略一次占用长事务。
        var ids=jdbc.queryForList("SELECT id FROM monitor_samples WHERE sampled_at<? ORDER BY sampled_at LIMIT 1000",String.class,Timestamp.from(cutoff));
        if(!ids.isEmpty()) jdbc.update("DELETE FROM monitor_samples WHERE id IN ("+String.join(",",java.util.Collections.nCopies(ids.size(),"?"))+")",ids.toArray());
    }
}
