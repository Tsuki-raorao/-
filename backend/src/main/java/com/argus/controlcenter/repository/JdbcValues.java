package com.argus.controlcenter.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

/** JDBC 数字必须保留 SQL NULL，不能由 getLong/getDouble 偷换成零。 */
final class JdbcValues {
    private JdbcValues() { }
    static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
    static Double decimal(ResultSet rs, String column) throws SQLException {
        Number value = (Number) rs.getObject(column);
        return value == null ? null : value.doubleValue();
    }
    static Long integer(ResultSet rs, String column) throws SQLException {
        Number value = (Number) rs.getObject(column);
        return value == null ? null : value.longValue();
    }
}