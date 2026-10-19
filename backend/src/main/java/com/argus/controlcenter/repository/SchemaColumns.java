package com.argus.controlcenter.repository;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

/** 元数据必须限定当前 schema：MySQL DATABASE() 与 H2 SCHEMA() 的含义不同。 */
final class SchemaColumns {
    private SchemaColumns() { }
    static boolean has(JdbcTemplate jdbc, String table, String column) {
        return Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) connection -> {
            String schema = connection.getMetaData().getDatabaseProductName().equalsIgnoreCase("MySQL")
                    ? "DATABASE()" : "SCHEMA()";
            try (var statement = connection.prepareStatement("SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA="
                    + schema + " AND LOWER(TABLE_NAME)=LOWER(?) AND LOWER(COLUMN_NAME)=LOWER(?)")) {
                statement.setString(1, table); statement.setString(2, column);
                try (var rows = statement.executeQuery()) { return rows.next() && rows.getInt(1)>0; }
            }
        }));
    }
}
