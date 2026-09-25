package com.example.starter.calibration.repo;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.JointReleaseBatch;
import com.example.starter.calibration.model.JointReleaseItem;

/**
 * 跨仪器联合放行持久化。联合批次记录只增不改（不可变）；
 * joint_batch_key 全局唯一作为业务幂等键，requestId 用于同键同参重放返回首次响应快照。
 */
@Repository
public class JointReleaseRepository {

    private static final RowMapper<JointReleaseBatch> BATCH_MAPPER = (rs, rowNum) -> new JointReleaseBatch(
            rs.getLong("id"),
            rs.getString("joint_batch_key"),
            rs.getString("request_id"),
            rs.getString("released_by"),
            JdbcTimes.fromDb(rs.getObject("released_at", LocalDateTime.class)),
            rs.getString("snapshot_keys"),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private static final RowMapper<JointReleaseItem> ITEM_MAPPER = (rs, rowNum) -> new JointReleaseItem(
            rs.getLong("id"),
            rs.getLong("joint_batch_id"),
            rs.getString("joint_batch_key"),
            rs.getLong("measurement_id"),
            rs.getString("measurement_key"),
            rs.getString("instrument_id"),
            rs.getLong("certificate_id"),
            rs.getBigDecimal("computed_value"),
            rs.getString("released_by"),
            JdbcTimes.fromDb(rs.getObject("released_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public JointReleaseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入联合批次头记录并返回自增主键；joint_batch_key 冲突时抛出 DuplicateKeyException。
     */
    public long insertBatch(String jointBatchKey, String requestId, String releasedBy,
                            Instant releasedAt, String snapshotKeys, Instant createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO joint_release_batch "
                            + "(joint_batch_key, request_id, released_by, released_at, snapshot_keys, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, jointBatchKey);
            ps.setString(2, requestId);
            ps.setString(3, releasedBy);
            ps.setObject(4, JdbcTimes.toDb(releasedAt));
            ps.setString(5, snapshotKeys);
            ps.setObject(6, JdbcTimes.toDb(createdAt));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 追加一条联合放行明细快照（不可变）。
     */
    public void insertItem(long jointBatchId, String jointBatchKey, JointReleaseItem item) {
        jdbc.update("INSERT INTO joint_release_item "
                        + "(joint_batch_id, joint_batch_key, measurement_id, measurement_key, instrument_id, "
                        + "certificate_id, computed_value, released_by, released_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                jointBatchId,
                jointBatchKey,
                item.measurementId(),
                item.measurementKey(),
                item.instrumentId(),
                item.certificateId(),
                item.computedValue(),
                item.releasedBy(),
                JdbcTimes.toDb(item.releasedAt()));
    }

    /**
     * 按联合批次键查询头记录。
     */
    public Optional<JointReleaseBatch> findBatchByJointKey(String jointBatchKey) {
        return jdbc.query("SELECT * FROM joint_release_batch WHERE joint_batch_key = ?",
                        BATCH_MAPPER, jointBatchKey)
                .stream().findFirst();
    }

    /**
     * 按 requestId 查询首次成功的联合批次头记录（按插入顺序取最早一条）。
     */
    public Optional<JointReleaseBatch> findBatchByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM joint_release_batch WHERE request_id = ? ORDER BY id LIMIT 1",
                        BATCH_MAPPER, requestId)
                .stream().findFirst();
    }

    /**
     * 按批次头 ID 查询全部明细，按测量键字典序稳定排序（键相同再按明细 ID）。
     */
    public List<JointReleaseItem> findItemsByJointBatchId(long jointBatchId) {
        return jdbc.query("SELECT * FROM joint_release_item WHERE joint_batch_id = ? "
                        + "ORDER BY measurement_key, id",
                ITEM_MAPPER, jointBatchId);
    }

    /**
     * 统计某测量已被联合批次放行的明细数；用于区分“被并发联合批次抢占（409）”
     * 与“经由既有单条入口放行后的业务校验失败（422）”。
     */
    public int countItemsByMeasurementId(long measurementId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM joint_release_item WHERE measurement_id = ?",
                Integer.class, measurementId);
        return count == null ? 0 : count;
    }
}
