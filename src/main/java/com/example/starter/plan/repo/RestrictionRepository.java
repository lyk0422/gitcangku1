package com.example.starter.plan.repo;

import com.example.starter.plan.model.RestrictionStatus;
import com.example.starter.plan.model.SectionProfile;
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
 * 区段档案与气象限速令的 JDBC 持久化。所有时刻以 UTC 毫秒存储，时区无关。
 */
@Repository
public class RestrictionRepository {

    private static final RowMapper<SectionProfile> SECTION_MAPPER = (rs, n) -> new SectionProfile(
            rs.getString("section_id"),
            rs.getInt("design_speed_kmh"),
            rs.getInt("min_headway_minutes"));

    private static final RowMapper<SpeedRestriction> RESTRICTION_MAPPER = (rs, n) -> new SpeedRestriction(
            rs.getLong("id"),
            rs.getString("restriction_key"),
            rs.getString("section_id"),
            Instant.ofEpochMilli(rs.getLong("start_utc")),
            Instant.ofEpochMilli(rs.getLong("end_utc")),
            rs.getInt("max_speed_kmh"),
            rs.getInt("version"),
            RestrictionStatus.valueOf(rs.getString("status")),
            rs.getString("operator_name"),
            rs.getLong("created_at"),
            rs.getLong("updated_at"));

    private static final String RESTRICTION_COLUMNS = "id, restriction_key, section_id, start_utc, end_utc,"
            + " max_speed_kmh, version, status, operator_name, created_at, updated_at";

    private final JdbcTemplate jdbc;

    public RestrictionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 登记区段档案；主键冲突时抛出 DuplicateKeyException 由上层裁决。
     */
    public void insertSection(SectionProfile profile, long nowMillis) {
        jdbc.update("INSERT INTO rail_section_profile (section_id, design_speed_kmh,"
                        + " min_headway_minutes, created_at) VALUES (?, ?, ?, ?)",
                profile.sectionId(), profile.designSpeedKmh(), profile.minHeadwayMinutes(), nowMillis);
    }

    /**
     * 按区段 ID 查询档案（不加锁）。
     */
    public Optional<SectionProfile> findSection(String sectionId) {
        return jdbc.query("SELECT section_id, design_speed_kmh, min_headway_minutes"
                        + " FROM rail_section_profile WHERE section_id = ?",
                SECTION_MAPPER, sectionId).stream().findFirst();
    }

    /**
     * 插入限速令（版本 1，状态 ACTIVE），返回自增主键。
     */
    public long insertRestriction(String restrictionKey, String sectionId, Instant startUtc,
                                  Instant endUtc, int maxSpeedKmh, String operator, long nowMillis) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rail_speed_restriction (restriction_key, section_id, start_utc, end_utc,"
                            + " max_speed_kmh, version, status, operator_name, created_at, updated_at)"
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
        return jdbc.query("SELECT " + RESTRICTION_COLUMNS + " FROM rail_speed_restriction"
                        + " WHERE restriction_key = ?",
                RESTRICTION_MAPPER, restrictionKey).stream().findFirst();
    }

    /**
     * 按业务键查询限速令并加行级写锁，须在事务内调用，串行化撤销与修订。
     */
    public Optional<SpeedRestriction> findByKeyForUpdate(String restrictionKey) {
        return jdbc.query("SELECT " + RESTRICTION_COLUMNS + " FROM rail_speed_restriction"
                        + " WHERE restriction_key = ? FOR UPDATE",
                RESTRICTION_MAPPER, restrictionKey).stream().findFirst();
    }

    /**
     * 修订限速令：更新时段与最高速度，版本加一。
     */
    public void reviseRestriction(long id, Instant startUtc, Instant endUtc, int maxSpeedKmh,
                                  int version, String operator, long nowMillis) {
        jdbc.update("UPDATE rail_speed_restriction SET start_utc = ?, end_utc = ?, max_speed_kmh = ?,"
                        + " version = ?, operator_name = ?, updated_at = ? WHERE id = ?",
                startUtc.toEpochMilli(), endUtc.toEpochMilli(), maxSpeedKmh, version, operator,
                nowMillis, id);
    }

    /**
     * 撤销限速令（状态置为 REVOKED，版本不变）。
     */
    public void revokeRestriction(long id, long nowMillis) {
        jdbc.update("UPDATE rail_speed_restriction SET status = 'REVOKED', updated_at = ? WHERE id = ?",
                nowMillis, id);
    }

    /**
     * 查询指定区段上与给定时段（左闭右开）重叠的生效限速令，按开始时刻与业务键排序。
     */
    public List<SpeedRestriction> findActiveOverlapping(String sectionId, Instant startUtc,
                                                        Instant endUtc) {
        return jdbc.query("SELECT " + RESTRICTION_COLUMNS + " FROM rail_speed_restriction"
                        + " WHERE section_id = ? AND status = 'ACTIVE'"
                        + " AND start_utc < ? AND end_utc > ?"
                        + " ORDER BY start_utc, restriction_key",
                RESTRICTION_MAPPER, sectionId, endUtc.toEpochMilli(), startUtc.toEpochMilli());
    }

    /**
     * 查询指定区段集合上的全部生效限速令（不限时段），用于幂等指纹；按业务键排序保证稳定。
     */
    public List<SpeedRestriction> findActiveBySections(Collection<String> sectionIds) {
        if (sectionIds.isEmpty()) {
            return List.of();
        }
        StringJoiner placeholders = new StringJoiner(", ");
        sectionIds.forEach(s -> placeholders.add("?"));
        return jdbc.query("SELECT " + RESTRICTION_COLUMNS + " FROM rail_speed_restriction"
                        + " WHERE status = 'ACTIVE' AND section_id IN (" + placeholders + ")"
                        + " ORDER BY restriction_key",
                RESTRICTION_MAPPER, sectionIds.toArray());
    }

    /**
     * 查询指定区段的全部限速令（含已撤销），按业务键排序，作为历史视图。
     */
    public List<SpeedRestriction> findBySection(String sectionId) {
        return jdbc.query("SELECT " + RESTRICTION_COLUMNS + " FROM rail_speed_restriction"
                        + " WHERE section_id = ? ORDER BY restriction_key",
                RESTRICTION_MAPPER, sectionId);
    }

    /**
     * 查询全部限速令（含已撤销），按业务键排序。
     */
    public List<SpeedRestriction> findAll() {
        return jdbc.query("SELECT " + RESTRICTION_COLUMNS + " FROM rail_speed_restriction"
                        + " ORDER BY restriction_key", RESTRICTION_MAPPER);
    }

    /**
     * 查询指定区段在某 UTC 时刻生效的限速令（左闭右开），按最高速度升序、业务键排序。
     */
    public List<SpeedRestriction> findActiveAt(String sectionId, Instant at) {
        return jdbc.query("SELECT " + RESTRICTION_COLUMNS + " FROM rail_speed_restriction"
                        + " WHERE section_id = ? AND status = 'ACTIVE'"
                        + " AND start_utc <= ? AND end_utc > ?"
                        + " ORDER BY max_speed_kmh, restriction_key",
                RESTRICTION_MAPPER, sectionId, at.toEpochMilli(), at.toEpochMilli());
    }
}
