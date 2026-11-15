package com.portfolio.deployops.repository;

import java.sql.*;
import java.util.Arrays;
import java.util.List;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

/** Strict numeric JSON array; avoids global collection type-handler registration. */
public class LongIdListTypeHandler extends BaseTypeHandler<List<Long>> {
    @Override public void setNonNullParameter(PreparedStatement ps, int i, List<Long> ids, JdbcType jdbcType) throws SQLException {
        if (ids.stream().anyMatch(id -> id == null || id <= 0)) throw new SQLException("Invalid server group IDs");
        ps.setString(i, ids.toString());
    }
    @Override public List<Long> getNullableResult(ResultSet rs, String name) throws SQLException { return parse(rs.getString(name)); }
    @Override public List<Long> getNullableResult(ResultSet rs, int i) throws SQLException { return parse(rs.getString(i)); }
    @Override public List<Long> getNullableResult(CallableStatement cs, int i) throws SQLException { return parse(cs.getString(i)); }
    private List<Long> parse(String value) throws SQLException {
        if (value == null) return null;
        if (!value.matches("\\s*\\[\\s*(?:[1-9][0-9]*(?:\\s*,\\s*[1-9][0-9]*)*)?\\s*]\\s*"))
            throw new SQLException("Invalid persisted server group IDs");
        String body = value.trim().substring(1, value.trim().length() - 1).trim();
        if (body.isEmpty()) return List.of();
        try { return Arrays.stream(body.split(",")).map(String::trim).map(Long::valueOf).toList(); }
        catch (NumberFormatException error) { throw new SQLException("Invalid persisted server group IDs", error); }
    }
}
