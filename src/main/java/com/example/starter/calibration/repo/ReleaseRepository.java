package com.example.starter.calibration.repo;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.ReleaseRecord;

/**
 * 放行历史持久化。历史只增不改，证书撤销或复核驳回后均保留；
 * 重新放行时未驳回位置复用原测量行，因此同一测量可能出现在多个批次。
 */
@Repository
public class ReleaseRepository {

    private static final RowMapper<ReleaseRecord> MAPPER = (rs, rowNum) -> new ReleaseRecord(
            rs.getLong("id"),
            rs.getString("batch_id"),
            rs.getInt("position"),
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
    public void insert(String batchId, int position, long measurementId,
                       String releasedBy, Instant releasedAt) {
        jdbc.update("INSERT INTO release_record (batch_id, position, measurement_id, released_by, released_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                batchId, position, measurementId, releasedBy, JdbcTimes.toDb(releasedAt));
    }

    /**
     * 查询某测量记录的全部放行历史（按时间升序）。重新放行复用原测量行时会出现多条。
     */
    public List<ReleaseRecord> findByMeasurementId(long measurementId) {
        return jdbc.query("SELECT * FROM release_record WHERE measurement_id = ? ORDER BY id",
                MAPPER, measurementId);
    }

    /**
     * 查询某批次的全部放行明细，按位置升序。
     */
    public List<ReleaseRecord> findByBatchId(String batchId) {
        return jdbc.query("SELECT * FROM release_record WHERE batch_id = ? ORDER BY position",
                MAPPER, batchId);
    }
}
