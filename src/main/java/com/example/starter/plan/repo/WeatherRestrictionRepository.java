package com.example.starter.plan.repo;

import com.example.starter.plan.model.RestrictionStatus;
import com.example.starter.plan.model.WeatherRestriction;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 气象限速令的 JDBC 持久化。所有时刻以 UTC 毫秒存储，时区无关。
 * 限速令行一旦写入不改写：撤销只翻转状态，修订追加新版本行。
 */
@Repository
public class WeatherRestrictionRepository {

    private static final String COLUMNS = "id, restriction_key, version, section_id, start_utc, end_utc,"
            + " max_speed_kmh, status, operator, created_at";

    private static final RowMapper<WeatherRestriction> MAPPER = (rs, n) -> new WeatherRestriction(
            rs.getLong("id"),
            rs.getString("restriction_key"),
            rs.getInt("version"),
            rs.getString("section_id"),
            Instant.ofEpochMilli(rs.getLong("start_utc")),
            Instant.ofEpochMilli(rs.getLong("end_utc")),
            rs.getInt("max_speed_kmh"),
            RestrictionStatus.valueOf(rs.getString("status")),
            rs.getString("operator"),
            rs.getLong("created_at"));

    private final JdbcTemplate jdbc;

    public WeatherRestrictionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入新版本限速令（状态 ACTIVE），返回自增主键。
     */
    public long insert(String restrictionKey, int version, String sectionId,
                       Instant startUtc, Instant endUtc, int maxSpeedKmh,
                       String operator, long nowMillis) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rail_weather_restriction (restriction_key, version, section_id,"
                            + " start_utc, end_utc, max_speed_kmh, status, operator, created_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, restrictionKey);
            ps.setInt(2, version);
            ps.setString(3, sectionId);
            ps.setLong(4, startUtc.toEpochMilli());
            ps.setLong(5, endUtc.toEpochMilli());
            ps.setInt(6, maxSpeedKmh);
            ps.setString(7, operator);
            ps.setLong(8, nowMillis);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按主键查询限速令（不加锁）。
     */
    public Optional<WeatherRestriction> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM rail_weather_restriction WHERE id = ?",
                MAPPER, id).stream().findFirst();
    }

    /**
     * 查询指定业务键当前生效（ACTIVE）的版本行，并加行级写锁；须在事务内调用。
     */
    public Optional<WeatherRestriction> findActiveByKeyForUpdate(String restrictionKey) {
        return jdbc.query("SELECT " + COLUMNS + " FROM rail_weather_restriction"
                        + " WHERE restriction_key = ? AND status = 'ACTIVE' FOR UPDATE",
                MAPPER, restrictionKey).stream().findFirst();
    }

    /**
     * 查询指定业务键的最高版本号；不存在时返回空。
     */
    public Optional<Integer> findMaxVersion(String restrictionKey) {
        return jdbc.query("SELECT MAX(version) AS max_version FROM rail_weather_restriction"
                        + " WHERE restriction_key = ?",
                (rs, n) -> rs.getInt("max_version"), restrictionKey).stream()
                .filter(v -> v > 0).findFirst();
    }

    /**
     * 将指定版本行置为 REVOKED（只翻转状态，不改写其他字段）。
     */
    public void revoke(long id) {
        jdbc.update("UPDATE rail_weather_restriction SET status = 'REVOKED' WHERE id = ?", id);
    }

    /**
     * 查询指定业务键的全部版本，按版本升序（历史查询，读取不改变状态）。
     */
    public List<WeatherRestriction> findHistory(String restrictionKey) {
        return jdbc.query("SELECT " + COLUMNS + " FROM rail_weather_restriction"
                        + " WHERE restriction_key = ? ORDER BY version",
                MAPPER, restrictionKey);
    }

    /**
     * 明细查询：按可选区段与是否仅生效过滤，按业务键、版本升序。
     *
     * @param sectionId  为 null 时不过滤区段
     * @param onlyActive 为 true 时仅返回 ACTIVE 行
     */
    public List<WeatherRestriction> findAll(String sectionId, boolean onlyActive) {
        StringBuilder sql = new StringBuilder(
                "SELECT " + COLUMNS + " FROM rail_weather_restriction WHERE 1 = 1");
        List<Object> args = new java.util.ArrayList<>();
        if (sectionId != null) {
            sql.append(" AND section_id = ?");
            args.add(sectionId);
        }
        if (onlyActive) {
            sql.append(" AND status = 'ACTIVE'");
        }
        sql.append(" ORDER BY restriction_key, version");
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    /**
     * 查询指定区段集合上的全部生效限速令（不限时段），按业务键、版本升序；
     * 用于发布/改签幂等指纹中的限速版本签名。
     */
    public List<WeatherRestriction> findActiveBySections(Collection<String> sectionIds) {
        if (sectionIds.isEmpty()) {
            return List.of();
        }
        StringBuilder placeholders = new StringBuilder();
        List<Object> args = new java.util.ArrayList<>();
        for (String sectionId : sectionIds) {
            if (placeholders.length() > 0) {
                placeholders.append(", ");
            }
            placeholders.append('?');
            args.add(sectionId);
        }
        return jdbc.query("SELECT " + COLUMNS + " FROM rail_weather_restriction"
                        + " WHERE status = 'ACTIVE' AND section_id IN (" + placeholders + ")"
                        + " ORDER BY restriction_key, version",
                MAPPER, args.toArray());
    }

    /**
     * 查询指定区段集合上、与给定时段相交的全部生效限速令（左闭右开相交），
     * 按区段、开始时刻升序，供发布/改签裁决使用。
     */
    public List<WeatherRestriction> findActiveOverlapping(Collection<String> sectionIds,
                                                          Instant windowStart, Instant windowEnd) {
        if (sectionIds.isEmpty()) {
            return List.of();
        }
        StringBuilder placeholders = new StringBuilder();
        List<Object> args = new java.util.ArrayList<>();
        for (String sectionId : sectionIds) {
            if (placeholders.length() > 0) {
                placeholders.append(", ");
            }
            placeholders.append('?');
            args.add(sectionId);
        }
        args.add(windowEnd.toEpochMilli());
        args.add(windowStart.toEpochMilli());
        return jdbc.query("SELECT " + COLUMNS + " FROM rail_weather_restriction"
                        + " WHERE status = 'ACTIVE' AND section_id IN (" + placeholders + ")"
                        + " AND start_utc < ? AND end_utc > ?"
                        + " ORDER BY section_id, start_utc",
                MAPPER, args.toArray());
    }
}
