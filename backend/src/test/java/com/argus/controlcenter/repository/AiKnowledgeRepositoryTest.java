package com.argus.controlcenter.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Verifies deterministic, explainable and project-scoped knowledge retrieval. */
@SpringBootTest
@ActiveProfiles("test")
class AiKnowledgeRepositoryTest {
    @Autowired private AiKnowledgeRepository repository;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void ranksTitleAndCjkBigramsAndDoesNotCrossProject() {
        String project = "knowledge-ranking-project";
        String other = "knowledge-other-project";
        String first = repository.save(project, "节点状态排查", "runbook://node-status", "1", "节点状态需要结合最近心跳和采样时间判断。");
        repository.save(project, "日志采集", "runbook://logs", "1", "日志采集失败时先检查 Agent 连接。");
        repository.save(other, "节点状态排查", "runbook://other", "1", "其他项目的节点状态证据。");
        try {
            List<Map<String, Object>> rows = repository.search(project, "节点状态", 10);
            assertThat(rows).isNotEmpty();
            assertThat(rows.get(0).get("document_id")).isEqualTo(first);
            assertThat(rows.get(0).get("retrieval")).isEqualTo("HYBRID_LEXICAL_COSINE");
            assertThat(rows.get(0).get("semanticScore")).isInstanceOf(Number.class);
            assertThat(rows.get(0).get("matchedTerms").toString()).contains("节点", "状态");
            assertThat(rows).allMatch(row -> !other.equals(row.get("document_id")));
        } finally {
            jdbc.update("DELETE FROM ai_knowledge_chunks WHERE project_id IN (?,?)", project, other);
            jdbc.update("DELETE FROM ai_knowledge_documents WHERE project_id IN (?,?)", project, other);
        }
    }

    @Test
    void newerVersionReplacesOnlySameSourceAndOldVersionIsHidden() {
        String project = "knowledge-version-project";
        repository.save(project, "运维手册", "runbook://versioned", "1", "旧版本节点重启流程。");
        repository.save(project, "运维手册", "runbook://versioned", "2", "新版本节点重启流程。");
        try {
            List<Map<String, Object>> rows = repository.search(project, "节点重启", 10);
            assertThat(rows).isNotEmpty();
            assertThat(rows).allMatch(row -> "2".equals(String.valueOf(row.get("VERSION")))
                    || "2".equals(String.valueOf(row.get("version"))));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_knowledge_documents WHERE project_id=? AND status='DEPRECATED'",
                    Integer.class, project)).isEqualTo(1);
        } finally {
            jdbc.update("DELETE FROM ai_knowledge_chunks WHERE project_id=?", project);
            jdbc.update("DELETE FROM ai_knowledge_documents WHERE project_id=?", project);
        }
    }

    @Test
    void savedChunkHasDeterministicLocalEmbeddingAndModelMarker() {
        String project = "knowledge-embedding-project";
        repository.save(project, "Agent 手册", "runbook://embedding-a", "1", "节点心跳采样与日志状态。");
        repository.save(project, "Agent 手册", "runbook://embedding-b", "1", "节点心跳采样与日志状态。");
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT embedding_json,embedding_model FROM ai_knowledge_chunks WHERE project_id=? ORDER BY embedding_json",
                    project);
            assertThat(rows).hasSize(2);
            assertThat(rows.get(0).get("EMBEDDING_MODEL")).isEqualTo("local-hash-v1");
            assertThat(rows.get(0).get("EMBEDDING_JSON")).isEqualTo(rows.get(1).get("EMBEDDING_JSON"));
            assertThat(String.valueOf(rows.get(0).get("EMBEDDING_JSON")).split(",")).hasSize(64);
        } finally {
            jdbc.update("DELETE FROM ai_knowledge_chunks WHERE project_id=?", project);
            jdbc.update("DELETE FROM ai_knowledge_documents WHERE project_id=?", project);
        }
    }

    @Test
    void saveRollsBackDeprecationWhenVersionInsertFails() {
        String project = "knowledge-transaction-project";
        repository.save(project, "手册", "runbook://transaction", "1", "初始内容");
        try {
            assertThatThrownBy(() -> repository.save(project, "手册", "runbook://transaction", "1", "重复版本"))
                    .isInstanceOf(RuntimeException.class);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_knowledge_documents WHERE project_id=? AND status='ACTIVE'",
                    Integer.class, project)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_knowledge_documents WHERE project_id=? AND status='DEPRECATED'",
                    Integer.class, project)).isZero();
        } finally {
            jdbc.update("DELETE FROM ai_knowledge_chunks WHERE project_id=?", project);
            jdbc.update("DELETE FROM ai_knowledge_documents WHERE project_id=?", project);
        }
    }
}
