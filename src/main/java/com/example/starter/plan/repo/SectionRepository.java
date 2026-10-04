package com.example.starter.plan.repo;

import com.example.starter.plan.model.Section;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 区段目录持久化。区段登记后不可变，仅用于接续站点连续性校验。
 */
@Repository
public class SectionRepository {

    private static final RowMapper<Section> MAPPER = (rs, n) -> new Section(
            rs.getString("section_id"),
            rs.getString("from_station"),
            rs.getString("to_station"),
            rs.getLong("created_at"));

    private final JdbcTemplate jdbc;

    public SectionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 登记区段；section_id 主键冲突时抛出 DuplicateKeyException 由上层裁决。
     */
    public void insert(String sectionId, String fromStation, String toStation, long nowMillis) {
        jdbc.update("INSERT INTO rail_section (section_id, from_station, to_station, created_at)"
                        + " VALUES (?, ?, ?, ?)",
                sectionId, fromStation, toStation, nowMillis);
    }

    /**
     * 按区段 ID 查询。
     */
    public Optional<Section> find(String sectionId) {
        return jdbc.query("SELECT section_id, from_station, to_station, created_at"
                        + " FROM rail_section WHERE section_id = ?",
                MAPPER, sectionId).stream().findFirst();
    }
}