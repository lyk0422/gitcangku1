package com.example.starter.calibration.repo;

import java.sql.PreparedStatement;
import java.sql.Statement;
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
 * 离群记录持久化。检测时封存目标值、允许偏差、读数与实际偏差；仅状态与裁决时间可更新。
 */
@Repository
public class OutlierRepository {

    private static final RowMapper<OutlierRecord> MAPPER = (rs, rowNum) -> new OutlierRecord(
            rs.getLong("id"),
            rs.getLong("batch_id"),
            rs.getLong("reading_id"),
            rs.getInt("batch_revision"),
            rs.getBigDecimal("target_value"),
            rs.getBigDecimal("tolerance"),
            rs.getBigDecimal("reading_value"),
            rs.getBigDecimal("deviation"),
            OutlierStatus.valueOf(rs.getString("status")),
            JdbcTimes.fromDb(rs.getObject("detected_at", LocalDateTime.class)),
            JdbcTimes.fromDb(rs.getObject("adjudicated_at", LocalDateTime.class)));

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
                            + "(batch_id, reading_id, batch_revision, target_value, tolerance, "
                            + "reading_value, deviation, status, detected_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, outlier.batchId());
            ps.setLong(2, outlier.readingId());
            ps.setInt(3, outlier.batchRevision());
            ps.setBigDecimal(4, outlier.targetValue());
            ps.setBigDecimal(5, outlier.tolerance());
            ps.setBigDecimal(6, outlier.readingValue());
            ps.setBigDecimal(7, outlier.deviation());
            ps.setString(8, outlier.status().name());
            ps.setObject(9, JdbcTimes.toDb(outlier.detectedAt()));
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
     * 查询批次全部离群记录（按 ID 升序，即检测顺序）。
     */
    public List<OutlierRecord> findByBatchId(long batchId) {
        return jdbc.query("SELECT * FROM outlier_record WHERE batch_id = ? ORDER BY id", MAPPER, batchId);
    }

    /**
     * 统计批次待裁决离群记录数（须在持有批次行锁的事务内调用，保证与复核集合一致）。
     */
    public int countPendingByBatchId(long batchId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outlier_record WHERE batch_id = ? AND status = ?",
                Integer.class, batchId, OutlierStatus.PENDING.name());
        return count == null ? 0 : count;
    }

    /**
     * 裁决后更新状态与裁决时间（须在持有批次行锁的事务内调用）。
     */
    public void markAdjudicated(long id, OutlierStatus status, java.time.Instant adjudicatedAt) {
        jdbc.update("UPDATE outlier_record SET status = ?, adjudicated_at = ? WHERE id = ?",
                status.name(), JdbcTimes.toDb(adjudicatedAt), id);
    }
}