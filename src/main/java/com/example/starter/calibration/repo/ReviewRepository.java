package com.example.starter.calibration.repo;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.OutlierReview;
import com.example.starter.calibration.model.ReviewDecision;

/**
 * 离群复核持久化。复核记录只增不改；stale 记录仅留痕，不参与裁决计数。
 */
@Repository
public class ReviewRepository {

    private static final RowMapper<OutlierReview> MAPPER = (rs, rowNum) -> new OutlierReview(
            rs.getLong("id"),
            rs.getLong("outlier_id"),
            rs.getString("request_id"),
            rs.getString("reviewer"),
            ReviewDecision.valueOf(rs.getString("decision")),
            rs.getString("reason"),
            rs.getInt("batch_revision"),
            rs.getLong("certificate_id"),
            rs.getBoolean("stale"),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public ReviewRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入复核记录并返回生成的 ID。
     */
    public long insert(OutlierReview review) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO outlier_review "
                            + "(outlier_id, request_id, reviewer, decision, reason, batch_revision, "
                            + "certificate_id, stale, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, review.outlierId());
            ps.setString(2, review.requestId());
            ps.setString(3, review.reviewer());
            ps.setString(4, review.decision().name());
            ps.setString(5, review.reason());
            ps.setInt(6, review.batchRevision());
            ps.setLong(7, review.certificateId());
            ps.setBoolean(8, review.stale());
            ps.setObject(9, JdbcTimes.toDb(review.createdAt()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 查询离群记录的全部复核（按 ID 升序，即提交顺序），含留痕的失效复核。
     */
    public List<OutlierReview> findByOutlierId(long outlierId) {
        return jdbc.query("SELECT * FROM outlier_review WHERE outlier_id = ? ORDER BY id", MAPPER, outlierId);
    }

    /**
     * 查询离群记录的有效复核（stale=FALSE，按提交顺序），用于裁决计数。
     */
    public List<OutlierReview> findValidByOutlierId(long outlierId) {
        return jdbc.query("SELECT * FROM outlier_review WHERE outlier_id = ? AND stale = FALSE ORDER BY id",
                MAPPER, outlierId);
    }
}
