package com.example.starter.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 案件保管人权限表访问。授权可重复（已撤销则重新激活）；撤销仅停用，不删除历史。
 */
@Repository
public class CaseCustodianRepository {

    private final JdbcTemplate jdbc;

    public CaseCustodianRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 授权保管人：不存在则插入有效记录，已存在则置为有效。
     */
    public void grant(String caseKey, String custodianId, LocalDateTime now) {
        int updated = jdbc.update(
                "UPDATE case_custodian SET active = 1 WHERE case_key = ? AND custodian_id = ?",
                caseKey, custodianId);
        if (updated == 0) {
            jdbc.update("""
                            INSERT INTO case_custodian (case_key, custodian_id, active, created_at)
                            VALUES (?, ?, 1, ?)
                            """,
                    caseKey, custodianId, now);
        }
    }

    /**
     * 撤销保管人权限（停用，不删除）。
     *
     * @return 是否存在并被停用的记录
     */
    public boolean revoke(String caseKey, String custodianId) {
        int updated = jdbc.update(
                "UPDATE case_custodian SET active = 0 WHERE case_key = ? AND custodian_id = ? AND active = 1",
                caseKey, custodianId);
        return updated == 1;
    }

    /**
     * 判断保管人是否具备指定案件的有效权限。
     */
    public boolean isActive(String caseKey, String custodianId) {
        List<Integer> rows = jdbc.query(
                "SELECT 1 FROM case_custodian WHERE case_key = ? AND custodian_id = ? AND active = 1",
                (rs, rowNum) -> rs.getInt(1), caseKey, custodianId);
        return !rows.isEmpty();
    }
}
