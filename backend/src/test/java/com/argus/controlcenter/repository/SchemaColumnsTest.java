package com.argus.controlcenter.repository;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;

class SchemaColumnsTest {
    @Test void matchesCaseAndDoesNotConfuseAnotherSchema() {
        var source=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");
        var jdbc=new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE nodes(id VARCHAR(64))");
        jdbc.execute("CREATE SCHEMA other");
        jdbc.execute("CREATE TABLE other.nodes(project_id VARCHAR(36))");
        assertThat(SchemaColumns.has(jdbc,"nodes","PROJECT_ID")).isFalse();
        jdbc.execute("ALTER TABLE nodes ADD COLUMN project_id VARCHAR(36)");
        assertThat(SchemaColumns.has(jdbc,"NODES","project_id")).isTrue();
    }
}
