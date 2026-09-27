package com.example.starter.firmware.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 标签字典数据访问：单行版本计数器（label_dict_meta id=1）与已知标签集合。
 * 版本推进必须在持有 meta 行锁的事务内完成，与批量标签修改、设备登记串行化。
 */
@Repository
public class LabelDictRepository {

    private final JdbcTemplate jdbc;

    public LabelDictRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 当前字典版本（无锁只读）。
     */
    public long currentVersion() {
        Long value = jdbc.queryForObject("SELECT version FROM label_dict_meta WHERE id = 1", Long.class);
        return value == null ? 0 : value;
    }

    /**
     * 持有 meta 行锁读取当前版本；调用方必须在事务内。
     */
    public long currentVersionForUpdate() {
        Long value = jdbc.queryForObject("SELECT version FROM label_dict_meta WHERE id = 1 FOR UPDATE", Long.class);
        return value == null ? 0 : value;
    }

    public List<String> findAllTags() {
        return jdbc.queryForList("SELECT tag FROM label_dict_tag ORDER BY tag", String.class);
    }

    /**
     * 写入字典中尚不存在的标签，返回实际新增数量。
     */
    public int insertMissingTags(List<String> normalizedTags) {
        int inserted = 0;
        for (String tag : normalizedTags) {
            int rows = jdbc.update("INSERT INTO label_dict_tag (tag) SELECT ? WHERE NOT EXISTS ("
                    + "SELECT 1 FROM label_dict_tag WHERE tag = ?)", tag, tag);
            inserted += rows;
        }
        return inserted;
    }

    public void bumpVersion() {
        jdbc.update("UPDATE label_dict_meta SET version = version + 1 WHERE id = 1");
    }
}
