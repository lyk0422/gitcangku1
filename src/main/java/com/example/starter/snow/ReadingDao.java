package com.example.starter.snow;

import com.example.starter.snow.model.ErrataRec;
import com.example.starter.snow.model.ReadingRec;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 雪况读数与勘误的数据访问；读数写入后不可改，只允许追加勘误。
 */
@Repository
public class ReadingDao {

    private final JdbcTemplate jdbc;

    public ReadingDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insertReading(String regionId, Instant hourUtc, int depthCm, int newSnowCm,
                              String requestId, Instant now) {
        jdbc.update("INSERT INTO snow_reading (region_id, hour_utc, depth_cm, new_snow_cm, request_id, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                regionId, Timestamp.from(hourUtc), depthCm, newSnowCm, requestId, Timestamp.from(now));
        Long id = jdbc.queryForObject(
                "SELECT id FROM snow_reading WHERE region_id = ? AND request_id = ?", Long.class, regionId, requestId);
        return id == null ? -1L : id;
    }

    public Optional<ReadingRec> findByRequestId(String regionId, String requestId) {
        List<ReadingRec> rows = jdbc.query(
                "SELECT * FROM snow_reading WHERE region_id = ? AND request_id = ?",
                this::mapReading, regionId, requestId);
        return rows.stream().findFirst();
    }

    public Optional<ReadingRec> findByHour(String regionId, Instant hourUtc) {
        List<ReadingRec> rows = jdbc.query(
                "SELECT * FROM snow_reading WHERE region_id = ? AND hour_utc = ?",
                this::mapReading, regionId, Timestamp.from(hourUtc));
        return rows.stream().findFirst();
    }

    public Optional<ReadingRec> findById(long id) {
        List<ReadingRec> rows = jdbc.query("SELECT * FROM snow_reading WHERE id = ?", this::mapReading, id);
        return rows.stream().findFirst();
    }

    public List<ReadingRec> findRecent(String regionId, int limit) {
        return jdbc.query("SELECT * FROM snow_reading WHERE region_id = ? ORDER BY hour_utc DESC LIMIT ?",
                this::mapReading, regionId, limit);
    }

    public long insertErrata(long readingId, String reviewer, String note, String requestId, Instant now) {
        jdbc.update("INSERT INTO reading_errata (reading_id, reviewer, note, request_id, created_at)"
                        + " VALUES (?, ?, ?, ?, ?)",
                readingId, reviewer, note, requestId, Timestamp.from(now));
        Long id = jdbc.queryForObject("SELECT id FROM reading_errata WHERE request_id = ?",
                Long.class, requestId);
        return id == null ? -1L : id;
    }

    public Optional<ErrataRec> findErrataByRequestId(String requestId) {
        List<ErrataRec> rows = jdbc.query("SELECT * FROM reading_errata WHERE request_id = ?",
                this::mapErrata, requestId);
        return rows.stream().findFirst();
    }

    public List<ErrataRec> findErrataByReadingId(long readingId) {
        return jdbc.query("SELECT * FROM reading_errata WHERE reading_id = ? ORDER BY id",
                this::mapErrata, readingId);
    }

    private ReadingRec mapReading(ResultSet rs, int rowNum) throws SQLException {
        return new ReadingRec(rs.getLong("id"), rs.getString("region_id"),
                rs.getTimestamp("hour_utc").toInstant(), rs.getInt("depth_cm"), rs.getInt("new_snow_cm"),
                rs.getString("request_id"), rs.getTimestamp("created_at").toInstant());
    }

    private ErrataRec mapErrata(ResultSet rs, int rowNum) throws SQLException {
        return new ErrataRec(rs.getLong("id"), rs.getLong("reading_id"), rs.getString("reviewer"),
                rs.getString("note"), rs.getString("request_id"), rs.getTimestamp("created_at").toInstant());
    }
}