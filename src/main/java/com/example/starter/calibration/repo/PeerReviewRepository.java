package com.example.starter.calibration.repo;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.PeerReview;
import com.example.starter.calibration.model.ReviewConclusion;
import com.example.starter.calibration.model.ReviewState;

/**
 * 同行复核记录持久化。记录写入后不可变；valid_slot 唯一约束保证同一版本
 * 最多一条有效 PASS 与一条有效 RETURN，STALE 记录不占名额。
 */
@Repository
public class PeerReviewRepository {

    private static final RowMapper<PeerReview> MAPPER = (rs, rowNum) -> new PeerReview(
            rs.getLong("id"),
            rs.getString("review_key"),
            rs.getString("measurement_key"),
            rs.getInt("version"),
            rs.getLong("measurement_id"),
            rs.getLong("certificate_id"),
            ReviewConclusion.valueOf(rs.getString("conclusion")),
            ReviewState.valueOf(rs.getString("state")),
            rs.getString("reviewer"),
            rs.getString("comment"),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public PeerReviewRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条复核记录并返回生成 ID。validSlot 为 null（STALE）时不占用有效名额唯一约束。
     * 有效名额冲突或 reviewKey 冲突时抛出 DuplicateKeyException。
     */
    public long insert(PeerReview review, String validSlot) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO peer_review "
                            + "(review_key, measurement_key, version, measurement_id, certificate_id, "
                            + "conclusion, state, reviewer, comment, valid_slot, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, review.reviewKey());
            ps.setString(2, review.measurementKey());
            ps.setInt(3, review.version());
            ps.setLong(4, review.measurementId());
            ps.setLong(5, review.certificateId());
            ps.setString(6, review.conclusion().name());
            ps.setString(7, review.state().name());
            ps.setString(8, review.reviewer());
            ps.setString(9, review.comment());
            ps.setString(10, validSlot);
            ps.setObject(11, JdbcTimes.toDb(review.createdAt()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 有效名额键：measurement_key#version#conclusion。
     */
    public static String validSlot(String measurementKey, int version, ReviewConclusion conclusion) {
        return measurementKey + "#" + version + "#" + conclusion.name();
    }

    /**
     * 按复核业务键查询（幂等重放用）。
     */
    public Optional<PeerReview> findByReviewKey(String reviewKey) {
        return jdbc.query("SELECT * FROM peer_review WHERE review_key = ?", MAPPER, reviewKey)
                .stream().findFirst();
    }

    /**
     * 按复核记录 ID 查询（幂等重放用）。
     */
    public Optional<PeerReview> findById(long id) {
        return jdbc.query("SELECT * FROM peer_review WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 查询某测量某版本的全部复核记录（按 ID 升序）。
     */
    public List<PeerReview> findByMeasurementVersion(String measurementKey, int version) {
        return jdbc.query("SELECT * FROM peer_review WHERE measurement_key = ? AND version = ? ORDER BY id",
                MAPPER, measurementKey, version);
    }

    /**
     * 查询某测量全部版本的复核记录（按版本与 ID 升序）。
     */
    public List<PeerReview> findByMeasurementKey(String measurementKey) {
        return jdbc.query("SELECT * FROM peer_review WHERE measurement_key = ? ORDER BY version, id",
                MAPPER, measurementKey);
    }

    /**
     * 查询当前版本指定结论的有效复核。
     */
    public Optional<PeerReview> findValid(String measurementKey, int version, ReviewConclusion conclusion) {
        return jdbc.query(
                "SELECT * FROM peer_review WHERE measurement_key = ? AND version = ? "
                        + "AND conclusion = ? AND state = 'VALID'",
                MAPPER, measurementKey, version, conclusion.name())
                .stream().findFirst();
    }

    /**
     * 是否为有效名额唯一约束冲突（区别于 reviewKey 冲突）。
     */
    public boolean isValidSlotConflict(DuplicateKeyException ex) {
        String msg = String.valueOf(ex.getMostSpecificCause().getMessage());
        return msg.contains("uk_review_valid_slot") || msg.contains("VALID_SLOT");
    }
}
