package com.example.starter.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 运动间隔许可数据访问。两点快照以 "x,y;x,y" 文本保存，内容不可变。
 */
@Repository
public class PermitRepository {

    private final JdbcTemplate jdbc;

    public PermitRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String COLUMNS = "permit_id, route_id, route_version, operator, "
            + "airspace_version, start_epoch_second, end_epoch_second, points_snapshot, "
            + "status, request_id, created_at, revoked_at";

    private static final RowMapper<PermitPo> PERMIT_MAPPER = (rs, n) -> new PermitPo(
            rs.getString("permit_id"),
            rs.getString("route_id"),
            rs.getInt("route_version"),
            rs.getString("operator"),
            rs.getLong("airspace_version"),
            rs.getLong("start_epoch_second"),
            rs.getLong("end_epoch_second"),
            ReviewRepository.decodePoints(rs.getString("points_snapshot")),
            rs.getString("status"),
            rs.getString("request_id"),
            rs.getLong("created_at"),
            (Long) rs.getObject("revoked_at"));

    /** 按 permitId 查询许可，不存在返回 null。 */
    public PermitPo findPermit(String permitId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM permit WHERE permit_id = ?",
                PERMIT_MAPPER, permitId).stream().findFirst().orElse(null);
    }

    /** 查询全部活动（ACTIVE）许可，按 permitId 排序保证确定顺序。 */
    public List<PermitPo> findActivePermits() {
        return jdbc.query("SELECT " + COLUMNS + " FROM permit WHERE status = 'ACTIVE' "
                + "ORDER BY permit_id", PERMIT_MAPPER);
    }

    /** 插入许可（调用方负责事务与全部前置校验）。 */
    public void insertPermit(PermitPo po) {
        jdbc.update("INSERT INTO permit (permit_id, route_id, route_version, operator, "
                        + "airspace_version, start_epoch_second, end_epoch_second, points_snapshot, "
                        + "status, request_id, created_at, revoked_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                po.permitId(), po.routeId(), po.routeVersion(), po.operator(),
                po.airspaceVersion(), po.startEpochSecond(), po.endEpochSecond(),
                ReviewRepository.encodePoints(po.pointsSnapshot()),
                po.status(), po.requestId(), po.createdAt(), po.revokedAt());
    }

    /** 撤销许可并记录撤销时间（调用方负责事务）；历史轨迹快照保留不改写。 */
    public void markRevoked(String permitId, long revokedAt) {
        jdbc.update("UPDATE permit SET status = 'REVOKED', revoked_at = ? "
                + "WHERE permit_id = ? AND status = 'ACTIVE'", revokedAt, permitId);
    }
}