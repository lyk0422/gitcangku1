package com.example.starter.plan.repo;

import com.example.starter.plan.model.ConflictPair;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 进路注册表与冲突矩阵的 JDBC 持久化。冲突对按 (route_a, route_b) 字典序规范化存储。
 */
@Repository
public class RouteRepository {

    /** 全局进路数量上限。 */
    public static final int MAX_ROUTES = 20;

    private final JdbcTemplate jdbc;

    public RouteRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 注册新进路；route_id 唯一约束冲突时抛出 DuplicateKeyException 由上层裁决。
     */
    public void insertRoute(String routeId, long nowMillis) {
        jdbc.update("INSERT INTO rail_route (route_id, created_at) VALUES (?, ?)",
                routeId, nowMillis);
    }

    /**
     * 进路是否已注册。
     */
    public boolean existsRoute(String routeId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_route WHERE route_id = ?", Integer.class, routeId);
        return count != null && count > 0;
    }

    /**
     * 当前已注册进路数量。
     */
    public int countRoutes() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM rail_route", Integer.class);
        return count == null ? 0 : count;
    }

    /**
     * 全部已注册进路编号，按字典序升序。
     */
    public List<String> findAllRoutes() {
        return jdbc.queryForList("SELECT route_id FROM rail_route ORDER BY route_id", String.class);
    }

    /**
     * 查询当前矩阵版本（不加锁），用于只读视图。
     */
    public int matrixVersion() {
        Integer version = jdbc.queryForObject(
                "SELECT version FROM rail_route_matrix WHERE id = 1", Integer.class);
        if (version == null) {
            throw new IllegalStateException("冲突矩阵版本行缺失");
        }
        return version;
    }
    /**
     * 锁定矩阵版本行（FOR UPDATE）并返回当前版本，须在事务内调用。
     */
    public int lockMatrixVersion() {
        Integer version = jdbc.queryForObject(
                "SELECT version FROM rail_route_matrix WHERE id = 1 FOR UPDATE", Integer.class);
        if (version == null) {
            throw new IllegalStateException("冲突矩阵版本行缺失");
        }
        return version;
    }

    /**
     * 更新矩阵版本。
     */
    public void updateMatrixVersion(int version) {
        jdbc.update("UPDATE rail_route_matrix SET version = ? WHERE id = 1", version);
    }

    /**
     * 追加对称冲突对（规范化存储）；唯一约束冲突时抛出 DuplicateKeyException 由上层裁决。
     */
    public void insertConflictPair(ConflictPair pair) {
        jdbc.update("INSERT INTO rail_route_conflict (route_a, route_b) VALUES (?, ?)",
                pair.routeA(), pair.routeB());
    }

    /**
     * 移除对称冲突对，返回实际删除行数。
     */
    public int deleteConflictPair(ConflictPair pair) {
        return jdbc.update("DELETE FROM rail_route_conflict WHERE route_a = ? AND route_b = ?",
                pair.routeA(), pair.routeB());
    }

    /**
     * 全部冲突对，按字典序升序。
     */
    public List<ConflictPair> findAllConflictPairs() {
        return jdbc.query("SELECT route_a, route_b FROM rail_route_conflict ORDER BY route_a, route_b",
                (rs, n) -> new ConflictPair(rs.getString("route_a"), rs.getString("route_b")));
    }
}