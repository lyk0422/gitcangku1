package com.example.starter.repository;

import com.example.starter.domain.IsolationRecord;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 隔离记录表 cell_isolation 的 JDBC 仓储。
 */
@Repository
public class IsolationRepository {

    private static final RowMapper<IsolationRecord> MAPPER = (rs, n) -> new IsolationRecord(
            rs.getLong("id"),
            rs.getLong("cell_id"),
            rs.getString("reason"),
            rs.getTimestamp("isolated_at").toInstant());

    private final JdbcTemplate jdbc;

    public IsolationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 记录一次隔离（原因与 UTC 时刻）。
     */
    public long insert(long cellId, String reason, Instant isolatedAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO cell_isolation (cell_id, reason, isolated_at) VALUES (?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, cellId);
            ps.setString(2, reason);
            ps.setTimestamp(3, Timestamp.from(isolatedAt));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 查询电芯最近一次隔离记录。
     */
    public Optional<IsolationRecord> findLatestByCellId(long cellId) {
        List<IsolationRecord> rows = jdbc.query(
                "SELECT * FROM cell_isolation WHERE cell_id = ? ORDER BY id DESC LIMIT 1", MAPPER, cellId);
        return rows.stream().findFirst();
    }
}