package com.example.starter.calibration.repo;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.ReleaseLineage;

/**
 * 重新放行逐项血缘持久化。血缘只增不改，旧批次与旧结果保持不可变。
 */
@Repository
public class LineageRepository {

    private static final RowMapper<ReleaseLineage> MAPPER = (rs, rowNum) -> new ReleaseLineage(
            rs.getLong("id"),
            rs.getString("batch_id"),
            rs.getLong("measurement_id"),
            rs.getString("source_batch_id"),
            rs.getLong("source_measurement_id"));

    private final JdbcTemplate jdbc;

    public LineageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条逐项血缘。
     */
    public void insert(String batchId, long measurementId, String sourceBatchId, long sourceMeasurementId) {
        jdbc.update("INSERT INTO release_lineage "
                        + "(batch_id, measurement_id, source_batch_id, source_measurement_id) "
                        + "VALUES (?, ?, ?, ?)",
                batchId, measurementId, sourceBatchId, sourceMeasurementId);
    }

    /**
     * 查询某批次的全部逐项血缘（按写入顺序）。
     */
    public List<ReleaseLineage> findByBatchId(String batchId) {
        return jdbc.query("SELECT * FROM release_lineage WHERE batch_id = ? ORDER BY id",
                MAPPER, batchId);
    }
}
