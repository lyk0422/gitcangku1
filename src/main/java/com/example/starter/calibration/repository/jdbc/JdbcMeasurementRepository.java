package com.example.starter.calibration.repository.jdbc;

import com.example.starter.calibration.domain.Measurement;
import com.example.starter.calibration.domain.MeasurementStatus;
import com.example.starter.calibration.error.ApiException;
import com.example.starter.calibration.repository.MeasurementRepository;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 基于 JdbcTemplate 的测量仓库实现（MySQL）。
 */
@Repository
public class JdbcMeasurementRepository implements MeasurementRepository {

    private static final RowMapper<Measurement> ROW_MAPPER = (rs, rowNum) -> new Measurement(
            rs.getLong("id"),
            rs.getString("measurement_key"),
            rs.getString("instrument_id"),
            rs.getTimestamp("measured_at").toInstant(),
            rs.getBigDecimal("raw_reading"),
            rs.getBigDecimal("lower_limit"),
            rs.getBigDecimal("upper_limit"),
            rs.getString("submitted_by"),
            rs.getLong("certificate_id"),
            rs.getBigDecimal("computed_value"),
            rs.getBigDecimal("display_value"),
            rs.getBoolean("passed"),
            MeasurementStatus.valueOf(rs.getString("status")),
            rs.getString("released_by"),
            rs.getTimestamp("released_at") == null ? null : rs.getTimestamp("released_at").toInstant(),
            rs.getTimestamp("created_at").toInstant());

    private static final String SELECT_COLUMNS =
            "id, measurement_key, instrument_id, measured_at, raw_reading, lower_limit, upper_limit,"
                    + " submitted_by, certificate_id, computed_value, display_value, passed, status,"
                    + " released_by, released_at, created_at";

    private static final String SELECT_COLUMNS_M =
            "m.id, m.measurement_key, m.instrument_id, m.measured_at, m.raw_reading, m.lower_limit,"
                    + " m.upper_limit, m.submitted_by, m.certificate_id, m.computed_value, m.display_value,"
                    + " m.passed, m.status, m.released_by, m.released_at, m.created_at";

    private final JdbcTemplate jdbc;

    public JdbcMeasurementRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Measurement insert(Measurement measurement) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            jdbc.update(connection -> {
                PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO measurement"
                                + " (measurement_key, instrument_id, measured_at, raw_reading, lower_limit,"
                                + " upper_limit, submitted_by, certificate_id, computed_value, display_value,"
                                + " passed, status, released_by, released_at, created_at)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, ?)",
                        Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, measurement.measurementKey());
                ps.setString(2, measurement.instrumentId());
                ps.setTimestamp(3, Timestamp.from(measurement.measuredAt()));
                ps.setBigDecimal(4, measurement.rawReading());
                ps.setBigDecimal(5, measurement.lowerLimit());
                ps.setBigDecimal(6, measurement.upperLimit());
                ps.setString(7, measurement.submittedBy());
                ps.setLong(8, measurement.certificateId());
                ps.setBigDecimal(9, measurement.computedValue());
                ps.setBigDecimal(10, measurement.displayValue());
                ps.setBoolean(11, measurement.passed());
                ps.setString(12, measurement.status().name());
                ps.setTimestamp(13, Timestamp.from(measurement.createdAt()));
                return ps;
            }, keyHolder);
        } catch (DuplicateKeyException ex) {
            throw ApiException.conflict(
                    "MEASUREMENT_KEY_DUPLICATE",
                    "measurementKey 已存在: " + measurement.measurementKey());
        }
        long id = keyHolder.getKey().longValue();
        return new Measurement(
                id,
                measurement.measurementKey(),
                measurement.instrumentId(),
                measurement.measuredAt(),
                measurement.rawReading(),
                measurement.lowerLimit(),
                measurement.upperLimit(),
                measurement.submittedBy(),
                measurement.certificateId(),
                measurement.computedValue(),
                measurement.displayValue(),
                measurement.passed(),
                measurement.status(),
                null,
                null,
                measurement.createdAt());
    }

    @Override
    public Optional<Measurement> findById(long id) {
        return jdbc.query(
                        "SELECT " + SELECT_COLUMNS + " FROM measurement WHERE id = ?",
                        ROW_MAPPER,
                        id)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<Measurement> findByIdForUpdate(long id) {
        return jdbc.query(
                        "SELECT " + SELECT_COLUMNS + " FROM measurement WHERE id = ? FOR UPDATE",
                        ROW_MAPPER,
                        id)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<Measurement> findByMeasurementKey(String measurementKey) {
        return jdbc.query(
                        "SELECT " + SELECT_COLUMNS + " FROM measurement WHERE measurement_key = ?",
                        ROW_MAPPER,
                        measurementKey)
                .stream()
                .findFirst();
    }

    @Override
    public boolean markReleased(long id, String releasedBy, Instant releasedAt) {
        int updated = jdbc.update(
                "UPDATE measurement SET status = 'RELEASED', released_by = ?, released_at = ?"
                        + " WHERE id = ? AND status = 'PENDING_RELEASE'",
                releasedBy,
                Timestamp.from(releasedAt),
                id);
        return updated > 0;
    }

    @Override
    public List<Measurement> findByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(", ", Collections.nCopies(ids.size(), "?"));
        return jdbc.query(
                "SELECT " + SELECT_COLUMNS + " FROM measurement WHERE id IN (" + placeholders + ") ORDER BY id ASC",
                ROW_MAPPER,
                ids.toArray());
    }

    @Override
    public List<Measurement> findCurrentUsable(String instrumentId) {
        String base = "SELECT " + SELECT_COLUMNS_M + " FROM measurement m"
                + " JOIN calibration_certificate c ON c.id = m.certificate_id"
                + " WHERE m.status = 'RELEASED' AND c.revoked = 0";
        if (instrumentId == null) {
            return jdbc.query(base + " ORDER BY m.id ASC", ROW_MAPPER);
        }
        return jdbc.query(
                base + " AND m.instrument_id = ? ORDER BY m.id ASC",
                ROW_MAPPER,
                instrumentId);
    }
}
