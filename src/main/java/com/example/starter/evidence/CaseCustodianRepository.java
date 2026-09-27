package com.example.starter.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 案件保管人权限表访问。记录只追加，不撤销；
 * (case_key, custodian_id) 唯一，重复授权由调用方先行判断。
 */
@Repository
public class CaseCustodianRepository {

    private final JdbcTemplate jdbc;

    public CaseCustodianRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条案件保管人授权。
     */
    public void insert(String caseKey, String custodianId, String grantedBy, LocalDateTime nowUtc) {
        jdbc.update("""
                        INSERT INTO case_custodian (case_key, custodian_id, granted_by, granted_at)
                        VALUES (?, ?, ?, ?)
                        """,
                caseKey, custodianId, grantedBy, nowUtc);
    }

    /**
     * 判断操作人是否具备指定案件的保管权限。
     */
    public boolean exists(String caseKey, String custodianId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM case_custodian WHERE case_key = ? AND custodian_id = ?",
                Integer.class, caseKey, custodianId);
        return count != null && count > 0;
    }

    /**
     * 判断案件是否已有任意保管人（用于新案件首名保管人自助登记）。
     */
    public boolean existsAnyForCase(String caseKey) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM case_custodian WHERE case_key = ?",
                Integer.class, caseKey);
        return count != null && count > 0;
    }

    /**
     * 查询案件全部保管人（按授权顺序）。
     */
    public List<String> findCustodians(String caseKey) {
        return jdbc.query(
                "SELECT custodian_id FROM case_custodian WHERE case_key = ? ORDER BY id",
                (rs, rowNum) -> rs.getString(1), caseKey);
    }
}
