package com.example.starter.archival.repo;

import com.example.starter.archival.domain.BatchRejection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 质检退回理由仓储，只追加。
 */
@Repository
public class RejectionRepository {

    private static final RowMapper<BatchRejection> MAPPER = (rs, rowNum) -> new BatchRejection(
            rs.getLong("id"),
            rs.getLong("batch_id"),
            rs.getString("reason"),
            rs.getTimestamp("created_at").toLocalDateTime());

    private final JdbcTemplate jdbc;

    public RejectionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 追加一条退回理由，旧理由保留。 */
    public int insert(long batchId, String reason, LocalDateTime createdAt) {
        return jdbc.update(
                "INSERT INTO batch_rejection (batch_id, reason, created_at) VALUES (?, ?, ?)",
                batchId, reason, Timestamp.valueOf(createdAt));
    }

    /** 查询批次的全部退回理由，按时间升序。 */
    public List<BatchRejection> findByBatch(long batchId) {
        return jdbc.query(
                "SELECT id, batch_id, reason, created_at FROM batch_rejection WHERE batch_id = ? ORDER BY id",
                MAPPER, batchId);
    }
}
