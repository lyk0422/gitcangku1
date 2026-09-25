package com.example.starter.firmware.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 冻结紧急例外已登记确认人数据访问。
 */
@Repository
public class FreezeApproverRepository {

    private final JdbcTemplate jdbc;

    public FreezeApproverRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String approverId) {
        jdbc.update("INSERT INTO freeze_approver (approver_id) VALUES (?)", approverId);
    }

    public boolean exists(String approverId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM freeze_approver WHERE approver_id = ?",
                Long.class, approverId);
        return count != null && count > 0;
    }

    public List<String> findAll() {
        return jdbc.query("SELECT approver_id FROM freeze_approver ORDER BY approver_id",
                (rs, rowNum) -> rs.getString("approver_id"));
    }
}
