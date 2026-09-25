package com.example.starter.plan.repo;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.StringJoiner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 走廊区段等级登记持久化。区段等级 1～5，登记后不可变更；未登记区段按 1 级处理。
 */
@Repository
public class SectionRepository {

    private final JdbcTemplate jdbc;

    public SectionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 登记区段等级；section_id 唯一冲突时抛出 DuplicateKeyException 由上层裁决。
     */
    public void insert(String sectionId, int priority, long nowMillis) {
        jdbc.update("INSERT INTO rail_section (section_id, priority, created_at) VALUES (?, ?, ?)",
                sectionId, priority, nowMillis);
    }

    /**
     * 批量查询区段等级，返回 sectionId → 等级；未登记的区段不出现在结果中。
     */
    public Map<String, Integer> findLevels(Collection<String> sectionIds) {
        if (sectionIds.isEmpty()) {
            return Map.of();
        }
        StringJoiner placeholders = new StringJoiner(", ");
        sectionIds.forEach(s -> placeholders.add("?"));
        Map<String, Integer> levels = new HashMap<>();
        jdbc.query("SELECT section_id, priority FROM rail_section WHERE section_id IN ("
                        + placeholders + ")",
                rs -> {
                    levels.put(rs.getString("section_id"), rs.getInt("priority"));
                }, sectionIds.toArray());
        return levels;
    }
}
