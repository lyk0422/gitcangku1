package com.example.starter.plan.repo;

import com.example.starter.plan.model.SectionConfig;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 单线区段换向配置持久化。版本从 1 起，更新须乐观校验 expectedVersion。
 */
@Repository
public class SectionConfigRepository {

    private static final RowMapper<SectionConfig> MAPPER = (rs, n) -> new SectionConfig(
            rs.getString("section_id"),
            rs.getInt("clearance_seconds"),
            rs.getInt("version"));

    private final JdbcTemplate jdbc;

    public SectionConfigRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按区段 ID 查询配置（不加锁）。
     */
    public Optional<SectionConfig> find(String sectionId) {
        return jdbc.query("SELECT section_id, clearance_seconds, version FROM rail_section_config"
                        + " WHERE section_id = ?",
                MAPPER, sectionId).stream().findFirst();
    }

    /**
     * 按区段 ID 查询配置并加行级写锁，须在事务内调用，串行化同一区段的配置更新。
     */
    public Optional<SectionConfig> findForUpdate(String sectionId) {
        return jdbc.query("SELECT section_id, clearance_seconds, version FROM rail_section_config"
                        + " WHERE section_id = ? FOR UPDATE",
                MAPPER, sectionId).stream().findFirst();
    }

    /**
     * 按区段 ID 集合批量查询配置，返回以区段 ID 为键的映射。
     */
    public Map<String, SectionConfig> findBySectionIds(Collection<String> sectionIds) {
        if (sectionIds.isEmpty()) {
            return Map.of();
        }
        StringBuilder sql = new StringBuilder(
                "SELECT section_id, clearance_seconds, version FROM rail_section_config"
                        + " WHERE section_id IN (");
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < sectionIds.size(); i++) {
            if (i > 0) {
                placeholders.append(", ");
            }
            placeholders.append('?');
        }
        sql.append(placeholders).append(')');
        return jdbc.query(sql.toString(), MAPPER, sectionIds.toArray()).stream()
                .collect(Collectors.toMap(SectionConfig::sectionId, Function.identity()));
    }

    /**
     * 新建配置，版本从 1 起；区段唯一键冲突时抛出 DuplicateKeyException 由上层裁决。
     */
    public void insert(String sectionId, int clearanceSeconds, long nowMillis) {
        jdbc.update("INSERT INTO rail_section_config"
                        + " (section_id, clearance_seconds, version, created_at, updated_at)"
                        + " VALUES (?, ?, 1, ?, ?)",
                sectionId, clearanceSeconds, nowMillis, nowMillis);
    }

    /**
     * 更新清空间隔并版本加一。
     */
    public void update(String sectionId, int clearanceSeconds, int newVersion, long nowMillis) {
        jdbc.update("UPDATE rail_section_config SET clearance_seconds = ?, version = ?,"
                        + " updated_at = ? WHERE section_id = ?",
                clearanceSeconds, newVersion, nowMillis, sectionId);
    }
}
