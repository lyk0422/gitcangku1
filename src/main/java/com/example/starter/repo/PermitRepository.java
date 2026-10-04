package com.example.starter.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 两点匀速航线运动间隔许可数据访问。
 * 许可记录创建后轨迹与时刻不可变，仅状态可从 ACTIVE 变为 REVOKED。
 */
@Repository
public class PermitRepository {

    private static final String COLUMNS =
            "permit_id, route_id, route_version, start_second, end_second, "
                    + "ax_milli, ay_milli, bx_milli, by_milli, status, request_id, "
                    + "created_at, revoked_at";

    private final JdbcTemplate jdbc;

    public PermitRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<PermitPo> PERMIT_MAPPER = (rs, n) -> new PermitPo(
            rs.getString("permit_id"),
            rs.getString("route_id"),
            rs.getInt("route_version"),
            rs.getLong("start_second"),
            rs.getLong("end_second"),
            rs.getLong("ax_milli"),
            rs.getLong("ay_milli"),
            rs.getLong("bx_milli"),
            rs.getLong("by_milli"),
            rs.getString("status"),
            rs.getString("request_id"),
            rs.getLong("created_at"),
            (Long) rs.getObject("revoked_at"));

    /** 按 permitId 查询许可，不存在返回 null。 */
    public PermitPo findPermit(String permitId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM flight_permit WHERE permit_id = ?",
                PERMIT_MAPPER, permitId).stream().findFirst().orElse(null);
    }

    /** 查询全部活动（ACTIVE）许可，按许可标识排序保证判定顺序稳定。 */
    public List<PermitPo> findActivePermits() {
        return jdbc.query("SELECT " + COLUMNS
                        + " FROM flight_permit WHERE status = 'ACTIVE' ORDER BY permit_id",
                PERMIT_MAPPER);
    }

    /** 插入许可（调用方负责事务与全部前置校验）。 */
    public void insertPermit(PermitPo po) {
        jdbc.update("INSERT INTO flight_permit "
                        + "(permit_id, route_id, route_version, start_second, end_second, "
                        + "ax_milli, ay_milli, bx_milli, by_milli, status, request_id, "
                        + "created_at, revoked_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                po.permitId(), po.routeId(), po.routeVersion(), po.startSecond(), po.endSecond(),
                po.axMilli(), po.ayMilli(), po.bxMilli(), po.byMilli(),
                po.status(), po.requestId(), po.createdAt(), po.revokedAt());
    }

    /** 撤销许可：仅状态推进为 REVOKED 并记录撤销时间，轨迹与时刻不改写。 */
    public void markRevoked(String permitId, long revokedAt) {
        jdbc.update("UPDATE flight_permit SET status = 'REVOKED', revoked_at = ? "
                + "WHERE permit_id = ? AND status = 'ACTIVE'", revokedAt, permitId);
    }
}