package com.example.starter.evidence;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 案件保管人权限表访问。入库与交接接受时自动授权；(case_key, custodian_id) 唯一。
 */
@Repository
public class CaseCustodianRepository {

    private final JdbcTemplate jdbc;

    public CaseCustodianRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 授权保管人访问指定案件；已授权时静默忽略（唯一键冲突视为已授权）。
     */
    public void grant(String caseKey, String custodianId, LocalDateTime nowUtc) {
        try {
            jdbc.update(
                    "INSERT INTO case_custodian (case_key, custodian_id, granted_at) VALUES (?, ?, ?)",
                    caseKey, custodianId, nowUtc);
        } catch (DuplicateKeyException ignored) {
            // 已授权：重复授权不改变既有记录
        }
    }

    /**
     * 判断保管人是否具备指定案件权限。
     */
    public boolean isAuthorized(String caseKey, String custodianId) {
        List<Integer> rows = jdbc.query(
                "SELECT 1 FROM case_custodian WHERE case_key = ? AND custodian_id = ?",
                (rs, rowNum) -> 1, caseKey, custodianId);
        return !rows.isEmpty();
    }
}
