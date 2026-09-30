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
 * 巡检复核结论表访问。每条巡检至多一条复核结论（inspection_id 唯一），记录只追加。
 */
@Repository
public class InspectionReviewRepository {

    private static final ReviewRowMapper ROW_MAPPER = new ReviewRowMapper();

    private final JdbcTemplate jdbc;

    public InspectionReviewRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加复核结论；同一巡检重复复核由唯一约束拒绝。
     */
    public void insert(long inspectionId, String reviewerId, InspectionConclusion conclusion,
                       String note, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO inspection_review
                            (inspection_id, reviewer_id, conclusion, note, created_at)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                inspectionId, reviewerId, conclusion.name(), note, now);
    }

    /**
     * 按巡检单查询复核结论。
     */
    public Optional<InspectionReview> findByInspectionId(long inspectionId) {
        List<InspectionReview> rows = jdbc.query(
                "SELECT * FROM inspection_review WHERE inspection_id = ?", ROW_MAPPER, inspectionId);
        return rows.stream().findFirst();
    }

    private static final class ReviewRowMapper implements RowMapper<InspectionReview> {
        @Override
        public InspectionReview mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new InspectionReview(
                    rs.getLong("id"),
                    rs.getLong("inspection_id"),
                    rs.getString("reviewer_id"),
                    InspectionConclusion.valueOf(rs.getString("conclusion")),
                    rs.getString("note"),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
