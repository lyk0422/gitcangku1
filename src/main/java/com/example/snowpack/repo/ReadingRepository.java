package com.example.snowpack.repo;

import com.example.snowpack.repo.Rows.ErrataRow;
import com.example.snowpack.repo.Rows.ReadingRow;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/** 雪况读数与勘误存取。 */
@Repository
public class ReadingRepository {

    private static final RowMapper<ReadingRow> READING_MAPPER = (rs, rowNum) -> new ReadingRow(
            rs.getLong("id"), rs.getString("zone_id"), rs.getTimestamp("hour_utc").toInstant(),
            rs.getInt("snow_depth_cm"), rs.getInt("new_snow_cm"),
            rs.getString("request_id"), rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<ErrataRow> ERRATA_MAPPER = (rs, rowNum) -> new ErrataRow(
            rs.getLong("id"), rs.getLong("reading_id"), rs.getString("request_id"),
            rs.getString("note"), rs.getString("review_result"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public ReadingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 插入读数，返回生成主键。 */
    public long insert(String zoneId, Instant hourUtc, int snowDepthCm, int newSnowCm,
                       String requestId, Instant now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement(
                    "INSERT INTO snow_reading (zone_id, hour_utc, snow_depth_cm, new_snow_cm, request_id, created_at)"
                            + " VALUES (?,?,?,?,?,?)",
                    new String[] {"id"});
            ps.setString(1, zoneId);
            ps.setTimestamp(2, Timestamp.from(hourUtc));
            ps.setInt(3, snowDepthCm);
            ps.setInt(4, newSnowCm);
            ps.setString(5, requestId);
            ps.setTimestamp(6, Timestamp.from(now));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<ReadingRow> findByRequestId(String zoneId, String requestId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT * FROM snow_reading WHERE zone_id = ? AND request_id = ?", READING_MAPPER,
                    zoneId, requestId));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    public Optional<ReadingRow> findByHour(String zoneId, Instant hourUtc) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT * FROM snow_reading WHERE zone_id = ? AND hour_utc = ?", READING_MAPPER,
                    zoneId, Timestamp.from(hourUtc)));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    public Optional<ReadingRow> findById(String zoneId, long id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT * FROM snow_reading WHERE zone_id = ? AND id = ?", READING_MAPPER, zoneId, id));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    /** 最新的若干条读数，按整点倒序。 */
    public List<ReadingRow> latest(String zoneId, int limit) {
        return jdbc.query("SELECT * FROM snow_reading WHERE zone_id = ? ORDER BY hour_utc DESC LIMIT ?",
                READING_MAPPER, zoneId, limit);
    }

    public long insertErrata(long readingId, String requestId, String note, String reviewResult, Instant now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement(
                    "INSERT INTO reading_errata (reading_id, request_id, note, review_result, created_at)"
                            + " VALUES (?,?,?,?,?)",
                    new String[] {"id"});
            ps.setLong(1, readingId);
            ps.setString(2, requestId);
            ps.setString(3, note);
            ps.setString(4, reviewResult);
            ps.setTimestamp(5, Timestamp.from(now));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<ErrataRow> findErrataByRequestId(long readingId, String requestId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT * FROM reading_errata WHERE reading_id = ? AND request_id = ?", ERRATA_MAPPER,
                    readingId, requestId));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    public List<ErrataRow> listErrata(long readingId) {
        return jdbc.query("SELECT * FROM reading_errata WHERE reading_id = ? ORDER BY id",
                ERRATA_MAPPER, readingId);
    }
}