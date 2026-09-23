package com.example.starter.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 案件操作人权限表访问。组合借出归还要求接收人与复核人都具备对应案件权限。
 * 授权记录只追加；(case_key, user_id) 唯一约束保证重复授权幂等。
 */
@Repository
public class CasePermissionRepository {

    private final JdbcTemplate jdbc;

    public CasePermissionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 登记案件权限；已存在相同授权时不重复插入（幂等）。
     */
    public void grantIfAbsent(String caseKey, String userId, String grantedBy, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO evidence_case_permission (case_key, user_id, granted_by, created_at)
                        SELECT ?, ?, ?, ? FROM DUAL
                        WHERE NOT EXISTS (
                            SELECT 1 FROM evidence_case_permission WHERE case_key = ? AND user_id = ?
                        )
                        """,
                caseKey, userId, grantedBy, now, caseKey, userId);
    }

    /**
     * 判断操作人是否具备指定案件权限。
     */
    public boolean hasPermission(String caseKey, String userId) {
        List<Integer> rows = jdbc.query(
                "SELECT 1 FROM evidence_case_permission WHERE case_key = ? AND user_id = ?",
                (rs, rowNum) -> rs.getInt(1), caseKey, userId);
        return !rows.isEmpty();
    }

    /**
     * 查询案件全部授权操作人（按登记顺序），用于测试核验。
     */
    public List<String> findUsersByCaseKey(String caseKey) {
        return jdbc.query(
                "SELECT user_id FROM evidence_case_permission WHERE case_key = ? ORDER BY id",
                (rs, rowNum) -> rs.getString(1), caseKey);
    }
}
