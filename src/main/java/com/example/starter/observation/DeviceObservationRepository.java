package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * 设备观测提交持久化：device_observation 每次提交追加一行版本，
 * 原始本地时刻不可改写，矫正后时刻与合并顺序仅由系统在提交或重建时更新。
 * 所有 SQL 使用参数化查询；TIMESTAMP 列按 UTC 字段读写。
 */
@Repository
public class DeviceObservationRepository {

    private static final RowMapper<DeviceObservation> OBSERVATION_MAPPER = (rs, rowNum) -> new DeviceObservation(
            rs.getString("observation_id"),
            rs.getInt("version"),
            rs.getString("device_id"),
            UtcJdbc.getLocalDateTime(rs, "device_local_time"),
            UtcJdbc.getInstant(rs, "corrected_at_utc"),
            rs.getString("location"),
            rs.getString("reading"),
            rs.getString("note"),
            rs.getLong("merge_seq"));

    private static final String COLUMNS =
            "observation_id, version, device_id, device_local_time, corrected_at_utc, "
                    + "location, reading, note, merge_seq";

    private final JdbcTemplate jdbcTemplate;

    public DeviceObservationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 锁定合并顺序全局互斥行（merge_order_lock id=1），
     * 使提交、偏移登记与修改在同一事务内串行，按事务提交顺序裁决并发。
     */
    public void acquireMergeLock() {
        jdbcTemplate.query("SELECT id FROM merge_order_lock WHERE id = 1 FOR UPDATE",
                (rs, rowNum) -> rs.getInt("id"));
    }

    /**
     * 追加一行观测版本。
     */
    public void insert(DeviceObservation observation) {
        jdbcTemplate.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO device_observation (" + COLUMNS + ", created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)");
            ps.setString(1, observation.observationId());
            ps.setInt(2, observation.version());
            ps.setString(3, observation.deviceId());
            UtcJdbc.setLocalDateTime(ps, 4, observation.deviceLocalTime());
            UtcJdbc.setInstant(ps, 5, observation.correctedAtUtc());
            ps.setString(6, observation.location());
            ps.setString(7, observation.reading());
            ps.setString(8, observation.note());
            ps.setLong(9, observation.mergeSeq());
            return ps;
        });
    }

    /**
     * 按观测标识查询全部版本，按版本号升序。
     */
    public List<DeviceObservation> findByObservationId(String observationId) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM device_observation WHERE observation_id = ? ORDER BY version ASC",
                OBSERVATION_MAPPER, observationId);
    }

    /**
     * 查询全部观测版本（重建与重排在全局互斥行锁内调用，读到的是本事务一致视图）。
     */
    public List<DeviceObservation> findAll() {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM device_observation ORDER BY observation_id ASC, version ASC",
                OBSERVATION_MAPPER);
    }

    /**
     * 更新指定版本的矫正后时刻与合并顺序（重建时调用；原始本地时刻永不被改写）。
     */
    public void updateTiming(String observationId, int version, Instant correctedAtUtc, long mergeSeq) {
        jdbcTemplate.update(con -> {
            var ps = con.prepareStatement(
                    "UPDATE device_observation SET corrected_at_utc = ?, merge_seq = ? "
                            + "WHERE observation_id = ? AND version = ?");
            UtcJdbc.setInstant(ps, 1, correctedAtUtc);
            ps.setLong(2, mergeSeq);
            ps.setString(3, observationId);
            ps.setInt(4, version);
            return ps;
        });
    }
}
