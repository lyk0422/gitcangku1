package com.example.starter.calibration.repo;

import java.time.Instant;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 仪器级互斥锁持久化：不存在锁行则插入，随后对该行 SELECT ... FOR UPDATE（须在事务内调用）。
 * 证书创建、系数版本更新与环境补偿放行共用同一把仪器锁，串行化同仪器的并发写与放行读版本。
 */
@Repository
public class InstrumentLockRepository {

    private final JdbcTemplate jdbc;

    public InstrumentLockRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 获取仪器级互斥锁（须在事务内调用）。
     */
    public void lock(String instrumentId) {
        try {
            jdbc.update("INSERT INTO instrument_lock (instrument_id, created_at) VALUES (?, ?)",
                    instrumentId, JdbcTimes.toDb(Instant.now()));
        } catch (DuplicateKeyException ignored) {
            // 锁行已存在，直接进入行锁等待
        }
        jdbc.queryForObject("SELECT instrument_id FROM instrument_lock WHERE instrument_id = ? FOR UPDATE",
                String.class, instrumentId);
    }
}
