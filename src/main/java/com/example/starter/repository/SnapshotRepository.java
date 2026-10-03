package com.example.starter.repository;

import com.example.starter.domain.SnapshotRecord;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 交接快照表 handover_snapshot 的 JDBC 仓储。
 */
@Repository
public class SnapshotRepository {

    private static final RowMapper<SnapshotRecord> MAPPER = (rs, n) -> new SnapshotRecord(
            rs.getLong("id"),
            rs.getLong("batch_id"),
            rs.getLong("cell_id"),
            rs.getString("cell_code"),
            rs.getLong("reading_id"),
            rs.getBigDecimal("voltage"),
            rs.getTimestamp("measured_at").toInstant());

    private final JdbcTemplate jdbc;

    public SnapshotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 交接时冻结一条电芯清单快照（含终测读数编号）。
     */
    public void insert(long batchId, long cellId, String cellCode, long readingId,
                       BigDecimal voltage, Instant measuredAt) {
        jdbc.update("INSERT INTO handover_snapshot"
                        + " (batch_id, cell_id, cell_code, reading_id, voltage, measured_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                batchId, cellId, cellCode, readingId, voltage, Timestamp.from(measuredAt));
    }

    /**
     * 查询批次的交接快照清单。
     */
    public List<SnapshotRecord> findByBatchId(long batchId) {
        return jdbc.query("SELECT * FROM handover_snapshot WHERE batch_id = ? ORDER BY id",
                MAPPER, batchId);
    }
}