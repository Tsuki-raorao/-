package com.argus.controlcenter.domain;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;

/** 规范化后不可变的人工决定；证据是纯文本，不读取URL或文件。 */
public record ReviewRequest(String decision,String reason,String evidence,
                            boolean acknowledgeNoReplay,boolean acknowledgeResidualRisk) {
    public static final String DECISION="ACKNOWLEDGE_UNCERTAINTY";
    public ReviewRequest {
        if(!DECISION.equals(decision)||!acknowledgeNoReplay||!acknowledgeResidualRisk)
            throw new IllegalArgumentException("REVIEW_ACKNOWLEDGEMENT_REQUIRED");
        reason=normalize(reason,500);evidence=normalize(evidence,4000);
    }
    public static ReviewRequest parse(JsonNode body) {
        if(body==null||!body.isObject()||body.size()!=5)throw new IllegalArgumentException("INVALID_REVIEW_REQUEST");
        Set<String> keys=Set.of("decision","reason","evidence","acknowledgeNoReplay","acknowledgeResidualRisk");
        body.fieldNames().forEachRemaining(k->{if(!keys.contains(k))throw new IllegalArgumentException("INVALID_REVIEW_REQUEST");});
        for(String k:keys) {
            if(k.startsWith("acknowledge")) {if(!body.path(k).isBoolean())throw new IllegalArgumentException("INVALID_REVIEW_REQUEST");}
            else if(!body.path(k).isTextual())throw new IllegalArgumentException("INVALID_REVIEW_REQUEST");
        }
        return new ReviewRequest(body.get("decision").textValue(),body.get("reason").textValue(),body.get("evidence").textValue(),
                body.get("acknowledgeNoReplay").booleanValue(),body.get("acknowledgeResidualRisk").booleanValue());
    }
    private static String normalize(String value,int limit) {
        if(value==null)throw new IllegalArgumentException("REVIEW_TEXT_REQUIRED");
        String text=trimEcma(value.replace("\r\n","\n").replace('\r','\n'));
        if(text.isEmpty()||text.codePointCount(0,text.length())>limit)throw new IllegalArgumentException("REVIEW_TEXT_LENGTH_INVALID");
        for(int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            if(c==0)throw new IllegalArgumentException("REVIEW_TEXT_INVALID");
            if(Character.isHighSurrogate(c)) {
                if(i+1==text.length()||!Character.isLowSurrogate(text.charAt(++i)))throw new IllegalArgumentException("REVIEW_TEXT_INVALID");
            } else if(Character.isLowSurrogate(c))throw new IllegalArgumentException("REVIEW_TEXT_INVALID");
        }
        return text;
    }
    /** 与浏览器String.trim完全相同的WhiteSpace+LineTerminator集合，避免strip的Unicode差异。 */
    private static String trimEcma(String text) {
        int first=0,last=text.length();
        while(first<last&&ecmaSpace(text.codePointAt(first)))first+=Character.charCount(text.codePointAt(first));
        while(last>first&&ecmaSpace(text.codePointBefore(last)))last-=Character.charCount(text.codePointBefore(last));
        return text.substring(first,last);
    }
    private static boolean ecmaSpace(int c) {
        return c>=0x9&&c<=0xd||c==0x20||c==0xa0||c==0x1680||c>=0x2000&&c<=0x200a
            ||c==0x2028||c==0x2029||c==0x202f||c==0x205f||c==0x3000||c==0xfeff;
    }
}
