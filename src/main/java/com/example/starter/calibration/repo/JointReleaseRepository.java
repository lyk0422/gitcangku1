package com.example.starter.calibration.repo;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.JointReleaseBatch;
import com.example.starter.calibration.model.JointReleaseItem;

/**
 * 联合放行批次与明细持久化。记录不可变：只插不改；
 * joint_batch_key 主键与 measurement_id 唯一约束由数据库保证并发下的幂等与独占。
 */
@Repository
public class JointReleaseRepository {

    private static final RowMapper<JointReleaseBatch> BATCH_MAPPER = (rs, rowNum) -> new JointReleaseBatch(
            rs.getString("joint_batch_key"),
            rs.getString("request_fingerprint"),
            rs.getString("released_by"),
            JdbcTimes.fromDb(rs.getObject("released_at", LocalDateTime.class)),
            rs.getInt("item_count"));

    private static final RowMapper<JointReleaseItem> ITEM_MAPPER = (rs, rowNum) -> new JointReleaseItem(
            rs.getLong("id"),
            rs.getString("joint_batch_key"),
            rs.getInt("measurement_sort"),
            rs.getLong("measurement_id"),
            rs.getString("measurement_key"),
            rs.getLong("certificate_id"),
            rs.getBigDecimal("computed_value"));

    private final JdbcTemplate jdbc;

    public JointReleaseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 写入联合放行批次记录；joint_batch_key 冲突时抛出 DuplicateKeyException。
     */
    public void insertBatch(JointReleaseBatch batch) {
        jdbc.update("INSERT INTO joint_release_batch "
                        + "(joint_batch_key, request_fingerprint, released_by, released_at, item_count) "
                        + "VALUES (?, ?, ?, ?, ?)",
                batch.jointBatchKey(), batch.requestFingerprint(), batch.releasedBy(),
                JdbcTimes.toDb(batch.releasedAt()), batch.itemCount());
    }

    /**
     * 写入一条联合放行明细快照；measurement_id 冲突时抛出 DuplicateKeyException。
     */
    public void insertItem(JointReleaseItem item) {
        jdbc.update("INSERT INTO joint_release_item "
                        + "(joint_batch_key, measurement_sort, measurement_id, measurement_key, "
                        + "certificate_id, computed_value) VALUES (?, ?, ?, ?, ?, ?)",
                item.jointBatchKey(), item.measurementSort(), item.measurementId(),
                item.measurementKey(), item.certificateId(), item.computedValue());
    }

    /**
     * 按联合批次键查询批次记录（不加锁）。
     */
    public Optional<JointReleaseBatch> findBatchByKey(String jointBatchKey) {
        return jdbc.query("SELECT * FROM joint_release_batch WHERE joint_batch_key = ?",
                BATCH_MAPPER, jointBatchKey).stream().findFirst();
    }

    /**
     * 查询某联合批次的全部测量放行明细，按批内排序序号稳定升序。
     */
    public List<JointReleaseItem> findItemsByBatchKey(String jointBatchKey) {
        return jdbc.query("SELECT * FROM joint_release_item WHERE joint_batch_key = ? "
                + "ORDER BY measurement_sort", ITEM_MAPPER, jointBatchKey);
    }
}
