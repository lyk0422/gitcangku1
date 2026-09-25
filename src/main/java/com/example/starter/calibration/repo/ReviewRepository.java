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

import com.example.starter.calibration.model.MeasurementReview;
import com.example.starter.calibration.model.ReviewConclusion;
import com.example.starter.calibration.model.ReviewStatus;

/**
 * 同行复核记录持久化。记录不可变：只插入，不更新、不删除；
 * 是否“有效”由记录状态与测量当前修订版本共同判定。
 */
@Repository
public class ReviewRepository {

    private static final RowMapper<MeasurementReview> MAPPER = (rs, rowNum) -> new MeasurementReview(
            rs.getLong("id"),
            rs.getString("review_key"),
            rs.getString("request_id"),
            rs.getLong("measurement_id"),
            rs.getInt("measurement_revision"),
            rs.getLong("certificate_id"),
            rs.getString("reviewer"),
            ReviewConclusion.valueOf(rs.getString("conclusion")),
            rs.getString("comment"),
            ReviewStatus.valueOf(rs.getString("status")),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public ReviewRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入复核记录并返回生成的 ID；review_key 冲突时抛出 DuplicateKeyException。
     */
    public long insert(MeasurementReview review) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO measurement_review "
                            + "(review_key, request_id, measurement_id, measurement_revision, certificate_id, "
                            + "reviewer, conclusion, comment, status, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, review.reviewKey());
            ps.setString(2, review.requestId());
            ps.setLong(3, review.measurementId());
            ps.setInt(4, review.measurementRevision());
            ps.setLong(5, review.certificateId());
            ps.setString(6, review.reviewer());
            ps.setString(7, review.conclusion().name());
            ps.setString(8, review.comment());
            ps.setString(9, review.status().name());
            ps.setObject(10, JdbcTimes.toDb(review.createdAt()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 按复核键查询（不加锁）。
     */
    public Optional<MeasurementReview> findByKey(String reviewKey) {
        return jdbc.query("SELECT * FROM measurement_review WHERE review_key = ?", MAPPER, reviewKey)
                .stream().findFirst();
    }

    /**
     * 查询某测量记录的全部复核历史（含旧版本与 STALE，按 ID 升序）。
     */
    public List<MeasurementReview> findByMeasurementId(long measurementId) {
        return jdbc.query("SELECT * FROM measurement_review WHERE measurement_id = ? ORDER BY id",
                MAPPER, measurementId);
    }

    /**
     * 查询某测量指定修订版本、指定结论的有效复核（最多一条；须在持有测量行锁的事务内判定唯一性）。
     */
    public Optional<MeasurementReview> findEffective(long measurementId, int revision,
                                                     ReviewConclusion conclusion) {
        return jdbc.query("SELECT * FROM measurement_review "
                        + "WHERE measurement_id = ? AND measurement_revision = ? "
                        + "AND conclusion = ? AND status = 'VALID' ORDER BY id",
                MAPPER, measurementId, revision, conclusion.name())
                .stream().findFirst();
    }
}
