package com.example.starter.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 案件保管人授权表访问。跨案移交要求双方保管人各自持有对应案件的有效授权。
 */
@Repository
public class CaseCustodianGrantRepository {

    private static final GrantRowMapper ROW_MAPPER = new GrantRowMapper();

    private final JdbcTemplate jdbc;

    public CaseCustodianGrantRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条有效授权（case_key + custodian_id 唯一）。
     */
    public void insert(String caseKey, String custodianId, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO case_custodian_grant (case_key, custodian_id, active, created_at)
                        VALUES (?, ?, 1, ?)
                        """,
                caseKey, custodianId, now);
    }

    /**
     * 查询指定保管人对指定案件的有效授权；不存在或已撤销均返回 empty。
     */
    public Optional<CaseCustodianGrant> findActive(String caseKey, String custodianId) {
        List<CaseCustodianGrant> rows = jdbc.query(
                "SELECT * FROM case_custodian_grant WHERE case_key = ? AND custodian_id = ? AND active = 1",
                ROW_MAPPER, caseKey, custodianId);
        return rows.stream().findFirst();
    }

    /**
     * 撤销授权（用于构造失效场景）；无匹配行时不报错。
     */
    public void deactivate(String caseKey, String custodianId) {
        jdbc.update(
                "UPDATE case_custodian_grant SET active = 0 WHERE case_key = ? AND custodian_id = ?",
                caseKey, custodianId);
    }

    private static final class GrantRowMapper implements RowMapper<CaseCustodianGrant> {
        @Override
        public CaseCustodianGrant mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new CaseCustodianGrant(
                    rs.getLong("id"),
                    rs.getString("case_key"),
                    rs.getString("custodian_id"),
                    rs.getInt("active") == 1,
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
