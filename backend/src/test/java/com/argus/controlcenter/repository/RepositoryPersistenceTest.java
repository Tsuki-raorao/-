package com.argus.controlcenter.repository;

import com.argus.controlcenter.domain.Node;
import com.argus.controlcenter.domain.NodeStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies that Repository instances read committed rows from the database rather than process memory. */
@SpringBootTest
@ActiveProfiles("test")
class RepositoryPersistenceTest {
    @Autowired private NodeRepository repository;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void rowSurvivesRepositoryRecreation() {
        String id = "persistence-test-node";
        jdbc.update("DELETE FROM nodes WHERE id=?", id);
        Node node = new Node(id, "Persistent node", "192.0.2.103", NodeStatus.ONLINE, Instant.now(), 12.5, 1024, 4096);
        repository.save(node);

        NodeRepository reopenedRepository = new NodeRepository(jdbc);
        assertThat(reopenedRepository.findById(id)).isPresent();
        Node restored = reopenedRepository.findById(id).orElseThrow();
        assertThat(restored.getName()).isEqualTo("Persistent node");
        assertThat(restored.getCpuPercent()).isEqualTo(12.5);
        assertThat(restored.getMemoryBytes()).isEqualTo(1024);
        assertThat(restored.getMemoryTotalBytes()).isEqualTo(4096);
    }
}
