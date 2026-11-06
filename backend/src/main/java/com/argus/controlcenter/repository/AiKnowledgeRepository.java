package com.argus.controlcenter.repository;

import com.argus.controlcenter.config.AiProperties;
import com.argus.controlcenter.service.AiEmbeddingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Project-scoped knowledge storage with deterministic, explainable hybrid retrieval. */
@Repository
public class AiKnowledgeRepository {
    /** Kept as a compatibility marker for callers that used the original local provider constant. */
    static final String EMBEDDING_MODEL = AiEmbeddingService.LOCAL_MODEL;
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[\\p{L}\\p{N}_-]+");

    private final JdbcTemplate jdbc;
    private final AiEmbeddingService embeddingService;

    @Autowired
    public AiKnowledgeRepository(JdbcTemplate jdbc, AiEmbeddingService embeddingService) {
        this.jdbc = jdbc;
        this.embeddingService = embeddingService;
    }

    /** Compatibility constructor for small standalone repository tests; Spring uses the injected provider. */
    public AiKnowledgeRepository(JdbcTemplate jdbc) {
        this(jdbc, new AiEmbeddingService(new AiProperties(), new ObjectMapper()));
    }

    /** Replaces only the active version from the same source as one transaction. */
    @Transactional
    public String save(String project, String title, String source, String version, String content) {
        String id = UUID.randomUUID().toString();
        String hash = sha(content);
        Instant now = Instant.now();
        jdbc.update("UPDATE ai_knowledge_documents SET status='DEPRECATED',updated_at=? WHERE project_id=? AND source_uri=? AND status='ACTIVE'",
                Timestamp.from(now), project, source);
        jdbc.update("INSERT INTO ai_knowledge_documents(id,project_id,title,source_uri,version,status,content_hash,created_at,updated_at) VALUES(?,?,?,?,?,'ACTIVE',?,?,?)",
                id, project, title, source, version, hash, Timestamp.from(now), Timestamp.from(now));
        int no = 0;
        for (int start = 0; start < content.length(); start += 1800) {
            String chunk = content.substring(start, Math.min(content.length(), start + 1800));
            AiEmbeddingService.Embedding embedding = embeddingService.embed(title + "\n" + chunk);
            jdbc.update("INSERT INTO ai_knowledge_chunks(id,document_id,project_id,chunk_no,content,content_hash,created_at,embedding_json,embedding_model) VALUES(?,?,?,?,?,?,?,?,?)",
                    UUID.randomUUID().toString(), id, project, no++, chunk, sha(chunk), Timestamp.from(now),
                    vectorJson(embedding.vector()), embedding.model());
        }
        return id;
    }

    /**
     * Returns project-scoped chunks ranked by lexical evidence plus local cosine
     * similarity. The model is deterministic and carries no external data.
     */
    public List<Map<String, Object>> search(String project, String query, int limit) {
        List<String> terms = tokenize(query);
        if (terms.isEmpty()) return List.of();
        double[] queryVector = embeddingService.embed(query).vector();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT d.id AS document_id,d.title,d.source_uri,d.version,d.updated_at AS document_updated_at,"
                        + "c.id AS chunk_id,c.chunk_no,c.content,c.embedding_json,c.embedding_model "
                        + "FROM ai_knowledge_documents d JOIN ai_knowledge_chunks c ON c.document_id=d.id "
                        + "WHERE d.project_id=? AND d.status='ACTIVE' ORDER BY d.updated_at DESC,c.chunk_no", project);
        List<Map<String, Object>> matches = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String title = text(row, "title").toLowerCase(Locale.ROOT);
            String content = text(row, "content").toLowerCase(Locale.ROOT);
            String searchable = title + " " + content;
            List<String> matched = terms.stream().filter(searchable::contains).toList();
            int lexicalScore = matched.stream().mapToInt(term -> {
                int hits = occurrences(content, term);
                int titleHits = occurrences(title, term);
                return Math.min(hits, 5) + Math.min(titleHits, 3) * 3;
            }).sum();
            double cosine = cosine(queryVector, parseVector(text(row, "embedding_json")));
            if (lexicalScore == 0 && cosine <= 0.05d) continue;
            double score = lexicalScore + cosine * 2d;
            row.put("lexicalScore", lexicalScore);
            row.put("semanticScore", round(cosine));
            row.put("score", round(score));
            row.put("matchedTerms", matched);
            row.put("retrieval", cosine > 0d ? "HYBRID_LEXICAL_COSINE" : "LEXICAL_EXPLAINABLE");
            matches.add(row);
        }
        matches.sort(Comparator
                .comparingDouble((Map<String, Object> row) -> ((Number) row.get("score")).doubleValue()).reversed()
                .thenComparing(row -> text(row, "document_updated_at"), Comparator.reverseOrder())
                .thenComparingInt(row -> Integer.parseInt(text(row, "chunk_no"))));
        int safeLimit = Math.max(1, Math.min(limit, 20));
        return matches.subList(0, Math.min(safeLimit, matches.size()));
    }

    private List<String> tokenize(String query) {
        if (query == null || query.isBlank()) return List.of();
        Set<String> terms = new LinkedHashSet<>();
        Matcher matcher = TOKEN_PATTERN.matcher(query.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String token = matcher.group();
            if (token.length() > 1) terms.add(token);
            for (int i = 0; i + 1 < token.length(); i++) {
                if (isCjk(token.charAt(i)) && isCjk(token.charAt(i + 1))) {
                    terms.add(token.substring(i, i + 2));
                }
            }
        }
        return List.copyOf(terms);
    }

    private String vectorJson(double[] vector) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) json.append(',');
            json.append(Float.toString((float) vector[i]));
        }
        return json.append(']').toString();
    }

    private double[] parseVector(String json) {
        if (json == null || json.isBlank()) return new double[0];
        String body = json.trim();
        if (body.length() < 2 || body.charAt(0) != '[' || body.charAt(body.length() - 1) != ']') return new double[0];
        String inner = body.substring(1, body.length() - 1).trim();
        if (inner.isEmpty()) return new double[0];
        String[] values = inner.split(",");
        double[] vector = new double[values.length];
        try {
            for (int i = 0; i < values.length; i++) vector[i] = Double.parseDouble(values[i]);
            return vector;
        } catch (NumberFormatException ignored) {
            return new double[0];
        }
    }

    private double cosine(double[] left, double[] right) {
        if (left.length == 0 || right.length == 0) return 0d;
        int overlap = Math.min(left.length, right.length);
        double dot = 0d;
        double leftNorm = 0d;
        double rightNorm = 0d;
        for (int i = 0; i < left.length; i++) leftNorm += left[i] * left[i];
        for (int i = 0; i < right.length; i++) rightNorm += right[i] * right[i];
        for (int i = 0; i < overlap; i++) dot += left[i] * right[i];
        if (leftNorm == 0d || rightNorm == 0d) return 0d;
        return Math.max(0d, dot / Math.sqrt(leftNorm * rightNorm));
    }

    private byte[] digest(String value) {
        return digestBytes(value);
    }

    private byte[] digestBytes(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private int occurrences(String text, String term) {
        int hits = 0;
        int at = 0;
        while ((at = text.indexOf(term, at)) >= 0) {
            hits++;
            at += Math.max(1, term.length());
        }
        return hits;
    }

    private boolean isCjk(char c) {
        return (c >= 0x3400 && c <= 0x4dbf) || (c >= 0x4e00 && c <= 0x9fff) || (c >= 0xf900 && c <= 0xfaff);
    }

    private String text(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) value = row.get(key.toUpperCase(Locale.ROOT));
        return value == null ? "" : String.valueOf(value);
    }

    private double round(double value) {
        return Math.round(value * 1_000_000d) / 1_000_000d;
    }

    private String sha(String value) {
        return HexFormat.of().formatHex(digestBytes(value));
    }
}
