package com.argus.controlcenter.service;

import com.argus.controlcenter.domain.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** 跨模块固定字段顺序和UTF-8长度前缀；不依赖JSON格式或有歧义的分隔符。 */
public final class ResolutionHash {
    private ResolutionHash() { }
    public static String evidence(String text){return sha(text.getBytes(StandardCharsets.UTF_8));}
    public static Map<String,Object> payload(String id,TaskCommand original,ReviewRequest request) {
        Map<String,Object> p=new LinkedHashMap<>();
        p.put("resolutionId",id);p.put("instanceId",original.task().getAgentInstanceId());
        p.put("action",original.task().getAction().toLowerCase(Locale.ROOT));p.put("expectedNodeId",original.agentNodeId());
        p.put("expectedStoreId",original.storeId());p.put("expectedExecutionMode",original.task().getExecutionMode());
        p.put("expectedCommandExpiresAt",original.expiresAt().toString());p.put("decision",request.decision());
        p.put("reason",request.reason());p.put("evidenceDigest",evidence(request.evidence()));
        p.put("acknowledgeNoReplay",true);p.put("acknowledgeResidualRisk",true);return p;
    }
    public static String request(String id,TaskCommand original,ReviewRequest request) {
        List<String> fields=new ArrayList<>(List.of("argus-resolution-v1",original.task().getCommandId()));
        payload(id,original,request).values().forEach(v->fields.add(v.toString()));
        return prefixHash(fields);
    }
    public static String prefixHash(List<String> fields) {
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            for(String field:fields){byte[] bytes=field.getBytes(StandardCharsets.UTF_8);digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());digest.update(bytes);}
            return HexFormat.of().formatHex(digest.digest());
        } catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    public static String sha(byte[] bytes) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
}
