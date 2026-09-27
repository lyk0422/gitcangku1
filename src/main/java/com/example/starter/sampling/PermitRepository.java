package com.example.starter.sampling;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 采样许可持久化：维护 sampling_permit 配置与已接收计数。所有 SQL 使用参数化查询。
 */
@Repository
public class PermitRepository {

    private static final String COLUMNS = "permit_id, device_id, grid_min_x, grid_max_x, grid_min_y, grid_max_y, "
            + "valid_from_utc, valid_to_utc, max_observations, used_count, permit_version, status";

    private static final RowMapper<SamplingPermit> PERMIT_MAPPER = (rs, rowNum) -> new SamplingPermit(
            rs.getString("permit_id"),
            rs.getString("device_id"),
            rs.getInt("grid_min_x"),
            rs.getInt("grid_max_x"),
            rs.getInt("grid_min_y"),
            rs.getInt("grid_max_y"),
            rs.getTimestamp("valid_from_utc").toInstant(),
            rs.getTimestamp("valid_to_utc").toInstant(),
            rs.getInt("max_observations"),
            rs.getInt("used_count"),
            rs.getInt("permit_version"),
            PermitStatus.valueOf(rs.getString("status")));

    private final JdbcTemplate jdbcTemplate;

    public PermitRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 插入新许可，初始计数为 0、状态 ACTIVE。
     */
    public void insert(SamplingPermit permit) {
        jdbcTemplate.update(
                "INSERT INTO sampling_permit (permit_id, device_id, grid_min_x, grid_max_x, grid_min_y, grid_max_y, "
                        + "valid_from_utc, valid_to_utc, max_observations, used_count, permit_version, status, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                permit.permitId(), permit.deviceId(),
                permit.gridMinX(), permit.gridMaxX(), permit.gridMinY(), permit.gridMaxY(),
                Timestamp.from(permit.validFromUtc()), Timestamp.from(permit.validToUtc()),
                permit.maxObservations(), permit.usedCount(), permit.permitVersion(), permit.status().name());
    }

    /**
     * 按标识查询许可（不加锁）。
     */
    public Optional<SamplingPermit> findById(String permitId) {
        return jdbcTemplate.query(
                        "SELECT " + COLUMNS + " FROM sampling_permit WHERE permit_id = ?",
                        PERMIT_MAPPER, permitId)
                .stream().findFirst();
    }

    /**
     * 按标识查询许可并加行锁（SELECT ... FOR UPDATE），用于计数扣减与状态变更的串行化。
     */
    public Optional<SamplingPermit> findByIdForUpdate(String permitId) {
        return jdbcTemplate.query(
                        "SELECT " + COLUMNS + " FROM sampling_permit WHERE permit_id = ? FOR UPDATE",
                        PERMIT_MAPPER, permitId)
                .stream().findFirst();
    }

    /**
     * 查询同设备有效（ACTIVE）许可中与指定网格范围及有效期相交的许可：
     * 网格相交且 [validFrom, validTo) 与 [from, to) 有交集即视为重叠。
     */
    public List<SamplingPermit> findOverlappingActive(String deviceId, int minX, int maxX, int minY, int maxY,
                                                      Instant validFromUtc, Instant validToUtc) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM sampling_permit "
                        + "WHERE device_id = ? AND status = 'ACTIVE' "
                        + "AND grid_min_x <= ? AND grid_max_x >= ? AND grid_min_y <= ? AND grid_max_y >= ? "
                        + "AND valid_from_utc < ? AND valid_to_utc > ?",
                PERMIT_MAPPER, deviceId, maxX, minX, maxY, minY,
                Timestamp.from(validToUtc), Timestamp.from(validFromUtc));
    }

    /**
     * 查询指定设备、网格坐标与采集时刻命中的许可：坐标落在网格范围内、
     * 时刻落在 [validFrom, validTo) 内且许可未被撤销（ACTIVE 或提前结束的 ENDED）。
     */
    public List<SamplingPermit> findHits(String deviceId, int gridX, int gridY, Instant collectedAtUtc) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM sampling_permit "
                        + "WHERE device_id = ? AND status IN ('ACTIVE', 'ENDED') "
                        + "AND grid_min_x <= ? AND grid_max_x >= ? AND grid_min_y <= ? AND grid_max_y >= ? "
                        + "AND valid_from_utc <= ? AND valid_to_utc > ?",
                PERMIT_MAPPER, deviceId, gridX, gridX, gridY, gridY,
                Timestamp.from(collectedAtUtc), Timestamp.from(collectedAtUtc));
    }

    /**
     * 前移有效期终点并更新状态（许可提前结束）。
     */
    public void updateWindowAndStatus(String permitId, Instant validToUtc, PermitStatus status) {
        jdbcTemplate.update(
                "UPDATE sampling_permit SET valid_to_utc = ?, status = ? WHERE permit_id = ?",
                Timestamp.from(validToUtc), status.name(), permitId);
    }

    /**
     * 更新许可状态（如撤销）。
     */
    public void updateStatus(String permitId, PermitStatus status) {
        jdbcTemplate.update(
                "UPDATE sampling_permit SET status = ? WHERE permit_id = ?",
                status.name(), permitId);
    }

    /**
     * 增加已接收观测计数；调用方须在行锁内先校验不超上限。
     */
    public void addUsedCount(String permitId, int delta) {
        jdbcTemplate.update(
                "UPDATE sampling_permit SET used_count = used_count + ? WHERE permit_id = ?",
                delta, permitId);
    }
}
