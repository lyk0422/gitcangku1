package com.example.starter.aliquot;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 取样审核历史表访问。只追加、不可变，不提供更新/删除语句。
 */
@Repository
public class AliquotReviewRepository {

    private static final ReviewRowMapper ROW_MAPPER = new ReviewRowMapper();

    private final JdbcTemplate jdbc;

    public AliquotReviewRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条审核记录（seq=1 第一次确认 / seq=2 第二次确认）。
     */
    public void insert(long requestId, int seq, String reviewerId, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO aliquot_review (request_id, seq, reviewer_id, created_at)
                        VALUES (?, ?, ?, ?)
                        """,
                requestId, seq, reviewerId, now);
    }

    /**
     * 按取样单 id 查询全部审核记录（按顺序）。
     */
    public List<ReviewRecord> findByRequestId(long requestId) {
        return jdbc.query(
                "SELECT * FROM aliquot_review WHERE request_id = ? ORDER BY seq, id",
                ROW_MAPPER, requestId);
    }

    /**
     * 审核历史记录（只追加、不可变）。
     *
     * @param id         主键
     * @param requestId  所属联合取样单 id
     * @param seq        审核顺序：1 第一次 / 2 第二次
     * @param reviewerId 审核人
     * @param createdAt  审核提交时间
     */
    public record ReviewRecord(long id, long requestId, int seq, String reviewerId,
                               LocalDateTime createdAt) {
    }

    private static final class ReviewRowMapper implements RowMapper<ReviewRecord> {
        @Override
        public ReviewRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ReviewRecord(
                    rs.getLong("id"),
                    rs.getLong("request_id"),
                    rs.getInt("seq"),
                    rs.getString("reviewer_id"),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
