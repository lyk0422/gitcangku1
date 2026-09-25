package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 设备时钟偏移持久化：device_offset 记录按设备与生效起始时刻唯一，
 * 区间由同一设备内相邻起始时刻推导，起始时刻相同即重叠（主键冲突）。
 * 所有 SQL 使用参数化查询；TIMESTAMP 列按 UTC 字段读写。
 */
@Repository
public class DeviceOffsetRepository {

    private static final RowMapper<DeviceOffset> OFFSET_MAPPER = (rs, rowNum) -> new DeviceOffset(
            rs.getString("device_id"),
            UtcJdbc.getInstant(rs, "effective_from_utc"),
            rs.getInt("offset_seconds"));

    private final JdbcTemplate jdbcTemplate;

    public DeviceOffsetRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 插入一条偏移记录；(deviceId, effectiveFromUtc) 主键冲突时抛出重复键异常（区间重叠）。
     */
    public void insert(DeviceOffset offset) {
        jdbcTemplate.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO device_offset (device_id, effective_from_utc, offset_seconds, created_at, updated_at) "
                            + "VALUES (?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            ps.setString(1, offset.deviceId());
            UtcJdbc.setInstant(ps, 2, offset.effectiveFromUtc());
            ps.setInt(3, offset.offsetSeconds());
            return ps;
        });
    }

    /**
     * 修改既有记录的偏移秒数；返回受影响行数（0 表示记录不存在）。
     */
    public int updateSeconds(String deviceId, Instant effectiveFromUtc, int offsetSeconds) {
        return jdbcTemplate.update(con -> {
            var ps = con.prepareStatement(
                    "UPDATE device_offset SET offset_seconds = ?, updated_at = CURRENT_TIMESTAMP "
                            + "WHERE device_id = ? AND effective_from_utc = ?");
            ps.setInt(1, offsetSeconds);
            ps.setString(2, deviceId);
            UtcJdbc.setInstant(ps, 3, effectiveFromUtc);
            return ps;
        });
    }

    /**
     * 按设备与生效起始时刻精确查询；不存在时返回空。
     */
    public Optional<DeviceOffset> find(String deviceId, Instant effectiveFromUtc) {
        return jdbcTemplate.query(con -> {
                    var ps = con.prepareStatement(
                            "SELECT device_id, effective_from_utc, offset_seconds FROM device_offset "
                                    + "WHERE device_id = ? AND effective_from_utc = ?");
                    ps.setString(1, deviceId);
                    UtcJdbc.setInstant(ps, 2, effectiveFromUtc);
                    return ps;
                }, OFFSET_MAPPER)
                .stream().findFirst();
    }

    /**
     * 查询设备全部偏移记录，按生效起始时刻升序。
     */
    public List<DeviceOffset> findByDevice(String deviceId) {
        return jdbcTemplate.query(con -> {
                    var ps = con.prepareStatement(
                            "SELECT device_id, effective_from_utc, offset_seconds FROM device_offset "
                                    + "WHERE device_id = ? ORDER BY effective_from_utc ASC");
                    ps.setString(1, deviceId);
                    return ps;
                }, OFFSET_MAPPER);
    }

    /**
     * 查询覆盖指定设备本地时刻的偏移记录：生效起始时刻不大于该时刻的最后一条记录。
     * 本地读数按 UTC 时间线上的点参与比较。
     */
    public Optional<DeviceOffset> findCovering(String deviceId, LocalDateTime deviceLocalTime) {
        return jdbcTemplate.query(con -> {
                    var ps = con.prepareStatement(
                            "SELECT device_id, effective_from_utc, offset_seconds FROM device_offset "
                                    + "WHERE device_id = ? AND effective_from_utc <= ? "
                                    + "ORDER BY effective_from_utc DESC LIMIT 1");
                    ps.setString(1, deviceId);
                    UtcJdbc.setLocalDateTime(ps, 2, deviceLocalTime);
                    return ps;
                }, OFFSET_MAPPER)
                .stream().findFirst();
    }
}
