package com.example.starter.plan.repo;

import com.example.starter.plan.model.SectionConfig;
import java.sql.PreparedStatement;
import java.sql.Statement;
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
 * 单线区段换向配置持久化。section_id 全局唯一；版本从 1 起，更新加一。
 */
@Repository
public class SectionConfigRepository {

    private static final RowMapper<SectionConfig> MAPPER = (rs, n) -> new SectionConfig(
            rs.getLong("id"),
            rs.getString("section_id"),
            rs.getString("direction_forward"),
            rs.getString("direction_backward"),
            rs.getInt("clearance_seconds"),
            rs.getInt("version"));

    private static final String COLUMNS =
            "id, section_id, direction_forward, direction_backward, clearance_seconds, version";

    private final JdbcTemplate jdbc;

    public SectionConfigRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入新配置（版本 1），返回自增主键；section_id 唯一冲突抛 DuplicateKeyException。
     */
    public long insert(String sectionId, String directionForward, String directionBackward,
                       int clearanceSeconds, long nowMillis) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rail_section_config (section_id, direction_forward,"
                            + " direction_backward, clearance_seconds, version, created_at, updated_at)"
                            + " VALUES (?, ?, ?, ?, 1, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, sectionId);
            ps.setString(2, directionForward);
            ps.setString(3, directionBackward);
            ps.setInt(4, clearanceSeconds);
            ps.setLong(5, nowMillis);
            ps.setLong(6, nowMillis);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按区段查询配置（不加锁）。
     */
    public Optional<SectionConfig> findBySectionId(String sectionId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM rail_section_config WHERE section_id = ?",
                MAPPER, sectionId).stream().findFirst();
    }

    /**
     * 按区段查询配置并加行级写锁，须在事务内调用，串行化同一区段的配置更新。
     */
    public Optional<SectionConfig> findBySectionIdForUpdate(String sectionId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM rail_section_config"
                        + " WHERE section_id = ? FOR UPDATE",
                MAPPER, sectionId).stream().findFirst();
    }

    /**
     * 按区段集合批量查询配置（不加锁），用于发布/改签时一次性加载候选涉及区段的配置。
     */
    public List<SectionConfig> findBySectionIds(Collection<String> sectionIds) {
        if (sectionIds.isEmpty()) {
            return List.of();
        }
        StringJoiner placeholders = new StringJoiner(", ");
        sectionIds.forEach(s -> placeholders.add("?"));
        return jdbc.query("SELECT " + COLUMNS + " FROM rail_section_config WHERE section_id IN ("
                + placeholders + ")", MAPPER, sectionIds.toArray());
    }

    /**
     * 更新配置内容并递增版本。
     */
    public void update(String sectionId, String directionForward, String directionBackward,
                       int clearanceSeconds, int newVersion, long nowMillis) {
        jdbc.update("UPDATE rail_section_config SET direction_forward = ?, direction_backward = ?,"
                        + " clearance_seconds = ?, version = ?, updated_at = ? WHERE section_id = ?",
                directionForward, directionBackward, clearanceSeconds, newVersion, nowMillis,
                sectionId);
    }
}
