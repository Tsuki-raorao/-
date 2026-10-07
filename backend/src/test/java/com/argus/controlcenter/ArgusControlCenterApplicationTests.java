package com.argus.controlcenter;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.http.ResponseEntity;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ArgusControlCenterApplicationTests {
    @Autowired private TestRestTemplate rest;
    @Test void healthAndSeedDataAreAvailable() {
        ResponseEntity<Map> health = rest.getForEntity("/api/health", Map.class);
        assertThat(health.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(health.getBody()).containsEntry("code", 0);
        assertThat((Map) health.getBody().get("data")).containsEntry("status", "UP").containsEntry("databaseReady", true);
        ResponseEntity<Map> instances = rest.getForEntity("/api/instances", Map.class);
        assertThat(instances.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(instances.getBody()).containsEntry("code", 0);
    }
}
