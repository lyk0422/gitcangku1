package com.example.starter.archival.repo;

import com.example.starter.archival.domain.BatchStatus;
import com.example.starter.archival.domain.DeacidificationBatch;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 脱酸批次仓储。
 */
@Repository
public class BatchRepository {

    private static final RowMapper<DeacidificationBatch> MAPPER = (rs, rowNum) -> {
        Timestamp sealedAt = rs.getTimestamp("sealed_at");
        return new DeacidificationBatch(
                rs.getLong("id"),
                rs.getString("batch_no"),
                BatchStatus.valueOf(rs.getString("status")),
                sealedAt == null ? null : sealedAt.toLocalDateTime(),
                rs.getTimestamp("created_at").toLocalDateTime());
    };

    private final JdbcTemplate jdbc;

    public BatchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 建批，状态 OPEN，返回主键。 */
    public long insert(String batchNo, LocalDateTime createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO deacidification_batch (batch_no, status, created_at) VALUES (?, 'OPEN', ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, batchNo);
            ps.setTimestamp(2, Timestamp.valueOf(createdAt));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /** 按主键查询。 */
    public Optional<DeacidificationBatch> findById(long id) {
        List<DeacidificationBatch> list = jdbc.query(
                "SELECT id, batch_no, status, sealed_at, created_at FROM deacidification_batch WHERE id = ?",
                MAPPER, id);
        return list.stream().findFirst();
    }

    /** 按批次号查询。 */
    public Optional<DeacidificationBatch> findByBatchNo(String batchNo) {
        List<DeacidificationBatch> list = jdbc.query(
                "SELECT id, batch_no, status, sealed_at, created_at FROM deacidification_batch WHERE batch_no = ?",
                MAPPER, batchNo);
        return list.stream().findFirst();
    }

    /**
     * 按批次号加行级锁查询（FOR UPDATE），并发封存/退回/归还按事务提交顺序串行化。
     */
    public Optional<DeacidificationBatch> findByBatchNoForUpdate(String batchNo) {
        List<DeacidificationBatch> list = jdbc.query(
                "SELECT id, batch_no, status, sealed_at, created_at FROM deacidification_batch "
                        + "WHERE batch_no = ? FOR UPDATE",
                MAPPER, batchNo);
        return list.stream().findFirst();
    }

    /** 更新批次状态；封存时同时写封存时间。 */
    public int updateStatus(long batchId, BatchStatus status, LocalDateTime sealedAt) {
        return jdbc.update(
                "UPDATE deacidification_batch SET status = ?, sealed_at = ? WHERE id = ?",
                status.name(), sealedAt == null ? null : Timestamp.valueOf(sealedAt), batchId);
    }
}
