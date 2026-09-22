package com.example.starter.artifact.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 仓库元信息数据访问：维护单行仓库版本号。
 * 所有写事务先对单行加排他锁，保证锁定与撤回看到一致的仓库状态。
 */
@Repository
public class MetaRepository {

    private static final long SINGLE_ROW_ID = 1L;

    private final JdbcTemplate jdbc;

    public MetaRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 确保单行元信息存在（应用启动时调用）。
     */
    public void ensureInitialized() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM repository_meta", Integer.class);
        if (count != null && count == 0) {
            jdbc.update("INSERT INTO repository_meta (id, repo_version) VALUES (?, 0)", SINGLE_ROW_ID);
        }
    }

    /**
     * 以排他锁读取仓库版本号，序列化所有写事务。
     *
     * @return 当前仓库版本号
     */
    public long lockForUpdate() {
        Long version = jdbc.queryForObject(
                "SELECT repo_version FROM repository_meta WHERE id = ? FOR UPDATE",
                Long.class, SINGLE_ROW_ID);
        return version == null ? 0L : version;
    }

    /**
     * 读取当前仓库版本号（须在已持有行锁的事务内使用）。
     *
     * @return 当前仓库版本号
     */
    public long currentVersion() {
        Long version = jdbc.queryForObject(
                "SELECT repo_version FROM repository_meta WHERE id = ?",
                Long.class, SINGLE_ROW_ID);
        return version == null ? 0L : version;
    }

    /**
     * 仓库版本号加一并返回新值（须在已持有行锁的事务内使用）。
     *
     * @return 加一后的仓库版本号
     */
    public long increment() {
        jdbc.update("UPDATE repository_meta SET repo_version = repo_version + 1 WHERE id = ?", SINGLE_ROW_ID);
        return currentVersion();
    }
}
