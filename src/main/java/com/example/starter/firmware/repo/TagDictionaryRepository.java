package com.example.starter.firmware.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 标签字典版本（单行）数据访问。行锁（SELECT ... FOR UPDATE 或 UPDATE）用于
 * 串行化设备登记、标签批量变更与发布单目标快照解析，按提交顺序裁决。
 */
@Repository
public class TagDictionaryRepository {

    private static final int SINGLETON_ID = 1;

    private final JdbcTemplate jdbc;

    public TagDictionaryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 加行锁读取当前版本；调用方处于事务内，锁持有至提交。
     */
    public long currentVersionForUpdate() {
        Long version = jdbc.queryForObject(
                "SELECT version FROM tag_dictionary WHERE id = ? FOR UPDATE",
                Long.class, SINGLETON_ID);
        if (version == null) {
            throw new IllegalStateException("标签字典行缺失");
        }
        return version;
    }

    /**
     * 版本加一并记录变更时刻（UTC ISO-8601），返回变更后版本；UPDATE 即取行锁。
     */
    public long bump(String changedAtUtc) {
        jdbc.update("UPDATE tag_dictionary SET version = version + 1, updated_at_utc = ? WHERE id = ?",
                changedAtUtc, SINGLETON_ID);
        return currentVersionForUpdate();
    }
}
