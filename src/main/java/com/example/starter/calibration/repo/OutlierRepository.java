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
import com.example.starter.calibration.model.OutlierReview;
import com.example.starter.calibration.model.ReviewDecision;
import com.example.starter.calibration.model.ReviewStatus;

/**
 * 离群记录与复核持久化。离群记录封存目标值、阈值与读数，生成后不改写证据字段，仅推进状态；
 * 复核记录只增不改，STALE 复核保留但不计入法定人数。
 */
@Repository
public class OutlierRepository {

    private static final RowMapper<OutlierRecord> OUTLIER_MAPPER = (rs, rowNum) -> new OutlierRecord(
            rs.getLong("id"),
            rs.getLong("batch_id"),
            rs.getLong("reading_id"),
            rs.getInt("batch_revision"),
            rs.getBigDecimal("target_value"),
            rs.getBigDecimal("allowed_deviation"),
            rs.getBigDecimal("reading_value"),
            rs.getBigDecimal("actual_deviation"),
            rs.getBigDecimal("excess"),
            OutlierStatus.valueOf(rs.getString("status")),
            JdbcTimes.fromDb(rs.getObject("decided_at", LocalDateTime.class)),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private static final RowMapper<OutlierReview> REVIEW_MAPPER = (rs, rowNum) -> new OutlierReview(
            rs.getLong("id"),
            rs.getLong("outlier_id"),
            rs.getString("reviewer"),
            ReviewDecision.valueOf(rs.getString("decision")),
            rs.getString("reason"),
            rs.getInt("batch_revision"),
            rs.getLong("certificate_id"),
            rs.getInt("certificate_version"),
            ReviewStatus.valueOf(rs.getString("status")),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public OutlierRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入离群记录并返回生成的 ID。
     */
    public long insertOutlier(OutlierRecord outlier) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO outlier_record "
                            + "(batch_id, reading_id, batch_revision, target_value, allowed_deviation, "
                            + "reading_value, actual_deviation, excess, status, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, outlier.batchId());
            ps.setLong(2, outlier.readingId());
            ps.setInt(3, outlier.batchRevision());
            ps.setBigDecimal(4, outlier.targetValue());
            ps.setBigDecimal(5, outlier.allowedDeviation());
            ps.setBigDecimal(6, outlier.readingValue());
            ps.setBigDecimal(7, outlier.actualDeviation());
            ps.setBigDecimal(8, outlier.excess());
            ps.setString(9, outlier.status().name());
            ps.setObject(10, JdbcTimes.toDb(outlier.createdAt()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 按 ID 查询离群记录（不加锁）。
     */
    public Optional<OutlierRecord> findOutlierById(long id) {
        return jdbc.query("SELECT * FROM outlier_record WHERE id = ?", OUTLIER_MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 查询批次全部离群记录（按 ID 升序）。
     */
    public List<OutlierRecord> findOutliersByBatch(long batchId) {
        return jdbc.query("SELECT * FROM outlier_record WHERE batch_id = ? ORDER BY id", OUTLIER_MAPPER, batchId);
    }

    /**
     * 统计批次待裁决离群数量（须在持有批次行锁的事务内调用，保证与复核集合一致）。
     */
    public int countPendingByBatch(long batchId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outlier_record WHERE batch_id = ? AND status = 'PENDING'",
                Integer.class, batchId);
        return count == null ? 0 : count;
    }

    /**
     * 推进离群记录状态（裁决时调用，证据字段不改写）。
     */
    public void updateOutlierStatus(long id, OutlierStatus status, Instant decidedAt) {
        jdbc.update("UPDATE outlier_record SET status = ?, decided_at = ? WHERE id = ?",
                status.name(), JdbcTimes.toDb(decidedAt), id);
    }

    /**
     * 插入一条复核记录并返回生成的 ID。
     */
    public long insertReview(OutlierReview review) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO outlier_review "
                            + "(outlier_id, reviewer, decision, reason, batch_revision, "
                            + "certificate_id, certificate_version, status, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, review.outlierId());
            ps.setString(2, review.reviewer());
            ps.setString(3, review.decision().name());
            ps.setString(4, review.reason());
            ps.setInt(5, review.batchRevision());
            ps.setLong(6, review.certificateId());
            ps.setInt(7, review.certificateVersion());
            ps.setString(8, review.status().name());
            ps.setObject(9, JdbcTimes.toDb(review.createdAt()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 查询离群记录的全部复核（按 ID 升序，即提交顺序）。
     */
    public List<OutlierReview> findReviewsByOutlier(long outlierId) {
        return jdbc.query("SELECT * FROM outlier_review WHERE outlier_id = ? ORDER BY id",
                REVIEW_MAPPER, outlierId);
    }

    /**
     * 统计某离群记录下指定结论的有效复核数量。
     */
    public int countValidReviews(long outlierId, ReviewDecision decision) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outlier_review WHERE outlier_id = ? AND decision = ? AND status = 'VALID'",
                Integer.class, outlierId, decision.name());
        return count == null ? 0 : count;
    }

    /**
     * 判断复核人是否已对该离群记录提交过有效复核。
     */
    public boolean existsValidReviewByReviewer(long outlierId, String reviewer) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outlier_review WHERE outlier_id = ? AND reviewer = ? AND status = 'VALID'",
                Integer.class, outlierId, reviewer);
        return count != null && count > 0;
    }
}