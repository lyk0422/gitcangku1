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
 * 离群记录持久化。证据字段（目标值、允许偏差、读数、实际偏差）创建后不改写；
 * 仅裁决状态与裁决时间随有效裁决推进。
 */
@Repository
public class OutlierRepository {

    private static final RowMapper<OutlierRecord> MAPPER = (rs, rowNum) -> new OutlierRecord(
            rs.getLong("id"),
            rs.getLong("batch_id"),
            rs.getInt("batch_revision"),
            rs.getLong("reading_id"),
            rs.getBigDecimal("target_value"),
            rs.getBigDecimal("allowed_deviation"),
            rs.getBigDecimal("reading_value"),
            rs.getBigDecimal("actual_deviation"),
            OutlierStatus.valueOf(rs.getString("status")),
            JdbcTimes.fromDb(rs.getObject("detected_at", LocalDateTime.class)),
            JdbcTimes.fromDb(rs.getObject("verdict_at", LocalDateTime.class)));

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
                            + "(batch_id, batch_revision, reading_id, target_value, allowed_deviation, "
                            + "reading_value, actual_deviation, status, detected_at, verdict_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, outlier.batchId());
            ps.setInt(2, outlier.batchRevision());
            ps.setLong(3, outlier.readingId());
            ps.setBigDecimal(4, outlier.targetValue());
            ps.setBigDecimal(5, outlier.allowedDeviation());
            ps.setBigDecimal(6, outlier.readingValue());
            ps.setBigDecimal(7, outlier.actualDeviation());
            ps.setString(8, outlier.status().name());
            ps.setObject(9, JdbcTimes.toDb(outlier.detectedAt()));
            ps.setObject(10, JdbcTimes.toDb(outlier.verdictAt()));
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
     * 查询批次指定修订版本下的全部离群记录（按 ID 升序，即检测顺序）。
     */
    public List<OutlierRecord> findByBatchAndRevision(long batchId, int revision) {
        return jdbc.query("SELECT * FROM outlier_record WHERE batch_id = ? AND batch_revision = ? ORDER BY id",
                MAPPER, batchId, revision);
    }

    /**
     * 查询批次指定修订版本下仍未裁决（PENDING）的离群记录（按 ID 升序）。
     */
    public List<OutlierRecord> findPendingByBatchAndRevision(long batchId, int revision) {
        return jdbc.query("SELECT * FROM outlier_record "
                        + "WHERE batch_id = ? AND batch_revision = ? AND status = ? ORDER BY id",
                MAPPER, batchId, revision, OutlierStatus.PENDING.name());
    }

    /**
     * 推进裁决状态并记录裁决时间（须在持有批次行锁的裁决事务内调用）。
     */
    public void applyVerdict(long id, OutlierStatus status, Instant verdictAt) {
        jdbc.update("UPDATE outlier_record SET status = ?, verdict_at = ? WHERE id = ?",
                status.name(), JdbcTimes.toDb(verdictAt), id);
    }
}
