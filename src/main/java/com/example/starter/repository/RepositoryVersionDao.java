package com.example.starter.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 仓库版本单行表访问：所有写事务先对该行加写锁串行化，再读取业务数据。
 */
@Repository
public class RepositoryVersionDao {

    private final JdbcTemplate jdbcTemplate;

    public RepositoryVersionDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 锁定单行并返回当前仓库版本（事务结束前持锁）。
     * 锁定操作也调用本方法：只取锁不修改，保证锁定期间看到的制品状态一致。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public long lockAndGet() {
        Long version = jdbcTemplate.queryForObject(
                "SELECT version FROM repository_version WHERE id = 1 FOR UPDATE",
                Long.class);
        return version == null ? 0L : version;
    }

    /**
     * 锁定单行并自增仓库版本，返回自增后的版本。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public long lockAndIncrement() {
        jdbcTemplate.update(
                "UPDATE repository_version SET version = version + 1 WHERE id = 1");
        return lockAndGet();
    }
}
