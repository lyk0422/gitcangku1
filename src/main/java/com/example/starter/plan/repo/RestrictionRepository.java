package com.example.starter.plan.repo;

import com.example.starter.plan.model.RestrictionStatus;
import com.example.starter.plan.model.SpeedRestriction;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 气象限速令的 JDBC 持久化。所有时刻以 UTC 毫秒存储，区间左闭右开。
 */
@Repository
public class RestrictionRepository {

    private static final RowMapper<SpeedRestriction> MAPPER = (rs, n) -> new SpeedRestriction(
            rs.getLong("id"),
            rs.getString("restriction_key"),
            rs.getString("section_id"),
            Instant.ofEpochMilli(rs.getLong("start_utc")),
            Instant.ofEpochMilli(rs.getLong("end_utc")),
            rs.getInt("max_speed_kmh"),
            rs.getInt("version"),
            RestrictionStatus.valueOf(rs.getString("status")),
            rs.getString("operator"),
            rs.getLong("created_at"),
            rs.getLong("updated_at"));

    private static final String COLUMNS = "id, restriction_key, section_id, start_utc, end_utc,"
            + " max_speed_kmh, version, status, operator, created_at, updated_at";

    private final JdbcTemplate jdbc;

    public RestrictionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入新限速令（版本 1，状态 ACTIVE），返回自增主键。
     */
    public long insert(String restrictionKey, String sectionId, Instant startUtc, Instant endUtc,
                       int maxSpeedKmh, String operator, long nowMillis) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rail_speed_restriction (restriction_key, section_id, start_utc,"
                            + " end_utc, max_speed_kmh, version, status, operator, created_at, updated_at)"
                            + " VALUES (?, ?, ?, ?, ?, 1, 'ACTIVE', ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, restrictionKey);
            ps.setString(2, sectionId);
            ps.setLong(3, startUtc.toEpochMilli());
            ps.setLong(4, endUtc.toEpochMilli());
            ps.setInt(5, maxSpeedKmh);
            ps.setString(6, operator);
            ps.setLong(7, nowMillis);
            ps.setLong(8, nowMillis);
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
     * 按业务键查询并加行级写锁，须在事务内调用，用于修订与撤销串行化。
     */
    public Optional<SpeedRestriction> findByKeyForUpdate(String restrictionKey) {
        return jdbc.query("SELECT " + COLUMNS + " FROM rail_speed_restriction"
                        + " WHERE restriction_key = ? FOR UPDATE",
                MAPPER, restrictionKey).stream().findFirst();
    }

    /**
     * 查询指定区段集合上状态为 ACTIVE 的限速令，按区段与开始时刻升序。
     */
    public List<SpeedRestriction> findActiveBySections(Collection<String> sectionIds) {
        if (sectionIds.isEmpty()) {
            return List.of();
        }
        StringJoiner placeholders = new StringJoiner(", ");
        sectionIds.forEach(s -> placeholders.add("?"));
        return jdbc.query("SELECT " + COLUMNS + " FROM rail_speed_restriction"
                        + " WHERE status = 'ACTIVE' AND section_id IN (" + placeholders + ")"
                        + " ORDER BY section_id, start_utc",
                MAPPER, sectionIds.toArray());
    }

    /**
     * 查询全部限速令（含已撤销），按业务键升序；sectionId 非空时按区段过滤。
     */
    public List<SpeedRestriction> findAll(String sectionId) {
        if (sectionId == null || sectionId.isBlank()) {
            return jdbc.query("SELECT " + COLUMNS + " FROM rail_speed_restriction"
                    + " ORDER BY restriction_key", MAPPER);
        }
        return jdbc.query("SELECT " + COLUMNS + " FROM rail_speed_restriction"
                        + " WHERE section_id = ? ORDER BY restriction_key",
                MAPPER, sectionId);
    }

    /**
     * 修订限速令：更新时段与最高速度，版本加一。
     */
    public void revise(long id, int version, Instant startUtc, Instant endUtc,
                       int maxSpeedKmh, String operator, long nowMillis) {
        jdbc.update("UPDATE rail_speed_restriction SET start_utc = ?, end_utc = ?,"
                        + " max_speed_kmh = ?, version = ?, operator = ?, updated_at = ?"
                        + " WHERE id = ?",
                startUtc.toEpochMilli(), endUtc.toEpochMilli(), maxSpeedKmh, version,
                operator, nowMillis, id);
    }

    /**
     * 撤销限速令：仅更新状态，历史保留。
     */
    public void revoke(long id, String operator, long nowMillis) {
        jdbc.update("UPDATE rail_speed_restriction SET status = 'REVOKED', operator = ?,"
                        + " updated_at = ? WHERE id = ?",
                operator, nowMillis, id);
    }
}