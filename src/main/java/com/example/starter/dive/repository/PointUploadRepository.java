package com.example.starter.dive.repository;

import com.example.starter.dive.domain.PointUpload;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * 点位上传幂等批次仓储。
 */
@Repository
public class PointUploadRepository {

    private final JdbcTemplate jdbc;

    public PointUploadRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 按作业与requestId查询已有批次。 */
    public Optional<PointUpload> findByRequestId(long sessionId, String requestId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT id, session_id, request_id, payload_hash, point_count, first_seq, last_seq, "
                            + "point_version, created_at_utc FROM point_upload "
                            + "WHERE session_id = ? AND request_id = ?",
                    new UploadMapper(), sessionId, requestId));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    /** 记录新批次。 */
    public void insert(long sessionId, String requestId, String payloadHash, int pointCount,
                       int firstSeq, int lastSeq, int pointVersion, Instant createdAtUtc) {
        jdbc.update(
                "INSERT INTO point_upload (session_id, request_id, payload_hash, point_count, first_seq, "
                        + "last_seq, point_version, created_at_utc) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                sessionId, requestId, payloadHash, pointCount, firstSeq, lastSeq, pointVersion,
                OffsetDateTime.ofInstant(createdAtUtc, ZoneOffset.UTC));
    }

    private static final class UploadMapper implements RowMapper<PointUpload> {
        @Override
        public PointUpload mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new PointUpload(
                    rs.getLong("id"),
                    rs.getLong("session_id"),
                    rs.getString("request_id"),
                    rs.getString("payload_hash"),
                    rs.getInt("point_count"),
                    rs.getInt("first_seq"),
                    rs.getInt("last_seq"),
                    rs.getInt("point_version"),
                    rs.getObject("created_at_utc", OffsetDateTime.class).toInstant());
        }
    }
}