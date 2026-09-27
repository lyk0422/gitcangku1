package com.example.starter.evidence;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

/**
 * 案件保管人注册表访问。登记只增不删；(case_key, custodian_id) 主键保证重复登记幂等。
 */
@Repository
public class CaseCustodianRepository {

    private final JdbcTemplate jdbc;

    public CaseCustodianRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 登记保管人具备某案件权限；已登记时忽略（含并发重复登记）。
     */
    public void registerIfAbsent(String caseKey, String custodianId, LocalDateTime nowUtc) {
        try {
            jdbc.update(
                    "INSERT INTO case_custodian (case_key, custodian_id, created_at) VALUES (?, ?, ?)",
                    caseKey, custodianId, nowUtc);
        } catch (DuplicateKeyException ignored) {
            // 已登记（含并发事务先行登记）：登记语义幂等，无需处理。
        }
    }

    /**
     * 判断保管人是否具备对应案件权限。
     */
    public boolean exists(String caseKey, String custodianId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM case_custodian WHERE case_key = ? AND custodian_id = ?",
                Integer.class, caseKey, custodianId);
        return count != null && count > 0;
    }

    /**
     * 判断案件是否已有任何登记保管人（用于目标案件首位保管人登记规则）。
     */
    public boolean hasAnyCustodian(String caseKey) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM case_custodian WHERE case_key = ?",
                Integer.class, caseKey);
        return count != null && count > 0;
    }
}
