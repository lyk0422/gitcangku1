package com.example.starter.repository;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 仪器注册表仓储：并发事务内对仪器行加行锁，串行化同一仪器的写操作。
 */
@Repository
public class InstrumentRepository {

    private final JdbcTemplate jdbcTemplate;

    public InstrumentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 确保仪器存在并对其行加行锁（SELECT ... FOR UPDATE）。
     * 同一仪器的批准/撤销/接替事务因此按提交顺序串行执行。
     * 必须在业务事务内调用，行锁在事务提交/回滚时释放。
     */
    public void lockAndEnsure(String instrumentId, Instant now) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO instrument (instrument_id, created_at) VALUES (?, ?)",
                    instrumentId, Timestamp.valueOf(LocalDateTime.ofInstant(now, ZoneOffset.UTC)));
        } catch (DuplicateKeyException alreadyRegistered) {
            // 仪器已登记，走加锁路径
        }
        jdbcTemplate.queryForObject(
                "SELECT instrument_id FROM instrument WHERE instrument_id = ? FOR UPDATE",
                String.class, instrumentId);
    }
}
