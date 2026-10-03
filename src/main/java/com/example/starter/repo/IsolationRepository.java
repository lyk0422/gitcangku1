package com.example.starter.repo;

import com.example.starter.domain.Rows.IsolationRow;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** 电芯隔离记录存取。 */
@Repository
public class IsolationRepository {

    private static final RowMapper<IsolationRow> MAPPER = (rs, n) -> new IsolationRow(
            rs.getLong("id"),
            rs.getString("cell_code"),
            rs.getString("batch_code"),
            rs.getString("reason"),
            rs.getTimestamp("isolated_at").toInstant());

    private final JdbcTemplate jdbc;

    public IsolationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 追加隔离记录。 */
    public void insert(String cellCode, String batchCode, String reason, Instant isolatedAt) {
        jdbc.update(
                "INSERT INTO cell_isolation (cell_code, batch_code, reason, isolated_at)"
                        + " VALUES (?,?,?,?)",
                cellCode, batchCode, reason, Timestamp.from(isolatedAt));
    }

    /** 查询批次全部隔离记录（按隔离时刻升序）。 */
    public List<IsolationRow> findByBatch(String batchCode) {
        return jdbc.query(
                "SELECT id, cell_code, batch_code, reason, isolated_at FROM cell_isolation"
                        + " WHERE batch_code = ? ORDER BY isolated_at, id",
                MAPPER, batchCode);
    }
}