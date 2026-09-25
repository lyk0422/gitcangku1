package com.example.starter.calibration.repo;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.ReleaseRecord;

/**
 * 放行历史持久化。历史只增不改，证书撤销后保留；
 * 每条记录固化放行时的测量版本号，使已放行快照在后续重算后仍可追溯。
 */
@Repository
public class ReleaseRepository {

    private static final RowMapper<ReleaseRecord> MAPPER = (rs, rowNum) -> new ReleaseRecord(
            rs.getLong("id"),
            rs.getString("batch_id"),
            rs.getLong("measurement_id"),
            rs.getInt("measurement_version_no"),
            rs.getString("released_by"),
            JdbcTimes.fromDb(rs.getObject("released_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public ReleaseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条放行历史，固化放行时的测量版本号。
     */
    public void insert(String batchId, long measurementId, int measurementVersionNo,
                       String releasedBy, Instant releasedAt) {
        jdbc.update("INSERT INTO release_record "
                        + "(batch_id, measurement_id, measurement_version_no, released_by, released_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                batchId, measurementId, measurementVersionNo, releasedBy, JdbcTimes.toDb(releasedAt));
    }

    /**
     * 查询某测量记录的全部放行历史（按时间升序）。
     */
    public List<ReleaseRecord> findByMeasurementId(long measurementId) {
        return jdbc.query("SELECT * FROM release_record WHERE measurement_id = ? ORDER BY id",
                MAPPER, measurementId);
    }

    /**
     * 查询某放行批次的全部记录。
     */
    public List<ReleaseRecord> findByBatchId(String batchId) {
        return jdbc.query("SELECT * FROM release_record WHERE batch_id = ? ORDER BY id", MAPPER, batchId);
    }
}
