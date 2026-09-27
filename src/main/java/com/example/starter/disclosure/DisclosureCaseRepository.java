package com.example.starter.disclosure;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

/**
 * 披露包案件串行锁表访问。同一案件的建包、增删修订、交付与撤销事务
 * 必须先通过 {@link #lock} 锁定案件行，保证版本号分配与包裁决按事务提交顺序进行。
 */
@Repository
public class DisclosureCaseRepository {

    private final JdbcTemplate jdbc;

    public DisclosureCaseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 登记案件（首次建包时）并锁定案件行；案件已存在时直接加锁。
     */
    public void lock(String caseKey, LocalDateTime now) {
        try {
            jdbc.update("INSERT INTO disclosure_case (case_key, created_at) VALUES (?, ?)",
                    caseKey, now);
        } catch (DuplicateKeyException ignored) {
            // 案件已登记，直接锁定即可。
        }
        jdbc.queryForObject(
                "SELECT case_key FROM disclosure_case WHERE case_key = ? FOR UPDATE",
                String.class, caseKey);
    }

    /**
     * 案件内当前最大包版本号（调用前必须已锁定案件行）；无包时返回 0。
     */
    public int maxVersion(String caseKey) {
        Integer version = jdbc.queryForObject(
                "SELECT COALESCE(MAX(package_version), 0) FROM disclosure_package WHERE case_key = ?",
                Integer.class, caseKey);
        return version == null ? 0 : version;
    }
}
