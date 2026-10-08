package com.argus.controlcenter.service;

import com.argus.controlcenter.exception.TaskControlException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** 只投递核对；丢回包先查固定回执，绝不重投原命令。 */
@Service
public class TaskReviewWorker {
    private final TaskReviewStore store;private final TaskReviewGateway gateway;private final TaskReviewPolicy policy;
    public TaskReviewWorker(TaskReviewStore store,TaskReviewGateway gateway,TaskReviewPolicy policy){this.store=store;this.gateway=gateway;this.policy=policy;}
    @Scheduled(initialDelayString="${argus.task-review.initial-delay-ms:5000}",fixedDelayString="${argus.task-review.poll-interval-ms:1000}")
    public void runOnce(){store.claim().ifPresent(this::process);}
    public void process(TaskReviewStore.Claim claim) {
        var row=claim.row();
        try {
            policy.require(true,row.original().task().getInstanceId());
            String denial=store.eligibility(row.original(),false);
            if(denial!=null){store.failure(claim,denial,false);return;}
            gateway.health(row.original());
            var prior=gateway.query(row);
            if(prior.status()==200){store.apply(claim,gateway.validateReceipt(row,prior.body()));return;}
            if(prior.status()!=404){status(claim,prior.status());return;}
            var original=gateway.task(row.original());
            if(original.status()==404){store.failure(claim,"AGENT_RECORD_LOST",false);return;}
            if(original.status()!=200){status(claim,original.status());return;}
            gateway.validateTask(row.original(),original.body());
            if(java.util.Set.of("PENDING","RUNNING").contains(TaskReviewGateway.text(original.body(),"status"))){store.failure(claim,"REVIEW_EXECUTOR_ACTIVE",false);return;}
            if(!store.beforePost(claim))return;
            var response=gateway.post(row);
            if(response.status()==200||response.status()==201)store.apply(claim,gateway.validateReceipt(row,response.body()));
            else status(claim,response.status());
        }catch(TaskControlException error) {
            String code=error.getMessage();
            boolean retry=java.util.Set.of("AGENT_REQUEST_UNCONFIRMED","AGENT_REQUEST_INTERRUPTED","AGENT_HEALTH_UNAVAILABLE").contains(code);
            store.failure(claim,code,retry);
        }
    }
    private void status(TaskReviewStore.Claim claim,int status) {
        String code=status==401||status==403?"AGENT_REVIEW_ACCESS_DENIED":status==404?"AGENT_RECORD_LOST":status==409?"AGENT_REVIEW_REJECTED":"AGENT_REVIEW_UNCONFIRMED";
        store.failure(claim,code,status>=500||status==429);
    }
}
