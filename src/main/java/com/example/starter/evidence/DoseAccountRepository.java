package com.example.starter.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 剂量账本表访问。每件证物至多一本（evidence_key 唯一）；
 * 上限登记后不可变，累计剂量只增不减，不提供任何扣减语句。
 */
@Repository
public class DoseAccountRepository {

    private static final DoseAccountRowMapper ROW_MAPPER = new DoseAccountRowMapper();

    private final JdbcTemplate jdbc;

    public DoseAccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 首次登记剂量账本：累计剂量为0，版本为1；evidence_key 冲突由唯一约束拒绝。
     */
    public void insert(String evidenceKey, BigDecimal capLuxMinutes, String registeredBy,
                       LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO dose_account
                            (evidence_key, cap_lux_minutes, cumulative_lux_minutes, version,
                             registered_by, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                evidenceKey, capLuxMinutes, BigDecimal.ZERO, 1, registeredBy, now, now);
    }

    /**
     * 按证物业务键查询账本（不加锁），用于只读场景。
     */
    public Optional<DoseAccount> findByEvidenceKey(String evidenceKey) {
        List<DoseAccount> rows = jdbc.query(
                "SELECT * FROM dose_account WHERE evidence_key = ?", ROW_MAPPER, evidenceKey);
        return rows.stream().findFirst();
    }

    /**
     * 按证物业务键查询并锁定账本行（SELECT ... FOR UPDATE），用于剂量归还累计。
     */
    public Optional<DoseAccount> findByEvidenceKeyForUpdate(String evidenceKey) {
        List<DoseAccount> rows = jdbc.query(
                "SELECT * FROM dose_account WHERE evidence_key = ? FOR UPDATE",
                ROW_MAPPER, evidenceKey);
        return rows.stream().findFirst();
    }

    /**
     * 累加剂量并递增版本；上限不变，累计只增不减。
     */
    public void addDose(String evidenceKey, BigDecimal newCumulative, int newVersion,
                        LocalDateTime now) {
        jdbc.update("""
                        UPDATE dose_account
                        SET cumulative_lux_minutes = ?, version = ?, updated_at = ?
                        WHERE evidence_key = ?
                        """,
                newCumulative, newVersion, now, evidenceKey);
    }

    private static final class DoseAccountRowMapper implements RowMapper<DoseAccount> {
        @Override
        public DoseAccount mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new DoseAccount(
                    rs.getLong("id"),
                    rs.getString("evidence_key"),
                    rs.getBigDecimal("cap_lux_minutes"),
                    rs.getBigDecimal("cumulative_lux_minutes"),
                    rs.getInt("version"),
                    rs.getString("registered_by"),
                    rs.getObject("created_at", LocalDateTime.class),
                    rs.getObject("updated_at", LocalDateTime.class));
        }
    }
}
