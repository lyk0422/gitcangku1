package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.TagDictMeta;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 标签字典版本单行元数据。创建发布单解析快照与批量标签修订都通过行锁串行，
 * 保证快照只按一个已提交版本解析，且字典版本号严格递增。
 */
@Repository
public class TagDictMetaRepository {

    private final JdbcTemplate jdbc;

    public TagDictMetaRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 读取当前版本（不加锁，用于只读诊断）。
     */
    public long currentVersion() {
        Long version = jdbc.queryForObject("SELECT version FROM tag_dict_meta WHERE id = 1", Long.class);
        return version == null ? 0L : version;
    }

    /**
     * 行锁读取单行元数据，调用方必须处于事务内。
     */
    public TagDictMeta findForUpdate() {
        return jdbc.queryForObject("SELECT id, version FROM tag_dict_meta WHERE id = 1 FOR UPDATE",
                (rs, rowNum) -> new TagDictMeta(rs.getInt("id"), rs.getLong("version")));
    }

    /**
     * 字典版本加一，返回新的版本号；调用方已持有行锁。
     */
    public long incrementVersion() {
        jdbc.update("UPDATE tag_dict_meta SET version = version + 1 WHERE id = 1");
        return currentVersion();
    }
}
