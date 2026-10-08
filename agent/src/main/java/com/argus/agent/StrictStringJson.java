package com.argus.agent;

import java.util.LinkedHashMap;
import java.util.Map;

/** 任务只需要字符串/null 对象，必须完整解析而非正则抽取。 */
final class StrictStringJson {
    private final String input; private int index;
    private StrictStringJson(String input) { this.input = input == null ? "" : input; }
    static Map<String, String> parse(String input) { return new StrictStringJson(input).object(); }
    private Map<String, String> object() {
        Map<String, String> values = new LinkedHashMap<>();
        space(); expect('{'); space();
        if (take('}')) { space(); end(); return values; }
        while (true) {
            space(); String key = string(); space(); expect(':'); space();
            String value;
            if (input.startsWith("null", index)) { index += 4; value = null; }
            else value = string();
            if (values.containsKey(key)) throw invalid();
            values.put(key, value); space();
            if (take('}')) break;
            expect(',');
        }
        space(); end(); return values;
    }
    private String string() {
        expect('"'); StringBuilder value = new StringBuilder();
        while (index < input.length()) {
            char c = input.charAt(index++);
            if (c == '"') return value.toString();
            if (c < 32) throw invalid();
            if (c != '\\') { value.append(c); continue; }
            if (index >= input.length()) throw invalid();
            char escape = input.charAt(index++);
            switch (escape) {
                case '"', '\\', '/' -> value.append(escape);
                case 'b' -> value.append('\b'); case 'f' -> value.append('\f');
                case 'n' -> value.append('\n'); case 'r' -> value.append('\r'); case 't' -> value.append('\t');
                case 'u' -> {
                    if (index + 4 > input.length()) throw invalid();
                    String hex = input.substring(index, index + 4);
                    if (!hex.matches("[0-9a-fA-F]{4}")) throw invalid();
                    value.append((char) Integer.parseInt(hex, 16)); index += 4;
                }
                default -> throw invalid();
            }
        }
        throw invalid();
    }
    private void space() { while (index < input.length() && " \t\r\n".indexOf(input.charAt(index)) >= 0) index++; }
    private boolean take(char c) { if (index < input.length() && input.charAt(index) == c) { index++; return true; } return false; }
    private void expect(char c) { if (!take(c)) throw invalid(); }
    private void end() { if (index != input.length()) throw invalid(); }
    private IllegalArgumentException invalid() { return new IllegalArgumentException("invalid string JSON object"); }
}
