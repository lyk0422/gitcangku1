package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.TagChangeRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 设备标签批量变更历史数据访问。记录只增不改，按 id 升序（即提交顺序）稳定输出。
 */
@Repository
public class TagChangeRepository {

    private static final RowMapper<TagChangeRecord> MAPPER = (rs, rowNum) -> new TagChangeRecord(
            rs.getLong("id"), rs.getLong("dict_version"), rs.getString("operator"),
            rs.getInt("device_count"), rs.getString("changes"), rs.getString("changed_at_utc"));

    private final JdbcTemplate jdbc;

    public TagChangeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long dictVersion, String operator, int deviceCount, String changesJson,
                       String changedAtUtc) {
        jdbc.update("INSERT INTO tag_change_record"
                        + " (dict_version, operator, device_count, changes, changed_at_utc)"
                        + " VALUES (?, ?, ?, ?, ?)",
                dictVersion, operator, deviceCount, changesJson, changedAtUtc);
    }

    /**
     * 全部历史，按 id 升序（提交顺序）。区间过滤由服务层按 Instant 语义完成，避免字符串精度问题。
     */
    public List<TagChangeRecord> findAll() {
        return jdbc.query("SELECT id, dict_version, operator, device_count, changes, changed_at_utc"
                + " FROM tag_change_record ORDER BY id", MAPPER);
    }
}
