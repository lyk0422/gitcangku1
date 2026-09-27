package com.example.starter.permit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 采样许可持久化：sampling_permit 的插入、加锁读取、范围查询与状态/计数更新。所有 SQL 参数化。
 */
@Repository
public class SamplingPermitRepository {

    private static final String COLUMNS = "permit_id, device_id, permit_version, grid_min_lon, grid_min_lat, "
            + "grid_max_lon, grid_max_lat, valid_from_utc, valid_to_utc, effective_end_utc, max_observations, "
            + "accepted_count, status, revoke_snapshot, revoked_at_utc";

    private static final RowMapper<SamplingPermit> PERMIT_MAPPER = (rs, rowNum) -> new SamplingPermit(
            rs.getString("permit_id"),
            rs.getString("device_id"),
            rs.getInt("permit_version"),
            rs.getBigDecimal("grid_min_lon"),
            rs.getBigDecimal("grid_min_lat"),
            rs.getBigDecimal("grid_max_lon"),
            rs.getBigDecimal("grid_max_lat"),
            rs.getTimestamp("valid_from_utc").toInstant(),
            rs.getTimestamp("valid_to_utc").toInstant(),
            rs.getTimestamp("effective_end_utc") == null ? null : rs.getTimestamp("effective_end_utc").toInstant(),
            rs.getInt("max_observations"),
            rs.getInt("accepted_count"),
            rs.getString("status"),
            rs.getString("revoke_snapshot"),
            rs.getTimestamp("revoked_at_utc") == null ? null : rs.getTimestamp("revoked_at_utc").toInstant());

    private final JdbcTemplate jdbcTemplate;

    public SamplingPermitRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 插入新许可；permitId 主键或 (deviceId, permitVersion) 唯一键冲突时抛出重复键异常。
     */
    public void insert(SamplingPermit permit) {
        jdbcTemplate.update(
                "INSERT INTO sampling_permit (permit_id, device_id, permit_version, grid_min_lon, grid_min_lat, "
                        + "grid_max_lon, grid_max_lat, valid_from_utc, valid_to_utc, effective_end_utc, "
                        + "max_observations, accepted_count, status, revoke_snapshot, revoked_at_utc, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                permit.permitId(), permit.deviceId(), permit.permitVersion(),
                permit.gridMinLon(), permit.gridMinLat(), permit.gridMaxLon(), permit.gridMaxLat(),
                Timestamp.from(permit.validFrom()), Timestamp.from(permit.validTo()),
                permit.effectiveEnd() == null ? null : Timestamp.from(permit.effectiveEnd()),
                permit.maxObservations(), permit.acceptedCount(), permit.status(),
                permit.revokeSnapshot(),
                permit.revokedAt() == null ? null : Timestamp.from(permit.revokedAt()));
    }

    /**
     * 按许可标识加锁读取当前行。
     */
    public Optional<SamplingPermit> findByIdForUpdate(String permitId) {
        return jdbcTemplate.query(
                        "SELECT " + COLUMNS + " FROM sampling_permit WHERE permit_id = ? FOR UPDATE",
                        PERMIT_MAPPER, permitId)
                .stream().findFirst();
    }

    /**
     * 按许可标识读取当前行（不加锁）。
     */
    public Optional<SamplingPermit> findById(String permitId) {
        return jdbcTemplate.query(
                        "SELECT " + COLUMNS + " FROM sampling_permit WHERE permit_id = ?",
                        PERMIT_MAPPER, permitId)
                .stream().findFirst();
    }

    /**
     * 读取同一设备的全部许可（含已结束/撤销），用于交集判定。
     */
    public List<SamplingPermit> findByDevice(String deviceId) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM sampling_permit WHERE device_id = ? ORDER BY permit_version ASC",
                PERMIT_MAPPER, deviceId);
    }

    /**
     * 读取同一设备、自然有效期与 [from,to) 可能相交的许可并加锁，用于创建时重叠判定的串行化。
     * 已提前结束/撤销许可是否真正相交由业务层按实际窗口在 Java 中精判。
     */
    public List<SamplingPermit> findDevicePermitsForUpdate(String deviceId, Instant from, Instant to) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM sampling_permit WHERE device_id = ? "
                        + "AND valid_from_utc < ? AND valid_to_utc > ? FOR UPDATE",
                PERMIT_MAPPER, deviceId, Timestamp.from(to), Timestamp.from(from));
    }

    /**
     * 读取同一设备在指定采样时刻时间有效（自然窗口覆盖）的全部许可并加锁；
     * 已撤销许可一律不参与命中（撤销是强失效，禁止撤销后回填旧采样），
     * 提前结束许可是否覆盖由业务层按实际截断窗口精判。用于提交/合并事务内串行化配额扣减。
     */
    public List<SamplingPermit> findDeviceTimeCandidatesForUpdate(String deviceId, Instant sampledAt) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM sampling_permit WHERE device_id = ? "
                        + "AND status <> 'REVOKED' "
                        + "AND valid_from_utc <= ? AND valid_to_utc > ? ORDER BY permit_version ASC FOR UPDATE",
                PERMIT_MAPPER, deviceId, Timestamp.from(sampledAt), Timestamp.from(sampledAt));
    }

    /**
     * 更新许可状态与实际结束时刻（提前结束/撤销共用）。
     */
    public void updateState(String permitId, String status, Instant effectiveEnd) {
        jdbcTemplate.update(
                "UPDATE sampling_permit SET status = ?, effective_end_utc = ? WHERE permit_id = ?",
                status, Timestamp.from(effectiveEnd), permitId);
    }

    /**
     * 撤销：更新状态、实际结束时刻、撤销快照与撤销时刻。
     */
    public void updateRevoked(String permitId, Instant effectiveEnd,
                              String snapshotJson, Instant revokedAt) {
        jdbcTemplate.update(
                "UPDATE sampling_permit SET status = ?, effective_end_utc = ?, revoke_snapshot = ?, "
                        + "revoked_at_utc = ? WHERE permit_id = ?",
                SamplingPermit.REVOKED, Timestamp.from(effectiveEnd), snapshotJson,
                Timestamp.from(revokedAt), permitId);
    }

    /**
     * 条件式扣减计数（accepted_count + delta <= max_observations 才成功）；返回是否扣减成功。
     * 调用方须在事务内持有该许可行锁，delta 为正数。
     */
    public boolean incrementCount(String permitId, int delta) {
        int affected = jdbcTemplate.update(
                "UPDATE sampling_permit SET accepted_count = accepted_count + ? "
                        + "WHERE permit_id = ? AND accepted_count + ? <= max_observations",
                delta, permitId, delta);
        return affected > 0;
    }

    /**
     * 锁定采样设备行（不存在则先注册），串行化同一设备的许可创建交集判定。
     */
    public void lockDevice(String deviceId) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO sampling_device (device_id, created_at) VALUES (?, CURRENT_TIMESTAMP)",
                    deviceId);
        } catch (org.springframework.dao.DuplicateKeyException ignored) {
            // 设备已注册：直接进入加锁读取即可。
        }
        jdbcTemplate.queryForRowSet(
                "SELECT device_id FROM sampling_device WHERE device_id = ? FOR UPDATE", deviceId);
    }
}
