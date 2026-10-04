package com.example.starter.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 运动间隔许可数据访问。许可轨迹快照不可变；撤销仅更新状态与撤销时刻。
 */
@Repository
public class PermitRepository {

    private final JdbcTemplate jdbc;

    public PermitRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String COLUMNS =
            "permit_id, route_id, route_version, airspace_version, start_epoch_sec, end_epoch_sec, "
                    + "start_x, start_y, end_x, end_y, status, revoked_epoch_sec, request_id, created_at";

    private static final RowMapper<PermitPo> PERMIT_MAPPER = (rs, n) -> new PermitPo(
            rs.getString("permit_id"),
            rs.getString("route_id"),
            rs.getInt("route_version"),
            rs.getLong("airspace_version"),
            rs.getLong("start_epoch_sec"),
            rs.getLong("end_epoch_sec"),
            rs.getDouble("start_x"),
            rs.getDouble("start_y"),
            rs.getDouble("end_x"),
            rs.getDouble("end_y"),
            rs.getString("status"),
            (Long) rs.getObject("revoked_epoch_sec"),
            rs.getString("request_id"),
            rs.getLong("created_at"));

    /** 按 permitId 查询许可，不存在返回 null。 */
    public PermitPo findPermit(String permitId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM permit WHERE permit_id = ?",
                PERMIT_MAPPER, permitId).stream().findFirst().orElse(null);
    }

    /** 查询全部许可（含已撤销），用于间隔审查时按有效占用区间过滤。 */
    public List<PermitPo> findAllPermits() {
        return jdbc.query("SELECT " + COLUMNS + " FROM permit ORDER BY permit_id", PERMIT_MAPPER);
    }

    /** 统计当前活动（ACTIVE）许可数量。 */
    public int countActivePermits() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM permit WHERE status = 'ACTIVE'", Integer.class);
        return count == null ? 0 : count;
    }

    /** 插入许可（轨迹快照在插入时固定，调用方负责事务）。 */
    public void insertPermit(PermitPo po) {
        jdbc.update("INSERT INTO permit (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                po.permitId(), po.routeId(), po.routeVersion(), po.airspaceVersion(),
                po.startEpochSec(), po.endEpochSec(),
                po.startX(), po.startY(), po.endX(), po.endY(),
                po.status(), po.revokedEpochSec(), po.requestId(), po.createdAt());
    }

    /**
     * 条件撤销：仅当当前为 ACTIVE 时置为 REVOKED 并记录撤销时刻（UTC epoch 秒）。
     *
     * @return 更新行数；0 表示许可不存在或已撤销
     */
    public int markRevoked(String permitId, long revokedEpochSec) {
        return jdbc.update("UPDATE permit SET status = 'REVOKED', revoked_epoch_sec = ? "
                + "WHERE permit_id = ? AND status = 'ACTIVE'", revokedEpochSec, permitId);
    }
}