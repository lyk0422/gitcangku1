package com.example.starter.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 案件保管人授权表访问。跨案移交双方保管人必须各有对应案件权限。
 */
@Repository
public class CaseCustodianRepository {

    private static final CaseCustodianRowMapper ROW_MAPPER = new CaseCustodianRowMapper();

    private final JdbcTemplate jdbc;

    public CaseCustodianRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 登记授权（已存在则忽略），用于合成数据与测试夹具准备。
     */
    public void grant(String caseKey, String custodianId, LocalDateTime now) {
        jdbc.update("""
                        MERGE INTO case_custodian (case_key, custodian_id, created_at)
                        KEY (case_key, custodian_id) VALUES (?, ?, ?)
                        """,
                caseKey, custodianId, now);
    }

    /**
     * 判断保管人是否具备该案件权限。
     */
    public boolean hasPermission(String caseKey, String custodianId) {
        List<CaseCustodian> rows = jdbc.query(
                "SELECT * FROM case_custodian WHERE case_key = ? AND custodian_id = ?",
                ROW_MAPPER, caseKey, custodianId);
        return !rows.isEmpty();
    }

    private static final class CaseCustodianRowMapper implements RowMapper<CaseCustodian> {
        @Override
        public CaseCustodian mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new CaseCustodian(
                    rs.getLong("id"),
                    rs.getString("case_key"),
                    rs.getString("custodian_id"),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
