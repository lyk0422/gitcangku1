package com.example.starter.calibration.repo;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.OutlierRecord;
import com.example.starter.calibration.model.OutlierStatus;

/**
 * 离群记录持久化。检测时封存目标值、阈值与读数，后续不回写封存字段。
 */
@Repository
public class OutlierRepository {

    private static final RowMapper<OutlierRecord> MAPPER = (rs, rowNum) -> new OutlierRecord(
            rs.getLong("id"),
            rs.getLong("batch_id"),
            rs.getLong("reading_id"),
            rs.getBigDecimal("target_value"),
            rs.getBigDecimal("tolerance"),
            rs.getBigDecimal("reading_value"),
            rs.getBigDecimal("computed_value"),
            rs.getBigDecimal("deviation"),
            OutlierStatus.valueOf(rs.getString("status")),
            JdbcTimes.fromDb(rs.getObject("verdict_at", LocalDateTime.class)),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public OutlierRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入离群记录并返回生成的 ID。
     */
    public long insert(OutlierRecord outlier) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO outlier_record "
                            + "(batch_id, reading_id, target_value, tolerance, reading_value, computed_value, "
                            + "deviation, status, verdict_at, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, outlier.batchId());
            ps.setLong(2, outlier.readingId());
            ps.setBigDecimal(3, outlier.targetValue());
            ps.setBigDecimal(4, outlier.tolerance());
            ps.setBigDecimal(5, outlier.readingValue());
            ps.setBigDecimal(6, outlier.computedValue());
            ps.setBigDecimal(7, outlier.deviation());
            ps.setString(8, outlier.status().name());
            ps.setObject(9, JdbcTimes.toDb(outlier.verdictAt()));
            ps.setObject(10, JdbcTimes.toDb(outlier.createdAt()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 按 ID 查询（不加锁）。
     */
    public Optional<OutlierRecord> findById(long id) {
        return jdbc.query("SELECT * FROM outlier_record WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 查询批次全部离群记录（按检测顺序）。
     */
    public List<OutlierRecord> findByBatchId(long batchId) {
        return jdbc.query("SELECT * FROM outlier_record WHERE batch_id = ? ORDER BY id", MAPPER, batchId);
    }

    /**
     * 统计批次内待裁决离群记录数量，用于放行阻断判断。
     */
    public int countPendingByBatchId(long batchId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outlier_record WHERE batch_id = ? AND status = ?",
                Integer.class, batchId, OutlierStatus.PENDING.name());
        return count == null ? 0 : count;
    }

    /**
     * 写入裁决结果（须在持有批次行锁的事务内调用）。
     */
    public void markVerdict(long id, OutlierStatus status, Instant verdictAt) {
        jdbc.update("UPDATE outlier_record SET status = ?, verdict_at = ? WHERE id = ?",
                status.name(), JdbcTimes.toDb(verdictAt), id);
    }
}