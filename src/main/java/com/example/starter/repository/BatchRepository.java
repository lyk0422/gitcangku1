package com.example.starter.repository;

import com.example.starter.domain.BatchRecord;
import com.example.starter.domain.BatchStatus;
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
 * 批次表 discharge_batch 的 JDBC 仓储。
 */
@Repository
public class BatchRepository {

    private static final RowMapper<BatchRecord> MAPPER = (rs, n) -> new BatchRecord(
            rs.getLong("id"),
            rs.getString("batch_no"),
            BatchStatus.valueOf(rs.getString("status")),
            rs.getTimestamp("created_at").toInstant(),
            rs.getString("handover_from"),
            rs.getString("handover_to"),
            rs.getTimestamp("handover_at") == null ? null : rs.getTimestamp("handover_at").toInstant());

    private final JdbcTemplate jdbc;

    public BatchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 新建批次，返回生成主键。
     */
    public long insert(String batchNo, Instant createdAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO discharge_batch (batch_no, status, created_at) VALUES (?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, batchNo);
            ps.setString(2, BatchStatus.OPEN.name());
            ps.setTimestamp(3, Timestamp.from(createdAt));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按批次编号查询。
     */
    public Optional<BatchRecord> findByBatchNo(String batchNo) {
        List<BatchRecord> rows = jdbc.query(
                "SELECT * FROM discharge_batch WHERE batch_no = ?", MAPPER, batchNo);
        return rows.stream().findFirst();
    }

    /**
     * 按批次编号查询并加行锁（FOR UPDATE），用于串行化交接、作废、隔离等竞争操作。
     */
    public Optional<BatchRecord> findByBatchNoForUpdate(String batchNo) {
        List<BatchRecord> rows = jdbc.query(
                "SELECT * FROM discharge_batch WHERE batch_no = ? FOR UPDATE", MAPPER, batchNo);
        return rows.stream().findFirst();
    }

    /**
     * 交接成功时冻结批次状态与交接双方、UTC 时刻。
     */
    public void markHandedOver(long id, String from, String to, Instant handoverAt) {
        jdbc.update("UPDATE discharge_batch SET status = ?, handover_from = ?, handover_to = ?, handover_at = ?"
                        + " WHERE id = ?",
                BatchStatus.HANDED_OVER.name(), from, to, Timestamp.from(handoverAt), id);
    }

    /**
     * 按主键查询批次。
     */
    public Optional<BatchRecord> findById(long id) {
        List<BatchRecord> rows = jdbc.query("SELECT * FROM discharge_batch WHERE id = ?", MAPPER, id);
        return rows.stream().findFirst();
    }}