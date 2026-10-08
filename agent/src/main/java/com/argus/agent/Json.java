package com.argus.agent;

import java.util.LinkedHashMap;
import java.util.Map;

/** Small JSON helper for the deliberately dependency-free agent API. */
public final class Json {
    private Json() { }

    /** 用于把已经验证过的 JSON 片段嵌入 object；普通字符串仍会被安全转义。 */
    public record Raw(String json) { }

    public static Raw raw(String json) { return new Raw(json); }

    public static String escape(String value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.toString();
    }
    public static String object(Object... values) {
        StringBuilder b = new StringBuilder("{");
        for (int i = 0; i < values.length; i += 2) {
            if (i > 0) b.append(',');
            b.append('"').append(escape(String.valueOf(values[i]))).append('"').append(':');
            Object value = values[i + 1];
            if (value == null) b.append("null");
            else if (value instanceof Raw raw) b.append(raw.json());
            // JSON 不支持 NaN/Infinity，异常指标以缺失值表示，不能输出非法 JSON。
            else if (value instanceof Double d && !Double.isFinite(d)
                    || value instanceof Float f && !Float.isFinite(f)) b.append("null");
            else if (value instanceof Number || value instanceof Boolean) b.append(value);
            else b.append('"').append(escape(String.valueOf(value))).append('"');
        }
        return b.append('}').toString();
    }
    public static String array(Iterable<String> values) {
        StringBuilder b = new StringBuilder("["); int i = 0;
        for (String value : values) { if (i++ > 0) b.append(','); b.append('"').append(escape(value)).append('"'); }
        return b.append(']').toString();
    }
    /** Parses a flat JSON object; enough for task and heartbeat request DTOs. */
    public static Map<String, String> flatObject(String body) {
        return StrictStringJson.parse(body);
    }
}
