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
 * 采样许可持久化与设备级裁决锁。
 *
 * <p>所有同设备许可裁决（创建交集检测、提前结束、撤销、采样命中与计数扣减）必须先经
 * {@link #lockDevice(String)} 对 permit_device_lock 行加事务级行锁串行化；许可行本身再加行锁。
 * 所有 SQL 使用参数化查询。
 */
@Repository
public class SamplingPermitRepository {

    private static final String COLUMNS =
            "permit_id, device_id, permit_version, grid_min, grid_max, valid_from_utc, valid_to_utc, "
                    + "terminated_at_utc, revoked_at_utc, status, max_observations, observed_count";

    private static final RowMapper<SamplingPermit> PERMIT_MAPPER = (rs, rowNum) -> {
        Timestamp terminated = rs.getTimestamp("terminated_at_utc");
        Timestamp revoked = rs.getTimestamp("revoked_at_utc");
        return new SamplingPermit(
                rs.getString("permit_id"),
                rs.getString("device_id"),
                rs.getString("permit_version"),
                rs.getInt("grid_min"),
                rs.getInt("grid_max"),
                rs.getTimestamp("valid_from_utc").toInstant(),
                rs.getTimestamp("valid_to_utc").toInstant(),
                terminated == null ? null : terminated.toInstant(),
                revoked == null ? null : revoked.toInstant(),
                PermitStatus.valueOf(rs.getString("status")),
                rs.getInt("max_observations"),
                rs.getInt("observed_count"));
    };

    private final JdbcTemplate jdbcTemplate;

    public SamplingPermitRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 获取设备级裁决行锁：锁行不存在时先创建（并发首建冲突由一个事务胜出），随后 SELECT ... FOR UPDATE。
     * 必须在事务内调用，锁随事务提交/回滚释放。
     */
    public void lockDevice(String deviceId) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO permit_device_lock (device_id, created_at) VALUES (?, CURRENT_TIMESTAMP)",
                    deviceId);
        } catch (DuplicateKeyException ignored) {
            // 锁行已由先前事务创建，直接进入加锁步骤
        }
        jdbcTemplate.queryForObject(
                "SELECT device_id FROM permit_device_lock WHERE device_id = ? FOR UPDATE",
                String.class, deviceId);
    }

    /**
     * 按许可标识读取（不加锁）。
     */
    public Optional<SamplingPermit> findById(String permitId) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM sampling_permit WHERE permit_id = ?",
                        PERMIT_MAPPER, permitId)
                .stream().findFirst();
    }

    /**
     * 按许可标识读取并对许可行加锁；调用方应已持有该设备的裁决行锁。
     */
    public Optional<SamplingPermit> findByIdForUpdate(String permitId) {
        return jdbcTemplate.query(
                        "SELECT " + COLUMNS + " FROM sampling_permit WHERE permit_id = ? FOR UPDATE",
                        PERMIT_MAPPER, permitId)
                .stream().findFirst();
    }

    /**
     * 在设备裁决锁内读取该设备全部许可行并对其加锁（含已撤销行，命中判定只取 ACTIVE）。
     */
    public List<SamplingPermit> findByDeviceForUpdate(String deviceId) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM sampling_permit WHERE device_id = ? FOR UPDATE",
                PERMIT_MAPPER, deviceId);
    }

    /**
     * 插入新许可，初始计数为 0、状态 ACTIVE。
     */
    public void insert(SamplingPermit permit) {
        jdbcTemplate.update(
                "INSERT INTO sampling_permit (permit_id, device_id, permit_version, grid_min, grid_max, "
                        + "valid_from_utc, valid_to_utc, terminated_at_utc, revoked_at_utc, status, "
                        + "max_observations, observed_count, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, NULL, NULL, '" + PermitStatus.ACTIVE.name()
                        + "', ?, 0, CURRENT_TIMESTAMP)",
                permit.permitId(), permit.deviceId(), permit.permitVersion(),
                permit.gridMin(), permit.gridMax(),
                Timestamp.from(permit.validFromUtc()), Timestamp.from(permit.validToUtc()),
                permit.maxObservations());
    }

    /**
     * 提前结束：写入终止时刻（不含），不改状态与计数；只对 ACTIVE 许可生效。
     */
    public int terminate(String permitId, Instant terminatedAtUtc) {
        return jdbcTemplate.update(
                "UPDATE sampling_permit SET terminated_at_utc = ? "
                        + "WHERE permit_id = ? AND status = '" + PermitStatus.ACTIVE.name() + "'",
                Timestamp.from(terminatedAtUtc), permitId);
    }

    /**
     * 撤销许可：状态置 REVOKED 并记录撤销时刻（终态，计数保留不清除）。
     */
    public int revoke(String permitId, Instant revokedAtUtc) {
        return jdbcTemplate.update(
                "UPDATE sampling_permit SET status = '" + PermitStatus.REVOKED.name()
                        + "', revoked_at_utc = ? WHERE permit_id = ? AND status = '"
                        + PermitStatus.ACTIVE.name() + "'",
                Timestamp.from(revokedAtUtc), permitId);
    }

    /**
     * 条件扣增计数：仅当许可仍为 ACTIVE 且扣增后不超过上限时生效。
     *
     * @return 更新行数；1 表示扣减成功，0 表示许可失活或将超限（调用方据此回滚/拒绝）
     */
    public int incrementCountIfWithinLimit(String permitId, int delta, int maxObservations) {
        return jdbcTemplate.update(
                "UPDATE sampling_permit SET observed_count = observed_count + ? "
                        + "WHERE permit_id = ? AND status = '" + PermitStatus.ACTIVE.name()
                        + "' AND observed_count + ? <= ?",
                delta, permitId, delta, maxObservations);
    }
}
