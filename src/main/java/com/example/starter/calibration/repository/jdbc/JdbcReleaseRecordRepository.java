package com.example.starter.calibration.repository.jdbc;

import com.example.starter.calibration.domain.ReleaseRecord;
import com.example.starter.calibration.repository.ReleaseRecordRepository;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 基于 JdbcTemplate 的放行历史仓库实现（MySQL）。
 */
@Repository
public class JdbcReleaseRecordRepository implements ReleaseRecordRepository {

    private static final RowMapper<ReleaseRecord> ROW_MAPPER = (rs, rowNum) -> new ReleaseRecord(
            rs.getLong("id"),
            rs.getLong("measurement_id"),
            rs.getLong("certificate_id"),
            rs.getString("released_by"),
            rs.getTimestamp("released_at").toInstant(),
            rs.getString("batch_id"));

    private static final String SELECT_COLUMNS =
            "id, measurement_id, certificate_id, released_by, released_at, batch_id";

    private final JdbcTemplate jdbc;

    public JdbcReleaseRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ReleaseRecord> insertAll(List<ReleaseRecord> records) {
        List<ReleaseRecord> persisted = new ArrayList<>(records.size());
        for (ReleaseRecord record : records) {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO release_record"
                                + " (measurement_id, certificate_id, released_by, released_at, batch_id)"
                                + " VALUES (?, ?, ?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS);
                ps.setLong(1, record.measurementId());
                ps.setLong(2, record.certificateId());
                ps.setString(3, record.releasedBy());
                ps.setTimestamp(4, Timestamp.from(record.releasedAt()));
                ps.setString(5, record.batchId());
                return ps;
            }, keyHolder);
            persisted.add(new ReleaseRecord(
                    keyHolder.getKey().longValue(),
                    record.measurementId(),
                    record.certificateId(),
                    record.releasedBy(),
                    record.releasedAt(),
                    record.batchId()));
        }
        return persisted;
    }

    @Override
    public Optional<ReleaseRecord> findByMeasurementId(long measurementId) {
        return jdbc.query(
                        "SELECT " + SELECT_COLUMNS + " FROM release_record WHERE measurement_id = ?",
                        ROW_MAPPER,
                        measurementId)
                .stream()
                .findFirst();
    }

    @Override
    public List<ReleaseRecord> findByMeasurementIds(Collection<Long> measurementIds) {
        if (measurementIds == null || measurementIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(", ", Collections.nCopies(measurementIds.size(), "?"));
        return jdbc.query(
                "SELECT " + SELECT_COLUMNS + " FROM release_record WHERE measurement_id IN (" + placeholders + ")"
                        + " ORDER BY id ASC",
                ROW_MAPPER,
                measurementIds.toArray());
    }
}
