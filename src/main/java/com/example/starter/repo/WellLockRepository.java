package com.example.starter.repo;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 井级并发控制：同一井的写操作先锁定 well_lock 行以串行化。
 */
@Repository
public class WellLockRepository {

    private final JdbcTemplate jdbc;

    public WellLockRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 确保井的锁行存在（并发插入时忽略主键冲突）。 */
    public void ensureRow(String wellNo) {
        try {
            jdbc.update("INSERT INTO well_lock (well_no) VALUES (?)", wellNo);
        } catch (DuplicateKeyException ignored) {
            // 锁行已存在
        }
    }

    /** 在当前事务内对井的锁行加写锁（SELECT ... FOR UPDATE）。 */
    public void lock(String wellNo) {
        jdbc.queryForObject("SELECT well_no FROM well_lock WHERE well_no = ? FOR UPDATE",
                String.class, wellNo);
    }
}