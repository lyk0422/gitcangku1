package com.example.starter.repository;

import com.example.starter.domain.BatchRelease;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.Optional;

/**
 * 批次放行事实表 batch_release 数据访问。一批次至多一行，放行后不可回滚。
 */
@Repository
public class ReleaseRepository {

    private final JdbcTemplate jdbcTemplate;

    public ReleaseRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final String COLUMNS =
            "id, batch_id, certificate_id, certificate_no, first_test_no, first_ratio, "
                    + "second_test_no, second_ratio, request_id, released_at, created_at";

    private static final RowMapper<BatchRelease> ROW_MAPPER = (rs, rowNum) -> new BatchRelease(
            rs.getLong("id"),
            rs.getLong("batch_id"),
            rs.getLong("certificate_id"),
            rs.getString("certificate_no"),
            rs.getString("first_test_no"),
            rs.getBigDecimal("first_ratio"),
            rs.getString("second_test_no"),
            rs.getBigDecimal("second_ratio"),
            rs.getString("request_id"),
            rs.getTimestamp("released_at").toLocalDateTime(),
            rs.getTimestamp("created_at").toLocalDateTime());

    /**
     * 写入放行事实及其证书快照，返回主键；批次唯一约束在并发下兜底重复放行。
     */
    public long insert(BatchRelease r) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO batch_release (batch_id, certificate_id, certificate_no, "
                            + "first_test_no, first_ratio, second_test_no, second_ratio, "
                            + "request_id, released_at, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, r.batchId());
            ps.setLong(2, r.certificateId());
            ps.setString(3, r.certificateNo());
            ps.setString(4, r.firstTestNo());
            ps.setBigDecimal(5, r.firstRatio());
            ps.setString(6, r.secondTestNo());
            ps.setBigDecimal(7, r.secondRatio());
            ps.setString(8, r.requestId());
            ps.setTimestamp(9, Timestamp.valueOf(r.releasedAt()));
            ps.setTimestamp(10, Timestamp.valueOf(r.createdAt()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 按批次查询放行事实；未放行返回空。
     */
    public Optional<BatchRelease> findByBatchId(long batchId) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM batch_release WHERE batch_id = ?",
                ROW_MAPPER, batchId).stream().findFirst();
    }
}
