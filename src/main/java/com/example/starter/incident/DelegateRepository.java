package com.example.starter.incident;

import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 事件互助接收代理人的 JDBC 仓储。(incident_id, delegate) 唯一，重复登记幂等。
 */
@Repository
public class DelegateRepository {

    private final JdbcTemplate jdbc;

    public DelegateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 登记接收代理人；重复登记（同事件同代理人）返回 false，不产生重复行。
     */
    public boolean insertIfAbsent(long incidentId, String delegate, String registeredBy, Instant now) {
        try {
            jdbc.update(con -> {
                var ps = con.prepareStatement(
                        "INSERT INTO incident_receiving_delegates (incident_id, delegate,"
                                + " registered_by, created_at) VALUES (?,?,?,?)",
                        Statement.RETURN_GENERATED_KEYS);
                ps.setLong(1, incidentId);
                ps.setString(2, delegate);
                ps.setString(3, registeredBy);
                ps.setTimestamp(4, Timestamp.from(now));
                return ps;
            });
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /**
     * 判断代理人是否已登记为事件接收代理人。
     */
    public boolean isDelegate(long incidentId, String delegate) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_receiving_delegates WHERE incident_id = ?"
                        + " AND delegate = ?", Integer.class, incidentId, delegate);
        return count != null && count > 0;
    }

    /**
     * 列出事件全部接收代理人，按登记顺序返回。
     */
    public List<String> listByIncident(long incidentId) {
        return jdbc.queryForList(
                "SELECT delegate FROM incident_receiving_delegates WHERE incident_id = ? ORDER BY id",
                String.class, incidentId);
    }
}
