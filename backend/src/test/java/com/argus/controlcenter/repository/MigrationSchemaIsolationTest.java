package com.argus.controlcenter.repository;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;

class MigrationSchemaIsolationTest {
    @Test void v7DoesNotMistakeAnotherSchemaColumnForCurrentSchema() {
        var source=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1;MODE=MySQL","sa","");
        var jdbc=new JdbcTemplate(source);
        Flyway.configure().dataSource(source).target("6").load().migrate();
        jdbc.execute("CREATE SCHEMA shadow");
        for(String table:java.util.List.of("nodes","instances","tasks","logs","task_resolutions"))
            jdbc.execute("CREATE TABLE shadow.\""+table+"\"(\"project_id\" VARCHAR(36))");
        Flyway.configure().dataSource(source).load().migrate();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='PUBLIC' AND table_name='NODES' AND column_name='PROJECT_ID'",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='PUBLIC' AND table_name='TASKS' AND column_name='PROJECT_ID'",Integer.class)).isEqualTo(1);
    }
}
