package com.example.starter.plan.repo;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 区段走廊等级登记持久化。等级 1～5，数值越大优先级越高；未登记区段按最低等级 1 参与判定。
 */
@Repository
public class SectionRepository {

    /** 未登记区段参与抢占判定的默认等级（最低）。 */
    public static final int DEFAULT_PRIORITY = 1;

    private final JdbcTemplate jdbc;

    public SectionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 登记或更新区段走廊等级（幂等 upsert）。
     */
    public void upsert(String sectionId, int priority, long nowMillis) {
        jdbc.update("INSERT INTO rail_section_priority (section_id, priority, updated_at)"
                        + " VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE priority = ?, updated_at = ?",
                sectionId, priority, nowMillis, priority, nowMillis);
    }

    /**
     * 查询单个区段登记等级，未登记返回空。
     */
    public Optional<Integer> findPriority(String sectionId) {
        return jdbc.query("SELECT priority FROM rail_section_priority WHERE section_id = ?",
                (rs, n) -> rs.getInt("priority"), sectionId).stream().findFirst();
    }

    /**
     * 批量查询区段登记等级，返回 区段ID → 等级；未登记区段不出现在结果中。
     */
    public Map<String, Integer> findPriorities(Collection<String> sectionIds) {
        if (sectionIds.isEmpty()) {
            return Map.of();
        }
        StringJoiner placeholders = new StringJoiner(", ");
        sectionIds.forEach(s -> placeholders.add("?"));
        Map<String, Integer> result = new HashMap<>();
        jdbc.query("SELECT section_id, priority FROM rail_section_priority WHERE section_id IN ("
                        + placeholders + ")",
                rs -> {
                    result.put(rs.getString("section_id"), rs.getInt("priority"));
                }, sectionIds.toArray());
        return result;
    }
}
