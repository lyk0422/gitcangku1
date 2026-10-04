package com.example.starter.plan.repo;

import com.example.starter.plan.model.RestrictionStatus;
import com.example.starter.plan.model.SpeedRestriction;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 限速令的 JDBC 持久化。所有时刻以 UTC 毫秒存储，时区无关。
 */
@Repository
public class RestrictionRepository {

    private static final RowMapper<SpeedRestriction> MAPPER = (rs, n) -> new SpeedRestriction(
            rs.getLong("id"),
            rs.getString("restriction_key"),
            rs.getInt("version"),
            rs.getString("section_id"),
            Instant.ofEpochMilli(rs.getLong("start_utc")),
            Instant.ofEpochMilli(rs.getLong("end_utc")),
            rs.getInt("max_speed_kmh"),
            RestrictionStatus.valueOf(rs.getString("status")),
            rs.getString("operator"),
            rs.getLong("created_at"),
            rs.getLong("updated_at"),
            rs.getObject("revoked_at", Long.class));

    private static final String COLUMNS = "id, restriction_key, version, section_id, start_utc,"
            + " end_utc, max_speed_kmh, status, operator, created_at, updated_at, revoked_at";

    private final JdbcTemplate jdbc;

    public RestrictionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 登记新限速令（版本 1，状态 ACTIVE），返回自增主键。
     */
    public long insert(SpeedRestriction r) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rail_speed_restriction (restriction_key, version, section_id,"
                            + " start_utc, end_utc, max_speed_kmh, status, operator,"
                            + " created_at, updated_at, revoked_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, r.restrictionKey());
            ps.setInt(2, r.version());
            ps.setString(3, r.sectionId());
            ps.setLong(4, r.startUtc().toEpochMilli());
            ps.setLong(5, r.endUtc().toEpochMilli());
            ps.setInt(6, r.maxSpeedKmh());
            ps.setString(7, r.status().name());
            ps.setString(8, r.operator());
            ps.setLong(9, r.createdAt());
            ps.setLong(10, r.updatedAt());
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按业务键查询限速令（不加锁）。
     */
    public Optional<SpeedRestriction> findByKey(String restrictionKey) {
        return jdbc.query("SELECT " + COLUMNS + " FROM rail_speed_restriction"
                        + " WHERE restriction_key = ?",
                MAPPER, restrictionKey).stream().findFirst();
    }

    /**
     * 按业务键查询并加行级写锁，须在事务内调用，串行化修订与撤销。
     */
    public Optional<SpeedRestriction> findByKeyForUpdate(String restrictionKey) {
        return jdbc.query("SELECT " + COLUMNS + " FROM rail_speed_restriction"
                        + " WHERE restriction_key = ? FOR UPDATE",
                MAPPER, restrictionKey).stream().findFirst();
    }

    /**
     * 修订限速令参数并递增版本（仅在事务内、状态校验通过后调用）。
     */
    public void updateAmendment(long id, int newVersion, String sectionId,
                                Instant startUtc, Instant endUtc, int maxSpeedKmh,
                                String operator, long nowMillis) {
        jdbc.update("UPDATE rail_speed_restriction SET version = ?, section_id = ?,"
                        + " start_utc = ?, end_utc = ?, max_speed_kmh = ?, operator = ?,"
                        + " updated_at = ? WHERE id = ?",
                newVersion, sectionId, startUtc.toEpochMilli(), endUtc.toEpochMilli(),
                maxSpeedKmh, operator, nowMillis, id);
    }

    /**
     * 标记撤销（状态置 REVOKED 并记录撤销时刻）。
     */
    public void markRevoked(long id, String operator, long nowMillis) {
        jdbc.update("UPDATE rail_speed_restriction SET status = 'REVOKED', operator = ?,"
                        + " updated_at = ?, revoked_at = ? WHERE id = ?",
                operator, nowMillis, nowMillis, id);
    }

    /**
     * 查询全部生效中的限速令，按业务键升序（用于幂等指纹与发布裁决）。
     */
    public List<SpeedRestriction> findActive() {
        return jdbc.query("SELECT " + COLUMNS + " FROM rail_speed_restriction"
                        + " WHERE status = 'ACTIVE' ORDER BY restriction_key",
                MAPPER);
    }

    /**
     * 查询指定区段集合上生效中的限速令，按区段与开始时刻升序。
     */
    public List<SpeedRestriction> findActiveBySections(List<String> sectionIds) {
        if (sectionIds.isEmpty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS
                + " FROM rail_speed_restriction WHERE status = 'ACTIVE' AND section_id IN (");
        sql.append(String.join(", ", sectionIds.stream().map(s -> "?").toList()));
        sql.append(") ORDER BY section_id, start_utc");
        return jdbc.query(sql.toString(), MAPPER, sectionIds.toArray());
    }

    /**
     * 查询指定区段在指定时刻生效的限速令（左闭右开），按速度升序（最低速度在前）。
     */
    public List<SpeedRestriction> findActiveAt(String sectionId, Instant at) {
        return jdbc.query("SELECT " + COLUMNS + " FROM rail_speed_restriction"
                        + " WHERE status = 'ACTIVE' AND section_id = ?"
                        + " AND start_utc <= ? AND end_utc > ?"
                        + " ORDER BY max_speed_kmh, restriction_key",
                MAPPER, sectionId, at.toEpochMilli(), at.toEpochMilli());
    }

    /**
     * 查询限速令历史清单（含已撤销），可按区段过滤，按业务键升序。
     */
    public List<SpeedRestriction> findAll(Optional<String> sectionId) {
        if (sectionId.isPresent()) {
            return jdbc.query("SELECT " + COLUMNS + " FROM rail_speed_restriction"
                            + " WHERE section_id = ? ORDER BY restriction_key",
                    MAPPER, sectionId.get());
        }
        return jdbc.query("SELECT " + COLUMNS + " FROM rail_speed_restriction"
                        + " ORDER BY restriction_key",
                MAPPER);
    }
}