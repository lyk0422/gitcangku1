package com.example.starter.observation;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 观测采样许可持久化：sampling_permit 许可表与 permit_device_lock 设备级互斥锁表。
 * 所有 SQL 使用参数化查询。
 */
@Repository
public class PermitRepository {

    private static final String PERMIT_COLUMNS =
            "permit_id, device_id, grid_x_min, grid_x_max, grid_y_min, grid_y_max, "
                    + "valid_from_utc, valid_to_utc, max_observations, used_count, permit_version, status";

    private static final RowMapper<SamplingPermit> PERMIT_MAPPER = (rs, rowNum) -> new SamplingPermit(
            rs.getString("permit_id"),
            rs.getString("device_id"),
            rs.getInt("grid_x_min"),
            rs.getInt("grid_x_max"),
            rs.getInt("grid_y_min"),
            rs.getInt("grid_y_max"),
            rs.getTimestamp("valid_from_utc").toInstant(),
            rs.getTimestamp("valid_to_utc").toInstant(),
            rs.getInt("max_observations"),
            rs.getInt("used_count"),
            rs.getInt("permit_version"),
            rs.getString("status"));

    private final JdbcTemplate jdbcTemplate;

    public PermitRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 按许可标识查询（不加锁）；不存在时返回空。
     */
    public Optional<SamplingPermit> findByPermitId(String permitId) {
        return jdbcTemplate.query(
                        "SELECT " + PERMIT_COLUMNS + " FROM sampling_permit WHERE permit_id = ?",
                        PERMIT_MAPPER, permitId)
                .stream().findFirst();
    }

    /**
     * 按许可标识查询并加行锁（SELECT ... FOR UPDATE），用于提前结束/撤销等写事务。
     */
    public Optional<SamplingPermit> findByPermitIdForUpdate(String permitId) {
        return jdbcTemplate.query(
                        "SELECT " + PERMIT_COLUMNS + " FROM sampling_permit WHERE permit_id = ? FOR UPDATE",
                        PERMIT_MAPPER, permitId)
                .stream().findFirst();
    }

    /**
     * 查询设备当前有效的许可（ACTIVE/ENDED）并加行锁，用于提交观测时解析唯一命中许可、
     * 以及创建许可时的交集重叠校验；行锁将同一设备的许可判定串行化。
     */
    public List<SamplingPermit> findValidByDeviceForUpdate(String deviceId) {
        return jdbcTemplate.query(
                "SELECT " + PERMIT_COLUMNS + " FROM sampling_permit "
                        + "WHERE device_id = ? AND status IN ('ACTIVE', 'ENDED') ORDER BY permit_id FOR UPDATE",
                PERMIT_MAPPER, deviceId);
    }

    /**
     * 插入新许可（初始 used_count = 0，状态 ACTIVE）。
     */
    public void insert(SamplingPermit permit) {
        jdbcTemplate.update(
                "INSERT INTO sampling_permit (permit_id, device_id, grid_x_min, grid_x_max, grid_y_min, grid_y_max, "
                        + "valid_from_utc, valid_to_utc, max_observations, used_count, permit_version, status, "
                        + "created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                permit.permitId(), permit.deviceId(),
                permit.gridXMin(), permit.gridXMax(), permit.gridYMin(), permit.gridYMax(),
                Timestamp.from(permit.validFromUtc()), Timestamp.from(permit.validToUtc()),
                permit.maxObservations(), permit.usedCount(), permit.permitVersion(), permit.status());
    }

    /**
     * 扣减许可观测计数（加一）；调用方须在行锁保护下先校验未超上限。
     */
    public void incrementUsedCount(String permitId) {
        jdbcTemplate.update(
                "UPDATE sampling_permit SET used_count = used_count + 1, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE permit_id = ?",
                permitId);
    }

    /**
     * 提前结束许可：有效期结束时刻提前到指定 UTC 时刻，状态置为 ENDED。
     */
    public void markEnded(String permitId, Instant newValidToUtc) {
        jdbcTemplate.update(
                "UPDATE sampling_permit SET valid_to_utc = ?, status = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE permit_id = ?",
                Timestamp.from(newValidToUtc), SamplingPermit.STATUS_ENDED, permitId);
    }

    /**
     * 撤销许可：状态置为 REVOKED，之后不再命中任何提交。
     */
    public void markRevoked(String permitId) {
        jdbcTemplate.update(
                "UPDATE sampling_permit SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE permit_id = ?",
                SamplingPermit.STATUS_REVOKED, permitId);
    }

    /**
     * 对设备加互斥锁：锁记录不存在则插入（并发插入由主键串行化），随后 SELECT ... FOR UPDATE
     * 持有行锁直到事务结束，用于串行化同一设备的许可创建。
     */
    public void lockDevice(String deviceId) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO permit_device_lock (device_id, created_at) VALUES (?, CURRENT_TIMESTAMP)",
                    deviceId);
        } catch (DuplicateKeyException e) {
            // 锁记录已存在：直接加锁即可
        }
        jdbcTemplate.queryForObject(
                "SELECT device_id FROM permit_device_lock WHERE device_id = ? FOR UPDATE",
                String.class, deviceId);
    }
}
