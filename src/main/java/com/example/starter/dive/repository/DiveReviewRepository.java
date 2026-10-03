package com.example.starter.dive.repository;

import com.example.starter.dive.domain.Anomaly;
import com.example.starter.dive.domain.DiveReview;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * 审核/复核记录仓储：异常明细以JSON落库，通过决定经唯一键保证同一版本最多一条。
 */
@Repository
public class DiveReviewRepository {

    private static final String COLUMNS =
            "id, session_id, point_version, kind, decision, status, request_id, anomalies_json, "
                    + "supersedes_id, created_at_utc";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public DiveReviewRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** 当前有效的审核记录。 */
    public Optional<DiveReview> findActive(long sessionId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT " + COLUMNS + " FROM dive_review WHERE session_id = ? AND status = 'ACTIVE' "
                            + "ORDER BY id DESC LIMIT 1",
                    new ReviewMapper(objectMapper), sessionId));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    /** 按幂等键查询通过决定（失败不占键，故只可能命中通过记录）。 */
    public Optional<DiveReview> findByIdemKey(long sessionId, String idemKey) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT " + COLUMNS + " FROM dive_review WHERE session_id = ? AND idem_key = ?",
                    new ReviewMapper(objectMapper), sessionId, idemKey));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    /** 查询指定版本当前有效的通过决定。 */
    public Optional<DiveReview> findActivePassedByVersion(long sessionId, int pointVersion) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT " + COLUMNS + " FROM dive_review WHERE session_id = ? AND status = 'ACTIVE' "
                            + "AND decision = 'PASSED' AND point_version = ?",
                    new ReviewMapper(objectMapper), sessionId, pointVersion));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    /** 作业的全部审核/复核记录，按ID升序形成复核链。 */
    public List<DiveReview> findBySessionId(long sessionId) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM dive_review WHERE session_id = ? ORDER BY id",
                new ReviewMapper(objectMapper), sessionId);
    }

    /** 插入一条审核记录，返回生成的ID；通过决定占用幂等键与版本唯一键。 */
    public long insert(long sessionId, int pointVersion, DiveReview.ReviewKind kind,
                       DiveReview.ReviewDecision decision, String requestId,
                       Long supersedesId, List<Anomaly> anomalies, Instant createdAtUtc) {
        boolean passed = decision == DiveReview.ReviewDecision.PASSED;
        String idemKey = passed ? requestId : null;
        Integer passedKey = passed ? pointVersion : null;
        String anomaliesJson = toJson(anomalies);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO dive_review (session_id, point_version, kind, decision, status, request_id, "
                            + "idem_key, passed_key, anomalies_json, supersedes_id, created_at_utc) "
                            + "VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, sessionId);
            ps.setInt(2, pointVersion);
            ps.setString(3, kind.name());
            ps.setString(4, decision.name());
            ps.setString(5, requestId);
            ps.setString(6, idemKey);
            if (passedKey == null) {
                ps.setNull(7, java.sql.Types.INTEGER);
            } else {
                ps.setInt(7, passedKey);
            }
            ps.setString(8, anomaliesJson);
            if (supersedesId == null) {
                ps.setNull(9, java.sql.Types.BIGINT);
            } else {
                ps.setLong(9, supersedesId);
            }
            ps.setObject(10, OffsetDateTime.ofInstant(createdAtUtc, ZoneOffset.UTC));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("审核记录未返回生成主键");
        }
        return key.longValue();
    }

    /** 将记录标记为过期。 */
    public void markExpired(long reviewId) {
        jdbc.update("UPDATE dive_review SET status = 'EXPIRED' WHERE id = ?", reviewId);
    }

    /** 作业内通过决定总数，用于并发断言。 */
    public long countPassed(long sessionId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dive_review WHERE session_id = ? AND decision = 'PASSED'",
                Long.class, sessionId);
        return count == null ? 0 : count;
    }

    private String toJson(List<Anomaly> anomalies) {
        try {
            return objectMapper.writeValueAsString(anomalies);
        } catch (Exception ex) {
            throw new IllegalStateException("异常明细序列化失败", ex);
        }
    }

    private static final class ReviewMapper implements RowMapper<DiveReview> {
        private final ObjectMapper objectMapper;

        private ReviewMapper(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        @Override
        public DiveReview mapRow(ResultSet rs, int rowNum) throws SQLException {
            List<Anomaly> anomalies;
            try {
                anomalies = objectMapper.readValue(rs.getString("anomalies_json"),
                        new TypeReference<List<Anomaly>>() {
                        });
            } catch (Exception ex) {
                throw new SQLException("异常明细反序列化失败", ex);
            }
            long id = rs.getLong("id");
            long sessionId = rs.getLong("session_id");
            int pointVersion = rs.getInt("point_version");
            DiveReview.ReviewKind kind = DiveReview.ReviewKind.valueOf(rs.getString("kind"));
            DiveReview.ReviewDecision decision =
                    DiveReview.ReviewDecision.valueOf(rs.getString("decision"));
            DiveReview.ReviewStatus status = DiveReview.ReviewStatus.valueOf(rs.getString("status"));
            String requestId = rs.getString("request_id");
            long supersedes = rs.getLong("supersedes_id");
            Long supersedesId = rs.wasNull() ? null : supersedes;
            Instant createdAtUtc = rs.getObject("created_at_utc", OffsetDateTime.class).toInstant();
            return new DiveReview(id, sessionId, pointVersion, kind, decision, status,
                    requestId, supersedesId, anomalies, createdAtUtc);
        }
    }
}