package com.example.starter.film.repo;

import com.example.starter.film.domain.DeliveryReceipt;
import com.example.starter.film.domain.Film;
import com.example.starter.film.domain.Master;
import com.example.starter.film.domain.MasterSegment;
import com.example.starter.film.domain.MasterStatus;
import com.example.starter.film.domain.SegmentVersion;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 基于 {@link JdbcTemplate} 的持久化实现；SQL 使用参数绑定，兼容 H2(MODE=MySQL) 与 MySQL。
 */
@Repository
public class JdbcFilmRepository implements FilmRepository {

    private final JdbcTemplate jdbc;

    public JdbcFilmRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Film> FILM_MAPPER = (rs, n) -> new Film(
            rs.getLong("id"), rs.getString("code"), rs.getInt("planned_segments"),
            rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<SegmentVersion> SEGMENT_MAPPER = (rs, n) -> {
        // 必须紧跟在 getInt 之后调用 wasNull()，读取后续列会重置判定状态。
        int basedOn = rs.getInt("based_on_version");
        Integer basedOnVersion = rs.wasNull() ? null : basedOn;
        return new SegmentVersion(
                rs.getLong("id"), rs.getLong("film_id"), rs.getInt("segment_no"),
                rs.getInt("version_no"), rs.getString("content_digest"),
                basedOnVersion,
                rs.getString("request_id"), rs.getTimestamp("created_at").toInstant());
    };

    private static final RowMapper<Master> MASTER_MAPPER = (rs, n) -> {
        Timestamp revokedAt = rs.getTimestamp("revoked_at");
        return new Master(
                rs.getLong("id"), rs.getLong("film_id"), rs.getString("master_no"),
                MasterStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("created_at").toInstant(),
                revokedAt == null ? null : revokedAt.toInstant());
    };

    private static final RowMapper<MasterSegment> MASTER_SEGMENT_MAPPER = (rs, n) -> new MasterSegment(
            rs.getLong("master_id"), rs.getInt("segment_no"),
            rs.getInt("version_no"), rs.getString("content_digest"));

    private static final RowMapper<DeliveryReceipt> RECEIPT_MAPPER = (rs, n) -> new DeliveryReceipt(
            rs.getLong("id"), rs.getLong("film_id"), rs.getString("receipt_no"),
            rs.getString("master_no"), rs.getString("request_id"),
            rs.getString("snapshot_json"), rs.getTimestamp("created_at").toInstant());

    @Override
    public long createFilm(String code, int plannedSegments, Instant now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO film (code, planned_segments, created_at) VALUES (?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, code);
            ps.setInt(2, plannedSegments);
            ps.setTimestamp(3, Timestamp.from(now));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    @Override
    public Optional<Film> findFilmByCode(String code) {
        return Optional.ofNullable(DataAccessUtils.singleResult(
                jdbc.query("SELECT * FROM film WHERE code = ?", FILM_MAPPER, code)));
    }

    @Override
    public Optional<Film> findFilmById(long filmId) {
        return Optional.ofNullable(DataAccessUtils.singleResult(
                jdbc.query("SELECT * FROM film WHERE id = ?", FILM_MAPPER, filmId)));
    }

    @Override
    public void lockFilm(long filmId) {
        jdbc.queryForRowSet("SELECT id FROM film WHERE id = ? FOR UPDATE", filmId);
    }

    @Override
    public Optional<SegmentVersion> findSegmentRequest(long filmId, String requestId) {
        return Optional.ofNullable(DataAccessUtils.singleResult(jdbc.query(
                "SELECT * FROM segment_version WHERE film_id = ? AND request_id = ?",
                SEGMENT_MAPPER, filmId, requestId)));
    }

    @Override
    public Optional<SegmentVersion> findLatestSegment(long filmId, int segmentNo) {
        return Optional.ofNullable(DataAccessUtils.singleResult(jdbc.query(
                "SELECT * FROM segment_version WHERE film_id = ? AND segment_no = ? "
                        + "ORDER BY version_no DESC LIMIT 1",
                SEGMENT_MAPPER, filmId, segmentNo)));
    }

    @Override
    public long insertSegmentVersion(long filmId, int segmentNo, int versionNo, String contentDigest,
                                     Integer basedOnVersion, String requestId, Instant now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO segment_version (film_id, segment_no, version_no, content_digest, "
                            + "based_on_version, request_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, filmId);
            ps.setInt(2, segmentNo);
            ps.setInt(3, versionNo);
            ps.setString(4, contentDigest);
            if (basedOnVersion == null) {
                ps.setNull(5, java.sql.Types.INTEGER);
            } else {
                ps.setInt(5, basedOnVersion);
            }
            ps.setString(6, requestId);
            ps.setTimestamp(7, Timestamp.from(now));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    @Override
    public List<SegmentVersion> findSegmentChain(long filmId) {
        return jdbc.query(
                "SELECT * FROM segment_version WHERE film_id = ? ORDER BY segment_no, version_no",
                SEGMENT_MAPPER, filmId);
    }

    @Override
    public List<SegmentVersion> findLatestVersions(long filmId) {
        return jdbc.query(
                "SELECT v.* FROM segment_version v "
                        + "JOIN (SELECT segment_no, MAX(version_no) AS max_version "
                        + "      FROM segment_version WHERE film_id = ? GROUP BY segment_no) m "
                        + "ON v.segment_no = m.segment_no AND v.version_no = m.max_version "
                        + "WHERE v.film_id = ? ORDER BY v.segment_no",
                SEGMENT_MAPPER, filmId, filmId);
    }

    @Override
    public Optional<Master> findMasterByReviewRequest(long filmId, String requestId) {
        return Optional.ofNullable(DataAccessUtils.singleResult(jdbc.query(
                "SELECT * FROM master WHERE film_id = ? AND review_request_id = ?",
                MASTER_MAPPER, filmId, requestId)));
    }

    @Override
    public long insertMaster(long filmId, String masterNo, String reviewRequestId, Instant now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO master (film_id, master_no, review_request_id, status, created_at) "
                            + "VALUES (?, ?, ?, 'APPROVED', ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, filmId);
            ps.setString(2, masterNo);
            ps.setString(3, reviewRequestId);
            ps.setTimestamp(4, Timestamp.from(now));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    @Override
    public void insertMasterSegment(long masterId, int segmentNo, int versionNo, String contentDigest) {
        jdbc.update("INSERT INTO master_segment (master_id, segment_no, version_no, content_digest) "
                + "VALUES (?, ?, ?, ?)", masterId, segmentNo, versionNo, contentDigest);
    }

    @Override
    public Optional<Master> findMasterByNo(String masterNo) {
        return Optional.ofNullable(DataAccessUtils.singleResult(
                jdbc.query("SELECT * FROM master WHERE master_no = ?", MASTER_MAPPER, masterNo)));
    }

    @Override
    public List<MasterSegment> findMasterSegments(long masterId) {
        return jdbc.query(
                "SELECT * FROM master_segment WHERE master_id = ? ORDER BY segment_no",
                MASTER_SEGMENT_MAPPER, masterId);
    }

    @Override
    public List<Master> findMasters(long filmId) {
        return jdbc.query("SELECT * FROM master WHERE film_id = ? ORDER BY id",
                MASTER_MAPPER, filmId);
    }

    @Override
    public void revokeMaster(long masterId, Instant now) {
        jdbc.update("UPDATE master SET status = 'REVOKED', revoked_at = ? WHERE id = ? AND status = 'APPROVED'",
                Timestamp.from(now), masterId);
    }

    @Override
    public Optional<DeliveryReceipt> findDeliveryRequest(long filmId, String requestId) {
        return Optional.ofNullable(DataAccessUtils.singleResult(jdbc.query(
                "SELECT * FROM delivery_receipt WHERE film_id = ? AND request_id = ?",
                RECEIPT_MAPPER, filmId, requestId)));
    }

    @Override
    public long insertReceipt(long filmId, String receiptNo, String masterNo, String requestId,
                              String snapshotJson, Instant now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO delivery_receipt (film_id, receipt_no, master_no, request_id, "
                            + "snapshot_json, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, filmId);
            ps.setString(2, receiptNo);
            ps.setString(3, masterNo);
            ps.setString(4, requestId);
            ps.setString(5, snapshotJson);
            ps.setTimestamp(6, Timestamp.from(now));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    @Override
    public List<DeliveryReceipt> findReceipts(long filmId) {
        return jdbc.query("SELECT * FROM delivery_receipt WHERE film_id = ? ORDER BY id",
                RECEIPT_MAPPER, filmId);
    }
}
