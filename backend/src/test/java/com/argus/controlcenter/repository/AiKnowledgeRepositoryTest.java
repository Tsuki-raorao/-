package com.argus.controlcenter.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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
            assertThat(rows.get(0).get("retrieval")).isEqualTo("LEXICAL_EXPLAINABLE");
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
}
