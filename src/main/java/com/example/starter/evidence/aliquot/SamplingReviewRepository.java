package com.example.starter.evidence.aliquot;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 联合取样审核历史表访问，只追加；按 id（发生顺序）返回。
 */
@Repository
public class SamplingReviewRepository {

    private static final SamplingReviewRowMapper ROW_MAPPER = new SamplingReviewRowMapper();

    private final JdbcTemplate jdbc;

    public SamplingReviewRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条审核历史（确认/拒绝/取消）。
     */
    public void insert(String requestId, int seq, ReviewAction action, String reviewerId,
                       String note, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO sampling_review
                            (request_id, seq, action, reviewer_id, note, created_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                requestId, seq, action.name(), reviewerId, note, now);
    }

    /**
     * 按发生顺序查询申请单全部审核历史。
     */
    public List<SamplingReview> findByRequestId(String requestId) {
        return jdbc.query(
                "SELECT * FROM sampling_review WHERE request_id = ? ORDER BY id",
                ROW_MAPPER, requestId);
    }

    private static final class SamplingReviewRowMapper implements RowMapper<SamplingReview> {
        @Override
        public SamplingReview mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new SamplingReview(
                    rs.getLong("id"),
                    rs.getString("request_id"),
                    rs.getInt("seq"),
                    ReviewAction.valueOf(rs.getString("action")),
                    rs.getString("reviewer_id"),
                    rs.getString("note"),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
