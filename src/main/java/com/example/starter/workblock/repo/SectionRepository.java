package com.example.starter.workblock.repo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.StringJoiner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 铁路区段目录持久化。施工单引用的区段必须存在于此表。
 */
@Repository
public class SectionRepository {

    private final JdbcTemplate jdbc;

    public SectionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 统计给定区段 ID 中已登记的去重数量。
     */
    public int countExisting(Collection<String> sectionIds) {
        if (sectionIds.isEmpty()) {
            return 0;
        }
        StringJoiner placeholders = new StringJoiner(", ");
        sectionIds.forEach(s -> placeholders.add("?"));
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT section_id) FROM rail_section WHERE section_id IN ("
                        + placeholders + ")",
                Integer.class, sectionIds.toArray());
        return count == null ? 0 : count;
    }

    /**
     * 查询全部区段 ID，按字典序。
     */
    public List<String> findAll() {
        return new ArrayList<>(jdbc.queryForList(
                "SELECT section_id FROM rail_section ORDER BY section_id", String.class));
    }
}
