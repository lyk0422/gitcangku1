package com.example.starter.incident;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 跨事件互助资源的 JDBC 仓储。写路径均处于锁定来源/目标事件行的事务内；
 * 资源行在交接创建与结算时以 SELECT ... FOR UPDATE 锁定，串行化同资源的并发交接。
 */
@Repository
public class ResourceRepository {

    private final JdbcTemplate jdbc;

    public ResourceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Resource> MAPPER = (rs, n) -> map(rs);

    private static Resource map(ResultSet rs) throws SQLException {
        return new Resource(
                rs.getLong("id"), rs.getString("resource_key"),
                rs.getLong("owner_incident_id"), rs.getString("label"),
                rs.getString("registered_by"),
                ResourceStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    /**
     * 登记资源，初始状态 AVAILABLE，返回生成主键。resource_key 全局唯一。
     */
    public long insert(Resource resource) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO incident_resources (resource_key, owner_incident_id, label,"
                            + " registered_by, status, created_at, updated_at)"
                            + " VALUES (?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, resource.resourceKey());
            ps.setLong(2, resource.ownerIncidentId());
            ps.setString(3, resource.label());
            ps.setString(4, resource.registeredBy());
            ps.setString(5, resource.status().name());
            ps.setTimestamp(6, Timestamp.from(resource.createdAt()));
            ps.setTimestamp(7, Timestamp.from(resource.updatedAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按业务键查询资源（不加锁）。
     */
    public Optional<Resource> findByKey(String resourceKey) {
        List<Resource> rows = jdbc.query(
                "SELECT * FROM incident_resources WHERE resource_key = ?", MAPPER, resourceKey);
        return rows.stream().findFirst();
    }

    /**
     * 按主键查询资源（不加锁）。
     */
    public Optional<Resource> findById(long id) {
        List<Resource> rows = jdbc.query(
                "SELECT * FROM incident_resources WHERE id = ?", MAPPER, id);
        return rows.stream().findFirst();
    }

    /**
     * 按主键查询并锁定资源行（SELECT ... FOR UPDATE）。
     */
    public Optional<Resource> lockById(long id) {
        List<Resource> rows = jdbc.query(
                "SELECT * FROM incident_resources WHERE id = ? FOR UPDATE", MAPPER, id);
        return rows.stream().findFirst();
    }

    /**
     * 按业务键查询并锁定资源行（SELECT ... FOR UPDATE）。
     */
    public Optional<Resource> lockByKey(String resourceKey) {
        List<Resource> rows = jdbc.query(
                "SELECT * FROM incident_resources WHERE resource_key = ? FOR UPDATE",
                MAPPER, resourceKey);
        return rows.stream().findFirst();
    }

    /**
     * 查询来源事件登记的全部资源，按创建顺序返回。
     */
    public List<Resource> listByOwner(long ownerIncidentId) {
        return jdbc.query(
                "SELECT * FROM incident_resources WHERE owner_incident_id = ? ORDER BY id",
                MAPPER, ownerIncidentId);
    }

    /**
     * 更新资源状态（AVAILABLE/LEASED_OUT）。
     */
    public void updateStatus(long id, ResourceStatus status, Instant at) {
        jdbc.update("UPDATE incident_resources SET status = ?, updated_at = ? WHERE id = ?",
                status.name(), Timestamp.from(at), id);
    }
}
