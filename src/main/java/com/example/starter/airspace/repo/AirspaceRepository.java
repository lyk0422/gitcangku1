package com.example.starter.airspace.repo;

import com.example.starter.airspace.domain.DomainRows.RequestRecordRow;
import com.example.starter.airspace.domain.DomainRows.ReviewRow;
import com.example.starter.airspace.domain.DomainRows.RouteRow;
import com.example.starter.airspace.domain.DomainRows.RouteSnapshot;
import com.example.starter.airspace.domain.DomainRows.ZoneRow;
import com.example.starter.airspace.geom.Geometry.Point;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 禁飞区/航线/审核/幂等记录的数据访问。所有方法仅在调用方事务边界内执行。
 */
@Repository
public class AirspaceRepository {

    private final JdbcTemplate jdbc;

    public AirspaceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<ZoneRow> ZONE_MAPPER = (rs, n) -> {
        Integer revoked = (Integer) rs.getObject("revoked_version");
        return new ZoneRow(
                rs.getString("zone_id"),
                rs.getInt("x_min"),
                rs.getInt("y_min"),
                rs.getInt("x_max"),
                rs.getInt("y_max"),
                rs.getBoolean("active"),
                rs.getInt("created_version"),
                revoked,
                rs.getTimestamp("created_at").toInstant());
    };

    private static final RowMapper<RouteRow> ROUTE_MAPPER = (rs, n) -> new RouteRow(
            rs.getString("route_id"),
            rs.getInt("version"),
            rs.getTimestamp("updated_at").toInstant());

    private static final RowMapper<ReviewRow> REVIEW_MAPPER = (rs, n) -> {
        String hits = rs.getString("hit_zone_ids");
        List<String> hitIds = hits == null || hits.isEmpty()
                ? List.of()
                : List.of(hits.split(","));
        return new ReviewRow(
                rs.getString("review_id"),
                rs.getString("route_id"),
                rs.getInt("route_version"),
                rs.getInt("airspace_version"),
                rs.getString("conclusion"),
                hitIds,
                rs.getTimestamp("created_at").toInstant());
    };

    private static final RowMapper<RequestRecordRow> REQUEST_MAPPER = (rs, n) ->
            new RequestRecordRow(
                    rs.getString("request_id"),
                    rs.getString("operation"),
                    rs.getString("fingerprint"),
                    rs.getInt("status_code"),
                    rs.getString("response_body"),
                    rs.getTimestamp("created_at").toInstant());

    /** 读取当前全局空域版本（无锁，用于查询类场景）。 */
    public int getGlobalVersion() {
        Integer v = jdbc.queryForObject(
                "SELECT global_version FROM airspace_meta WHERE id = 1", Integer.class);
        return v == null ? 0 : v;
    }

    /**
     * 锁定全局版本单行并读取当前版本。写操作与审核均先调用本方法，
     * 使同一时刻只有一个事务能基于某空域版本做变更/判定。
     */
    public int lockGlobalVersion() {
        Integer v = jdbc.queryForObject(
                "SELECT global_version FROM airspace_meta WHERE id = 1 FOR UPDATE", Integer.class);
        return v == null ? 0 : v;
    }

    /** 全局空域版本加一（调用方已持有该行行锁），返回新版本。 */
    public int incrementGlobalVersion() {
        jdbc.update("UPDATE airspace_meta SET global_version = global_version + 1 WHERE id = 1");
        return getGlobalVersion();
    }

    /** 按 zoneId 查询禁飞区（含已撤销），不存在返回 null。 */
    public ZoneRow findZone(String zoneId) {
        List<ZoneRow> rows = jdbc.query(
                "SELECT zone_id, x_min, y_min, x_max, y_max, active, created_version, "
                        + "revoked_version, created_at FROM no_fly_zone WHERE zone_id = ?",
                ZONE_MAPPER, zoneId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 查询全部当前有效禁飞区（调用方已持空域行锁，保证与提交版本一致）。 */
    public List<ZoneRow> findActiveZones() {
        return jdbc.query(
                "SELECT zone_id, x_min, y_min, x_max, y_max, active, created_version, "
                        + "revoked_version, created_at FROM no_fly_zone WHERE active = TRUE",
                ZONE_MAPPER);
    }

    /** 插入新禁飞区；created_version 为本次变更后的全局版本。 */
    public void insertZone(ZoneRow row) {
        jdbc.update("INSERT INTO no_fly_zone (zone_id, x_min, y_min, x_max, y_max, active, "
                        + "created_version, revoked_version, created_at) VALUES (?,?,?,?,?,TRUE,?,NULL,?)",
                row.zoneId(), row.xMin(), row.yMin(), row.xMax(), row.yMax(),
                row.createdVersion(), Timestamp.from(row.createdAt()));
    }

    /** 将禁飞区标记为已撤销。 */
    public void revokeZone(String zoneId, int revokedVersion, Instant now) {
        jdbc.update("UPDATE no_fly_zone SET active = FALSE, revoked_version = ? "
                + "WHERE zone_id = ?", revokedVersion, zoneId);
    }

    /** 按 routeId 查询航线当前版本行，不存在返回 null。 */
    public RouteRow findRoute(String routeId) {
        List<RouteRow> rows = jdbc.query(
                "SELECT route_id, version, updated_at FROM route WHERE route_id = ?",
                ROUTE_MAPPER, routeId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 锁定航线行并读取当前版本行，不存在返回 null（不锁，由调用方决定是否报错）。 */
    public RouteRow lockRoute(String routeId) {
        List<RouteRow> rows = jdbc.query(
                "SELECT route_id, version, updated_at FROM route WHERE route_id = ? FOR UPDATE",
                ROUTE_MAPPER, routeId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 查询某航线某版本的点列（按 seq 升序）；版本不存在返回空列表。 */
    public List<Point> findRoutePoints(String routeId, int version) {
        return jdbc.query("SELECT x, y FROM route_point WHERE route_id = ? AND version = ? "
                        + "ORDER BY seq ASC",
                (rs, n) -> new Point(rs.getInt("x"), rs.getInt("y")), routeId, version);
    }

    /** 查询航线某版本快照，不存在（点列为空）返回 null。 */
    public RouteSnapshot findRouteSnapshot(String routeId, int version) {
        List<Point> points = findRoutePoints(routeId, version);
        return points.isEmpty() ? null : new RouteSnapshot(routeId, version, points);
    }

    /** 创建航线（版本1）并写入点列。 */
    public void insertRoute(String routeId, List<Point> points, Instant now) {
        jdbc.update("INSERT INTO route (route_id, version, updated_at) VALUES (?, 1, ?)",
                routeId, Timestamp.from(now));
        insertPoints(routeId, 1, points);
    }

    /**
     * 乐观条件替换点列：仅当当前版本=expectedVersion 时把版本加一并写入新点列。
     * 返回是否替换成功（版本不匹配返回 false，不写入任何点列）。
     */
    public boolean replaceRoutePoints(String routeId, int expectedVersion,
                                      List<Point> points, Instant now) {
        int updated = jdbc.update(
                "UPDATE route SET version = version + 1, updated_at = ? "
                        + "WHERE route_id = ? AND version = ?",
                Timestamp.from(now), routeId, expectedVersion);
        if (updated == 0) {
            return false;
        }
        insertPoints(routeId, expectedVersion + 1, points);
        return true;
    }

    private void insertPoints(String routeId, int version, List<Point> points) {
        List<Object[]> batch = new ArrayList<>(points.size());
        for (int i = 0; i < points.size(); i++) {
            Point p = points.get(i);
            batch.add(new Object[]{routeId, version, i, p.x(), p.y()});
        }
        jdbc.batchUpdate(
                "INSERT INTO route_point (route_id, version, seq, x, y) VALUES (?,?,?,?,?)",
                batch);
    }

    /** 写入不可变审核结果。 */
    public void insertReview(ReviewRow row) {
        jdbc.update("INSERT INTO route_review (review_id, route_id, route_version, "
                        + "airspace_version, conclusion, hit_zone_ids, created_at) "
                        + "VALUES (?,?,?,?,?,?,?)",
                row.reviewId(), row.routeId(), row.routeVersion(), row.airspaceVersion(),
                row.conclusion(), String.join(",", row.hitZoneIds()),
                Timestamp.from(row.createdAt()));
    }

    /** 按 reviewId 查询审核结果，不存在返回 null。 */
    public ReviewRow findReview(String reviewId) {
        List<ReviewRow> rows = jdbc.query(
                "SELECT review_id, route_id, route_version, airspace_version, conclusion, "
                        + "hit_zone_ids, created_at FROM route_review WHERE review_id = ?",
                REVIEW_MAPPER, reviewId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 查询某航线全部历史审核结果（按时间、reviewId 升序），保留原结论。 */
    public List<ReviewRow> findReviewsByRoute(String routeId) {
        return jdbc.query(
                "SELECT review_id, route_id, route_version, airspace_version, conclusion, "
                        + "hit_zone_ids, created_at FROM route_review WHERE route_id = ? "
                        + "ORDER BY created_at ASC, review_id ASC",
                REVIEW_MAPPER, routeId);
    }

    /** 按 requestId 查询幂等记录，不存在返回 null。 */
    public RequestRecordRow findRequestRecord(String requestId) {
        List<RequestRecordRow> rows = jdbc.query(
                "SELECT request_id, operation, fingerprint, status_code, response_body, "
                        + "created_at FROM request_record WHERE request_id = ?",
                REQUEST_MAPPER, requestId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 写入幂等记录（PROCESSING 占位）；requestId 主键冲突时抛出异常由调用方处理。 */
    public void insertRequestRecord(RequestRecordRow row) {
        jdbc.update("INSERT INTO request_record (request_id, operation, fingerprint, "
                        + "status_code, response_body, created_at) VALUES (?,?,?,?,?,?)",
                row.requestId(), row.operation(), row.fingerprint(), row.statusCode(),
                row.responseBody(), Timestamp.from(row.createdAt()));
    }

    /** 业务成功后把占位记录补全为原成功响应（同事务提交）。 */
    public void completeRequestRecord(String requestId, int statusCode, String responseBody) {
        jdbc.update("UPDATE request_record SET status_code = ?, response_body = ? "
                + "WHERE request_id = ?", statusCode, responseBody, requestId);
    }
}
