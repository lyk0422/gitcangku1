package com.example.starter.plan.repo;

import com.example.starter.plan.model.RestrictionStatus;
import com.example.starter.plan.model.Section;
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
 * 区段注册表与气象限速令的 JDBC 持久化。所有时刻以 UTC 毫秒存储，时区无关。
 * 限速令按 (restriction_key, version) 唯一，历史版本保留不改写。
 */
@Repository
public class RestrictionRepository {

    private static final RowMapper<Section> SECTION_MAPPER = (rs, n) -> new Section(
            rs.getString("section_id"),
            rs.getString("line_id"),
            rs.getDouble("length_km"),
            rs.getInt("min_interval_minutes"));

    private static final RowMapper<SpeedRestriction> RESTRICTION_MAPPER = (rs, n) -> new SpeedRestriction(
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

    private static final String RESTRICTION_COLUMNS = "id, restriction_key, version, section_id,"
            + " start_utc, end_utc, max_speed_kmh, status, operator, created_at";

    private final JdbcTemplate jdbc;

    public RestrictionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按区段 ID 查询注册区段。
     */
    public Optional<Section> findSection(String sectionId) {
        return jdbc.query("SELECT section_id, line_id, length_km, min_interval_minutes"
                        + " FROM rail_section WHERE section_id = ?",
                SECTION_MAPPER, sectionId).stream().findFirst();
    }

    /**
     * 按区段 ID 集合批量查询注册区段。
     */
    public List<Section> findSections(Collection<String> sectionIds) {
        if (sectionIds.isEmpty()) {
            return List.of();
        }
        StringJoiner placeholders = new StringJoiner(", ");
        sectionIds.forEach(id -> placeholders.add("?"));
        return jdbc.query("SELECT section_id, line_id, length_km, min_interval_minutes"
                        + " FROM rail_section WHERE section_id IN (" + placeholders + ")",
                SECTION_MAPPER, sectionIds.toArray());
    }

    /**
     * 插入限速令新版本，返回自增主键。
     */
    public long insertRestriction(SpeedRestriction r) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rail_speed_restriction (restriction_key, version, section_id,"
                            + " start_utc, end_utc, max_speed_kmh, status, operator, created_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
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
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按业务键查询最新版本（版本号最大者）。
     */
    public Optional<SpeedRestriction> findLatestByKey(String restrictionKey) {
        return jdbc.query("SELECT " + RESTRICTION_COLUMNS + " FROM rail_speed_restriction"
                        + " WHERE restriction_key = ? ORDER BY version DESC LIMIT 1",
                RESTRICTION_MAPPER, restrictionKey).stream().findFirst();
    }

    /**
     * 按业务键查询全部版本，按版本号升序（历史证据，排序稳定）。
     */
    public List<SpeedRestriction> findVersionsByKey(String restrictionKey) {
        return jdbc.query("SELECT " + RESTRICTION_COLUMNS + " FROM rail_speed_restriction"
                        + " WHERE restriction_key = ? ORDER BY version",
                RESTRICTION_MAPPER, restrictionKey);
    }

    /**
     * 查询指定区段集合上当前生效（ACTIVE）的限速令，按业务键与版本升序。
     */
    public List<SpeedRestriction> findActiveBySections(Collection<String> sectionIds) {
        if (sectionIds.isEmpty()) {
            return List.of();
        }
        StringJoiner placeholders = new StringJoiner(", ");
        sectionIds.forEach(id -> placeholders.add("?"));
        return jdbc.query("SELECT " + RESTRICTION_COLUMNS + " FROM rail_speed_restriction"
                        + " WHERE status = 'ACTIVE' AND section_id IN (" + placeholders + ")"
                        + " ORDER BY restriction_key, version",
                RESTRICTION_MAPPER, sectionIds.toArray());
    }

    /**
     * 查询指定区段上的全部限速令版本（含历史），按业务键与版本升序。
     */
    public List<SpeedRestriction> findAllBySection(String sectionId) {
        return jdbc.query("SELECT " + RESTRICTION_COLUMNS + " FROM rail_speed_restriction"
                        + " WHERE section_id = ? ORDER BY restriction_key, version",
                RESTRICTION_MAPPER, sectionId);
    }

    /**
     * 更新指定版本的状态（修订置旧版本 SUPERSEDED、撤销置 REVOKED）。
     */
    public void updateStatus(String restrictionKey, int version, RestrictionStatus status) {
        jdbc.update("UPDATE rail_speed_restriction SET status = ?"
                        + " WHERE restriction_key = ? AND version = ?",
                status.name(), restrictionKey, version);
    }
}
