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
import com.example.starter.calibration.model.OutlierReview;
import com.example.starter.calibration.model.OutlierStatus;
import com.example.starter.calibration.model.ReviewDecision;
import com.example.starter.calibration.model.ReviewStatus;

/**
 * 离群记录与复核记录持久化。离群封存字段不可变，仅裁决字段一次性流转；
 * 复核记录只增不改，STALE 复核不计入法定人数。
 */
@Repository
public class OutlierRepository {

    private static final RowMapper<OutlierRecord> OUTLIER_MAPPER = (rs, rowNum) -> new OutlierRecord(
            rs.getLong("id"),
            rs.getLong("batch_id"),
            rs.getLong("reading_id"),
            rs.getInt("batch_revision"),
            rs.getLong("certificate_id"),
            rs.getBigDecimal("target_value"),
            rs.getBigDecimal("allowed_deviation"),
            rs.getBigDecimal("reading_value"),
            rs.getBigDecimal("deviation"),
            rs.getString("submitted_by"),
            OutlierStatus.valueOf(rs.getString("status")),
            rs.getString("verdict") == null ? null : ReviewDecision.valueOf(rs.getString("verdict")),
            JdbcTimes.fromDb(rs.getObject("resolved_at", LocalDateTime.class)),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private static final RowMapper<OutlierReview> REVIEW_MAPPER = (rs, rowNum) -> new OutlierReview(
            rs.getLong("id"),
            rs.getLong("outlier_id"),
            rs.getString("reviewer"),
            ReviewDecision.valueOf(rs.getString("decision")),
            rs.getString("reason"),
            rs.getInt("batch_revision"),
            rs.getLong("certificate_id"),
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
                            + "(batch_id, reading_id, batch_revision, certificate_id, target_value, "
                            + "allowed_deviation, reading_value, deviation, submitted_by, status, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, outlier.batchId());
            ps.setLong(2, outlier.readingId());
            ps.setInt(3, outlier.batchRevision());
            ps.setLong(4, outlier.certificateId());
            ps.setBigDecimal(5, outlier.targetValue());
            ps.setBigDecimal(6, outlier.allowedDeviation());
            ps.setBigDecimal(7, outlier.readingValue());
            ps.setBigDecimal(8, outlier.deviation());
            ps.setString(9, outlier.submittedBy());
            ps.setString(10, outlier.status().name());
            ps.setObject(11, JdbcTimes.toDb(outlier.createdAt()));
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
     * 查询批次全部离群记录（按 ID 升序，即检测顺序）。
     */
    public List<OutlierRecord> findOutliersByBatch(long batchId) {
        return jdbc.query("SELECT * FROM outlier_record WHERE batch_id = ? ORDER BY id",
                OUTLIER_MAPPER, batchId);
    }

    /**
     * 统计批次待裁决离群记录数。
     */
    public int countPendingByBatch(long batchId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outlier_record WHERE batch_id = ? AND status = 'PENDING'",
                Integer.class, batchId);
        return count == null ? 0 : count;
    }

    /**
     * 裁决离群记录：一次性流转状态、裁决结论与裁决时间（须在持有批次行锁的事务内调用）。
     */
    public void markResolved(long outlierId, OutlierStatus status, ReviewDecision verdict, Instant resolvedAt) {
        jdbc.update("UPDATE outlier_record SET status = ?, verdict = ?, resolved_at = ? WHERE id = ?",
                status.name(), verdict.name(), JdbcTimes.toDb(resolvedAt), outlierId);
    }

    /**
     * 追加一条复核记录并返回生成的 ID；记录不可变，只增不改。
     */
    public long insertReview(OutlierReview review) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO outlier_review "
                            + "(outlier_id, reviewer, decision, reason, batch_revision, certificate_id, "
                            + "status, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, review.outlierId());
            ps.setString(2, review.reviewer());
            ps.setString(3, review.decision().name());
            ps.setString(4, review.reason());
            ps.setInt(5, review.batchRevision());
            ps.setLong(6, review.certificateId());
            ps.setString(7, review.status().name());
            ps.setObject(8, JdbcTimes.toDb(review.createdAt()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 查询离群记录的全部复核（按 ID 升序，即复核顺序）。
     */
    public List<OutlierReview> findReviewsByOutlier(long outlierId) {
        return jdbc.query("SELECT * FROM outlier_review WHERE outlier_id = ? ORDER BY id",
                REVIEW_MAPPER, outlierId);
    }

    /**
     * 统计某复核人在该离群记录上的有效（EFFECTIVE）复核数，用于同一复核人仅计一票。
     */
    public int countEffectiveReviewsBy(long outlierId, String reviewer) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outlier_review WHERE outlier_id = ? AND reviewer = ? AND status = 'EFFECTIVE'",
                Integer.class, outlierId, reviewer);
        return count == null ? 0 : count;
    }

    /**
     * 统计离群记录上某结论的有效复核数（法定人数判定）。
     */
    public int countEffectiveReviewsByDecision(long outlierId, ReviewDecision decision) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outlier_review "
                        + "WHERE outlier_id = ? AND decision = ? AND status = 'EFFECTIVE'",
                Integer.class, outlierId, decision.name());
        return count == null ? 0 : count;
    }
}
