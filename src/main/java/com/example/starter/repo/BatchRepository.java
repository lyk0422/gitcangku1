package com.example.starter.repo;

import com.example.starter.domain.BatchStatus;
import com.example.starter.domain.Rows.BatchRow;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** 放电批次存取。 */
@Repository
public class BatchRepository {

    private static final RowMapper<BatchRow> MAPPER = (rs, n) -> new BatchRow(
            rs.getString("batch_code"),
            BatchStatus.valueOf(rs.getString("status")),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public BatchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 新建进行中批次。 */
    public void insert(BatchRow row) {
        jdbc.update("INSERT INTO discharge_batch (batch_code, status, created_at) VALUES (?,?,?)",
                row.batchCode(), row.status().name(), Timestamp.from(row.createdAt()));
    }

    /** 按编号查询批次。 */
    public Optional<BatchRow> find(String batchCode) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT batch_code, status, created_at FROM discharge_batch WHERE batch_code = ?",
                    MAPPER, batchCode));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** 按编号查询并锁定批次行（同事务内串行化入批与交接）。 */
    public Optional<BatchRow> findForUpdate(String batchCode) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT batch_code, status, created_at FROM discharge_batch WHERE batch_code = ? FOR UPDATE",
                    MAPPER, batchCode));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** 更新批次状态。 */
    public void updateStatus(String batchCode, BatchStatus status) {
        jdbc.update("UPDATE discharge_batch SET status = ? WHERE batch_code = ?",
                status.name(), batchCode);
    }
}