package com.example.starter.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 重量差异复核记录表访问。记录只追加、不可变，不提供任何更新语句。
 */
@Repository
public class WeightReviewRepository {

    private static final ReviewRowMapper ROW_MAPPER = new ReviewRowMapper();

    private final JdbcTemplate jdbc;

    public WeightReviewRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条复核记录（每件证物至多一条，由唯一约束保证）。
     */
    public void insert(String evidenceKey, String reviewerId, String note, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO weight_review
                            (evidence_key, reviewer_id, note, created_at)
                        VALUES (?, ?, ?, ?)
                        """,
                evidenceKey, reviewerId, note, now);
    }

    /**
     * 按证物键查询复核记录；未复核时为空。
     */
    public Optional<WeightReview> findByEvidenceKey(String evidenceKey) {
        List<WeightReview> rows = jdbc.query(
                "SELECT * FROM weight_review WHERE evidence_key = ?", ROW_MAPPER, evidenceKey);
        return rows.stream().findFirst();
    }

    private static final class ReviewRowMapper implements RowMapper<WeightReview> {
        @Override
        public WeightReview mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new WeightReview(
                    rs.getLong("id"),
                    rs.getString("evidence_key"),
                    rs.getString("reviewer_id"),
                    rs.getString("note"),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
