package com.example.starter.repo;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 空域占用数据访问。占用创建后仅发生状态推进（YIELDED/DEPARTED）与
 * superseded_by 关联写入；审查证据（reviewId、版本、航点快照）不可变。
 */
@Repository
public class OccupationRepository {

    private final JdbcTemplate jdbc;

    public OccupationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String OCCUPATION_COLUMNS =
            "occupation_id, route_id, route_version, kind, start_utc, end_utc, alt_min, alt_max, "
                    + "yieldable, status, review_id, airspace_version, points_snapshot, "
                    + "superseded_by, created_at, departed_at";

    private static final RowMapper<OccupationPo> OCCUPATION_MAPPER = (rs, n) -> new OccupationPo(
            rs.getString("occupation_id"),
            rs.getString("route_id"),
            rs.getInt("route_version"),
            rs.getString("kind"),
            rs.getLong("start_utc"),
            rs.getLong("end_utc"),
            rs.getInt("alt_min"),
            rs.getInt("alt_max"),
            rs.getBoolean("yieldable"),
            rs.getString("status"),
            rs.getString("review_id"),
            rs.getLong("airspace_version"),
            ReviewRepository.decodePoints(rs.getString("points_snapshot")),
            rs.getString("superseded_by"),
            rs.getLong("created_at"),
            (Long) rs.getObject("departed_at"));

    /** 按 occupationId 查询占用，不存在返回 null。 */
    public OccupationPo findOccupation(String occupationId) {
        try {
            return jdbc.queryForObject(
                    "SELECT " + OCCUPATION_COLUMNS + " FROM occupation WHERE occupation_id = ?",
                    OCCUPATION_MAPPER, occupationId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    /**
     * 查询全部指定状态集合的常规占用（按 occupationId 字典序，结果稳定），
     * 时空相交过滤由调用方在内存中完成（需要航点快照做几何判定）。
     */
    public List<OccupationPo> findRegularOccupationsByStatus(List<String> statuses) {
        String placeholders = String.join(", ", statuses.stream().map(s -> "?").toList());
        return jdbc.query(
                "SELECT " + OCCUPATION_COLUMNS + " FROM occupation "
                        + "WHERE kind = 'REGULAR' AND status IN (" + placeholders + ") "
                        + "ORDER BY occupation_id",
                OCCUPATION_MAPPER, statuses.toArray());
    }

    /** 创建占用（调用方负责事务与全部前置校验）。 */
    public void insertOccupation(OccupationPo po) {
        jdbc.update("INSERT INTO occupation "
                        + "(occupation_id, route_id, route_version, kind, start_utc, end_utc, "
                        + "alt_min, alt_max, yieldable, status, review_id, airspace_version, "
                        + "points_snapshot, superseded_by, created_at, departed_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                po.occupationId(), po.routeId(), po.routeVersion(), po.kind(),
                po.startUtc(), po.endUtc(), po.altMin(), po.altMax(), po.yieldable(),
                po.status(), po.reviewId(), po.airspaceVersion(),
                ReviewRepository.encodePoints(po.pointsSnapshot()),
                po.supersededBy(), po.createdAt(), po.departedAt());
    }

    /** 将占用标记为 YIELDED（仅 ACTIVE → YIELDED，调用方负责事务）。 */
    public void markYielded(String occupationId) {
        jdbc.update("UPDATE occupation SET status = 'YIELDED' "
                + "WHERE occupation_id = ? AND status = 'ACTIVE'", occupationId);
    }

    /** 将占用标记为 DEPARTED 并记录起飞时间（仅 ACTIVE → DEPARTED，调用方负责事务）。 */
    public void markDeparted(String occupationId, long departedAt) {
        jdbc.update("UPDATE occupation SET status = 'DEPARTED', departed_at = ? "
                + "WHERE occupation_id = ? AND status = 'ACTIVE'", departedAt, occupationId);
    }

    /** 记录原占用被新占用接替（调用方负责事务）。 */
    public void markSuperseded(String occupationId, String newOccupationId) {
        jdbc.update("UPDATE occupation SET superseded_by = ? WHERE occupation_id = ?",
                newOccupationId, occupationId);
    }
}
