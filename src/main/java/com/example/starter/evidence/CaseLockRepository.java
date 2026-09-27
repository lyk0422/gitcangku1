package com.example.starter.evidence;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

/**
 * 案件级披露包锁表访问。每个案件一行；包版本号生成、交付、撤销前必须先取得该行的
 * SELECT ... FOR UPDATE 行锁，使建包、增删、交付、撤销与同一案件的其他包操作按事务提交顺序裁决。
 */
@Repository
public class CaseLockRepository {

    private final JdbcTemplate jdbc;

    public CaseLockRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 确保案件锁行存在并锁定（事务内调用）。首次使用时插入；并发首插的负方捕获唯一冲突后
     * 重新 SELECT ... FOR UPDATE 复用先提交事务创建的锁行，保证两方在同一行上排队。
     */
    public void lock(String caseKey) {
        Integer locked = jdbc.query(
                "SELECT id FROM disclosure_case_lock WHERE case_key = ? FOR UPDATE",
                rs -> rs.next() ? rs.getInt("id") : null, caseKey);
        if (locked == null) {
            try {
                jdbc.update(
                        "INSERT INTO disclosure_case_lock (case_key, created_at) VALUES (?, ?)",
                        caseKey, LocalDateTime.now());
            } catch (DuplicateKeyException e) {
                // 并发首插：对方已提交锁行，本事务重新取该行行锁后继续裁决。
                jdbc.queryForObject(
                        "SELECT id FROM disclosure_case_lock WHERE case_key = ? FOR UPDATE",
                        Integer.class, caseKey);
            }
        }
    }
}
