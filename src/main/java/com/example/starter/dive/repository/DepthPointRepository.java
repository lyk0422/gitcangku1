package com.example.starter.dive.repository;

import com.example.starter.dive.domain.DepthPoint;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 深度点位仓储：只插不改，保证审核后点位冻结。
 */
@Repository
public class DepthPointRepository {

    private final JdbcTemplate jdbc;

    public DepthPointRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 按序号升序读取作业全部点位。 */
    public List<DepthPoint> findBySessionId(long sessionId) {
        return jdbc.query(
                "SELECT id, session_id, seq, recorded_at_utc, depth_m, request_id, created_at_utc "
                        + "FROM depth_point WHERE session_id = ? ORDER BY seq",
                new PointMapper(), sessionId);
    }

    /** 当前最大点位序号，无点位时返回0。 */
    public int maxSeq(long sessionId) {
        Integer max = jdbc.queryForObject(
                "SELECT MAX(seq) FROM depth_point WHERE session_id = ?", Integer.class, sessionId);
        return max == null ? 0 : max;
    }

    /** 追加一个点位。 */
    public void insert(long sessionId, int seq, Instant recordedAtUtc, BigDecimal depthM,
                       String requestId, Instant createdAtUtc) {
        jdbc.update(
                "INSERT INTO depth_point (session_id, seq, recorded_at_utc, depth_m, request_id, created_at_utc) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                sessionId, seq,
                OffsetDateTime.ofInstant(recordedAtUtc, ZoneOffset.UTC),
                depthM, requestId,
                OffsetDateTime.ofInstant(createdAtUtc, ZoneOffset.UTC));
    }

    /** 作业点位总数。 */
    public long countBySessionId(long sessionId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM depth_point WHERE session_id = ?", Long.class, sessionId);
        return count == null ? 0 : count;
    }

    private static final class PointMapper implements RowMapper<DepthPoint> {
        @Override
        public DepthPoint mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new DepthPoint(
                    rs.getLong("id"),
                    rs.getLong("session_id"),
                    rs.getInt("seq"),
                    rs.getObject("recorded_at_utc", OffsetDateTime.class).toInstant(),
                    rs.getBigDecimal("depth_m"),
                    rs.getString("request_id"),
                    rs.getObject("created_at_utc", OffsetDateTime.class).toInstant());
        }
    }
}