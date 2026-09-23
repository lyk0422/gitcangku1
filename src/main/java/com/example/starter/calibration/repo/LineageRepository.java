package com.example.starter.calibration.repo;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.BatchLineage;

/**
 * 重新放行逐项血缘持久化。新批次每个位置一条，建立新旧批次逐项对应关系。
 */
@Repository
public class LineageRepository {

    private static final RowMapper<BatchLineage> MAPPER = (rs, rowNum) -> new BatchLineage(
            rs.getLong("id"),
            rs.getString("new_batch_id"),
            rs.getString("source_batch_id"),
            rs.getInt("position"),
            rs.getLong("source_measurement_id"),
            rs.getLong("used_measurement_id"),
            rs.getInt("version"),
            rs.getBoolean("revised"));

    private final JdbcTemplate jdbc;

    public LineageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条逐项血缘。
     */
    public void insert(BatchLineage lineage) {
        jdbc.update("INSERT INTO batch_lineage "
                        + "(new_batch_id, source_batch_id, position, source_measurement_id, "
                        + "used_measurement_id, version, revised) VALUES (?, ?, ?, ?, ?, ?, ?)",
                lineage.newBatchId(), lineage.sourceBatchId(), lineage.position(),
                lineage.sourceMeasurementId(), lineage.usedMeasurementId(),
                lineage.version(), lineage.revised());
    }

    /**
     * 查询某新批次的全部逐项血缘，按位置升序。
     */
    public List<BatchLineage> findByNewBatchId(String newBatchId) {
        return jdbc.query("SELECT * FROM batch_lineage WHERE new_batch_id = ? ORDER BY position",
                MAPPER, newBatchId);
    }

    /**
     * 查询以某批次为来源的全部血缘（用于批次差异向上溯源），按新批次与位置升序。
     */
    public List<BatchLineage> findBySourceBatchId(String sourceBatchId) {
        return jdbc.query("SELECT * FROM batch_lineage WHERE source_batch_id = ? "
                        + "ORDER BY new_batch_id, position",
                MAPPER, sourceBatchId);
    }

    /**
     * 判断某来源批次是否已被重新放行（每个冻结批次至多一个后继批次）。
     */
    public boolean existsBySourceBatchId(String sourceBatchId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_lineage WHERE source_batch_id = ?",
                Integer.class, sourceBatchId);
        return count != null && count > 0;
    }
}
