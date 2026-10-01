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

import com.example.starter.calibration.model.ReviewDecision;
import com.example.starter.calibration.model.ReviewRecord;
import com.example.starter.calibration.model.ReviewStatus;

/**
 * 离群复核持久化。复核记录不可变追加，仅 VALID 计入裁决法定人数。
 */
@Repository
public class ReviewRepository {

    private static final RowMapper<ReviewRecord> MAPPER = (rs, rowNum) -> new ReviewRecord(
            rs.getLong("id"),
            rs.getLong("outlier_id"),
            rs.getString("reviewer"),
            ReviewDecision.valueOf(rs.getString("decision")),
            rs.getString("reason"),
            rs.getInt("measurement_revision"),
            rs.getLong("certificate_id"),
            ReviewStatus.valueOf(rs.getString("status")),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public ReviewRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条复核记录并返回生成的 ID。
     */
    public long insert(ReviewRecord review) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO review_record "
                            + "(outlier_id, reviewer, decision, reason, measurement_revision, certificate_id, "
                            + "status, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, review.outlierId());
            ps.setString(2, review.reviewer());
            ps.setString(3, review.decision().name());
            ps.setString(4, review.reason());
            ps.setInt(5, review.measurementRevision());
            ps.setLong(6, review.certificateId());
            ps.setString(7, review.status().name());
            ps.setObject(8, JdbcTimes.toDb(review.createdAt()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 查询离群记录的全部复核（按提交顺序，含 STALE）。
     */
    public List<ReviewRecord> findByOutlierId(long outlierId) {
        return jdbc.query("SELECT * FROM review_record WHERE outlier_id = ? ORDER BY id", MAPPER, outlierId);
    }
}