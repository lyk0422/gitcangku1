package com.example.starter.incident;

import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 共享资源定义的 JDBC 仓储，并承载资源池全局单行锁。
 * 租约申请/抢占/任务启动/完成/取消在任何其他锁之前先持有 lockPool()，
 * 串行化容量核算与租约状态变更，保证容量永不超限且任务不带失效租约启动。
 */
@Repository
public class SharedResourceRepository {

    /** 资源池全局锁行的固定主键。 */
    private static final long POOL_LOCK_ID = 1L;

    private final JdbcTemplate jdbc;

    public SharedResourceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<SharedResource> MAPPER = (rs, n) -> new SharedResource(
            rs.getLong("id"), rs.getString("resource_key"), rs.getInt("capacity"),
            rs.getString("created_by"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());

    /**
     * 插入资源定义，返回生成主键。resource_key 唯一约束兜底并发重复插入。
     */
    public long insert(SharedResource resource) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO shared_resources (resource_key, capacity, created_by,"
                            + " created_at, updated_at) VALUES (?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, resource.resourceKey());
            ps.setInt(2, resource.capacity());
            ps.setString(3, resource.createdBy());
            ps.setTimestamp(4, Timestamp.from(resource.createdAt()));
            ps.setTimestamp(5, Timestamp.from(resource.updatedAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按业务键查询资源（不加锁）。
     */
    public Optional<SharedResource> findByKey(String resourceKey) {
        List<SharedResource> rows = jdbc.query(
                "SELECT * FROM shared_resources WHERE resource_key = ?", MAPPER, resourceKey);
        return rows.stream().findFirst();
    }

    /**
     * 持有资源池全局锁（单行 SELECT ... FOR UPDATE），串行化容量核算与租约状态变更。
     * 锁行不存在时先插入；并发首次插入由主键约束串行化。
     */
    public void lockPool() {
        try {
            jdbc.update("INSERT INTO resource_pool_lock (id) VALUES (?)", POOL_LOCK_ID);
        } catch (DuplicateKeyException e) {
            // 锁行已存在（含并发事务已提交），继续加锁
        }
        jdbc.queryForObject("SELECT id FROM resource_pool_lock WHERE id = ? FOR UPDATE",
                Long.class, POOL_LOCK_ID);
    }
}
