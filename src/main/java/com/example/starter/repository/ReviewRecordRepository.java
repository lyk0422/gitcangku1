package com.example.starter.repository;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.po.ReviewRecordPo;

/**
 * 复核幂等记录仓储；request_id 唯一约束保证每个申请至多一条首次复核结论。
 */
@Repository
public class ReviewRecordRepository {

    private static final RowMapper<ReviewRecordPo> MAPPER = (rs, n) -> new ReviewRecordPo(
            rs.getObject("id", Long.class),
            rs.getString("request_id"),
            rs.getString("decision"),
            rs.getString("request_hash"),
            rs.getTimestamp("reviewed_at").toLocalDateTime());

    private final JdbcTemplate jdbcTemplate;

    public ReviewRecordRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public ReviewRecordPo findByRequestId(String requestId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT id, request_id, decision, request_hash, reviewed_at "
                            + "FROM review_record WHERE request_id = ?", MAPPER, requestId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    public void insert(ReviewRecordPo po) {
        jdbcTemplate.update(
                "INSERT INTO review_record (request_id, decision, request_hash, reviewed_at) "
                        + "VALUES (?, ?, ?, ?)",
                po.requestId(), po.decision(), po.requestHash(), po.reviewedAt());
    }
}
