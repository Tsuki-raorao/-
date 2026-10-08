package com.argus.agent;

import com.argus.agent.model.*;
import com.argus.agent.service.*;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.time.Instant;

/** 真实进程绕过 finally 退出，分别留下提交前临时片段和已同步回执，验证恢复边界。 */
public final class TaskReviewCrashChild {
    public static void main(String[] args)throws Exception{
        Path directory=Path.of(args[1]);String id=Files.readString(directory.resolve("command-id.txt"));
        ResolutionRequest request=ResolutionRequest.parse(id,Files.readString(directory.resolve("review-request.json")));
        try(TaskInbox inbox=new TaskInbox(directory,100)){
            if(args[0].equals("before")){
                try(FileChannel file=FileChannel.open(directory.resolve(".write-crash.tmp"),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){
                    file.write(ByteBuffer.wrap(new byte[]{1,2,3,4}));file.force(true);
                }
            }else{
                String time=Instant.now().toString();
                ResolutionReceipt receipt=new ResolutionReceipt(request,inbox.get(id).task(),time,"ACKNOWLEDGED_UNKNOWN","PRIOR_PROCESS_UNVERIFIED","UNAVAILABLE",null,time);
                inbox.putResolution(receipt);
            }
            Runtime.getRuntime().halt(49);
        }
    }
}
