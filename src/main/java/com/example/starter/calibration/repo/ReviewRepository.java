package com.example.starter.calibration.repo;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.AdjudicationRecord;
import com.example.starter.calibration.model.ReviewDecision;
import com.example.starter.calibration.model.ReviewRecord;
import com.example.starter.calibration.model.ReviewStatus;

/**
 * 复核与裁决持久化。两类记录均不可变，只增不改。
 */
@Repository
public class ReviewRepository {

    private static final RowMapper<ReviewRecord> REVIEW_MAPPER = (rs, rowNum) -> new ReviewRecord(
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

    private static final RowMapper<AdjudicationRecord> ADJUDICATION_MAPPER = (rs, rowNum) -> new AdjudicationRecord(
            rs.getLong("id"),
            rs.getLong("outlier_id"),
            ReviewDecision.valueOf(rs.getString("decision")),
            JdbcTimes.fromDb(rs.getObject("decided_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public ReviewRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条复核记录（VALID 或 STALE）并返回生成的 ID。
     */
    public long insertReview(ReviewRecord review) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO review_record "
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
     * 查询某离群记录的全部复核（按 ID 升序，即提交顺序）。
     */
    public List<ReviewRecord> findReviewsByOutlierId(long outlierId) {
        return jdbc.query("SELECT * FROM review_record WHERE outlier_id = ? ORDER BY id",
                REVIEW_MAPPER, outlierId);
    }

    /**
     * 查询某离群记录的全部有效复核（按 ID 升序）。
     */
    public List<ReviewRecord> findValidReviewsByOutlierId(long outlierId) {
        return jdbc.query("SELECT * FROM review_record WHERE outlier_id = ? AND status = ? ORDER BY id",
                REVIEW_MAPPER, outlierId, ReviewStatus.VALID.name());
    }

    /**
     * 追加一条裁决记录。
     */
    public void insertAdjudication(long outlierId, ReviewDecision decision, Instant decidedAt) {
        jdbc.update("INSERT INTO adjudication_record (outlier_id, decision, decided_at) VALUES (?, ?, ?)",
                outlierId, decision.name(), JdbcTimes.toDb(decidedAt));
    }

    /**
     * 查询某离群记录的全部裁决记录（按 ID 升序）。
     */
    public List<AdjudicationRecord> findAdjudicationsByOutlierId(long outlierId) {
        return jdbc.query("SELECT * FROM adjudication_record WHERE outlier_id = ? ORDER BY id",
                ADJUDICATION_MAPPER, outlierId);
    }
}