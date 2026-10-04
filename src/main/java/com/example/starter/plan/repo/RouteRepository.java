package com.example.starter.plan.repo;

import com.example.starter.plan.model.RoutePair;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 进路注册与对称冲突对的 JDBC 持久化。冲突对按字典序规范化存储（route_a &lt; route_b）。
 */
@Repository
public class RouteRepository {

    /** 进路注册上限。 */
    public static final int MAX_ROUTES = 20;

    private final JdbcTemplate jdbc;

    public RouteRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按编号查询进路的注册时刻（UTC 毫秒），不存在返回空。
     */
    public Optional<Long> findRouteCreatedAt(String routeId) {
        return jdbc.query("SELECT created_at FROM rail_route WHERE route_id = ?",
                (rs, n) -> rs.getLong(1), routeId).stream().findFirst();
    }

    /**
     * 全部进路编号，按字典序升序。
     */
    public List<String> findAllRouteIds() {
        return jdbc.query("SELECT route_id FROM rail_route ORDER BY route_id",
                (rs, n) -> rs.getString(1));
    }

    /**
     * 已注册进路数量。
     */
    public int countRoutes() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM rail_route", Integer.class);
        return count == null ? 0 : count;
    }

    /**
     * 注册新进路；同键冲突时抛出 DuplicateKeyException 由上层裁决。
     */
    public void insertRoute(String routeId, long nowMillis) {
        jdbc.update("INSERT INTO rail_route (route_id, created_at) VALUES (?, ?)",
                routeId, nowMillis);
    }

    /**
     * 返回给定编号集合中已注册的子集。
     */
    public Set<String> findExistingRouteIds(Collection<String> routeIds) {
        if (routeIds.isEmpty()) {
            return Set.of();
        }
        StringJoiner placeholders = new StringJoiner(", ");
        routeIds.forEach(id -> placeholders.add("?"));
        return new HashSet<>(jdbc.query(
                "SELECT route_id FROM rail_route WHERE route_id IN (" + placeholders + ")",
                (rs, n) -> rs.getString(1), routeIds.toArray()));
    }

    /**
     * 当前冲突矩阵版本（单行，初始为 1）。
     */
    public long matrixVersion() {
        Long version = jdbc.queryForObject(
                "SELECT version FROM rail_route_matrix WHERE id = 1", Long.class);
        if (version == null) {
            throw new IllegalStateException("进路冲突矩阵元信息缺失");
        }
        return version;
    }

    /**
     * 更新冲突矩阵版本。
     */
    public void updateMatrixVersion(long version) {
        jdbc.update("UPDATE rail_route_matrix SET version = ? WHERE id = 1", version);
    }

    /**
     * 全部对称冲突对，按 (route_a, route_b) 升序。
     */
    public List<RoutePair> findAllPairs() {
        return jdbc.query("SELECT route_a, route_b FROM rail_route_conflict"
                        + " ORDER BY route_a, route_b",
                (rs, n) -> new RoutePair(rs.getString(1), rs.getString(2)));
    }


    /**
     * 加入冲突对（须已按字典序规范化）；重复时抛出 DuplicateKeyException 由上层裁决。
     */
    public void insertPair(RoutePair pair, long nowMillis) {
        jdbc.update("INSERT INTO rail_route_conflict (route_a, route_b, created_at)"
                        + " VALUES (?, ?, ?)",
                pair.routeA(), pair.routeB(), nowMillis);
    }

    /**
     * 移除冲突对（须已按字典序规范化），不存在时为无操作。
     */
    public void deletePair(RoutePair pair) {
        jdbc.update("DELETE FROM rail_route_conflict WHERE route_a = ? AND route_b = ?",
                pair.routeA(), pair.routeB());
    }
}