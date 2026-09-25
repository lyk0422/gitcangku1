package com.example.starter.calibration.repo;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.ReleaseRecord;

/**
 * 放行历史持久化。历史只增不改，证书撤销后保留。
 */
@Repository
public class ReleaseRepository {

    private static final RowMapper<ReleaseRecord> MAPPER = (rs, rowNum) -> new ReleaseRecord(
            rs.getLong("id"),
            rs.getString("batch_id"),
            rs.getLong("measurement_id"),
            rs.getString("released_by"),
            JdbcTimes.fromDb(rs.getObject("released_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public ReleaseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条放行历史。
     */
    public void insert(String batchId, long measurementId, String releasedBy, Instant releasedAt) {
        jdbc.update("INSERT INTO release_record (batch_id, measurement_id, released_by, released_at) "
                        + "VALUES (?, ?, ?, ?)",
                batchId, measurementId, releasedBy, JdbcTimes.toDb(releasedAt));
    }

    /**
     * 查询某测量记录版本行的全部放行历史（按时间升序）。
     */
    public List<ReleaseRecord> findByMeasurementId(long measurementId) {
        return jdbc.query("SELECT * FROM release_record WHERE measurement_id = ? ORDER BY id",
                MAPPER, measurementId);
    }

    /**
     * 查询某逻辑测量（root_id）全部版本行的放行历史（按时间升序），用于重算链明细。
     */
    public List<ReleaseRecord> findByRootId(long rootId) {
        return jdbc.query(
                "SELECT r.* FROM release_record r "
                        + "JOIN measurement m ON m.id = r.measurement_id "
                        + "WHERE m.root_id = ? ORDER BY r.id",
                MAPPER, rootId);
    }
}
