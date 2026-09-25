package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * 基准重算记录持久化：frame_recalc 记录重算前后簇快照与参数版本，不可变、永不更新。
 * 所有 SQL 使用参数化查询。
 */
@Repository
public class FrameRecalcRepository {

    private static final String COLUMNS = "recalc_id, device_id, old_frame_version, new_frame_version, "
            + "old_clusters, new_clusters, recalced_at_utc";

    private static final RowMapper<FrameRecalcRecord> RECALC_MAPPER = (rs, rowNum) -> new FrameRecalcRecord(
            rs.getString("recalc_id"),
            rs.getString("device_id"),
            rs.getString("old_frame_version"),
            rs.getString("new_frame_version"),
            rs.getString("old_clusters"),
            rs.getString("new_clusters"),
            rs.getTimestamp("recalced_at_utc").toInstant());

    private final JdbcTemplate jdbcTemplate;

    public FrameRecalcRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 写入一条不可变重算记录。
     */
    public void insert(FrameRecalcRecord record) {
        jdbcTemplate.update(
                "INSERT INTO frame_recalc (recalc_id, device_id, old_frame_version, new_frame_version, "
                        + "old_clusters, new_clusters, recalced_at_utc, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                record.recalcId(), record.deviceId(), record.oldFrameVersion(), record.newFrameVersion(),
                record.oldClusters(), record.newClusters(), Timestamp.from(record.recalcedAtUtc()));
    }

    /**
     * 按标识查询重算记录；不存在时返回空。
     */
    public Optional<FrameRecalcRecord> find(String recalcId) {
        return jdbcTemplate.query(
                        "SELECT " + COLUMNS + " FROM frame_recalc WHERE recalc_id = ?",
                        RECALC_MAPPER, recalcId)
                .stream().findFirst();
    }

    /**
     * 按设备查询全部重算记录（按重算时刻先后排序）。
     */
    public List<FrameRecalcRecord> findByDeviceId(String deviceId) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM frame_recalc WHERE device_id = ? "
                        + "ORDER BY recalced_at_utc ASC, recalc_id ASC",
                RECALC_MAPPER, deviceId);
    }
}
