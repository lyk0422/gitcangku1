package com.example.starter.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 案件操作人授权表访问。组合借出的经办人、分批归还的接收人与复核人都必须具备案件权限。
 */
@Repository
public class CaseGrantRepository {

    private final JdbcTemplate jdbc;

    public CaseGrantRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条案件授权（已存在则跳过，保证初始化合成授权幂等）。
     */
    public void grant(String caseKey, String userId, java.time.LocalDateTime now) {
        if (hasGrant(caseKey, userId)) {
            return;
        }
        jdbc.update("INSERT INTO case_grant (case_key, user_id, created_at) VALUES (?, ?, ?)",
                caseKey, userId, now);
    }

    /**
     * 判断操作人是否具备指定案件权限。
     */
    public boolean hasGrant(String caseKey, String userId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM case_grant WHERE case_key = ? AND user_id = ?",
                Integer.class, caseKey, userId);
        return count != null && count > 0;
    }
}
