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
 * 光照剂量账本表访问。每件证物至多一本（uk_dose_ledger_evidence），
 * 登记与剂量归还均须在持有证物行锁的事务内执行，保证联合裁决按提交顺序生效。
 */
@Repository
public class DoseLedgerRepository {

    private static final DoseLedgerRowMapper ROW_MAPPER = new DoseLedgerRowMapper();

    private final JdbcTemplate jdbc;

    public DoseLedgerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 首次登记账本：版本从 1 开始，累计剂量为 0；同证物重复登记由唯一约束拒绝。
     */
    public void insert(String evidenceKey, BigDecimal limitLuxMin, String registeredBy,
                       LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO dose_ledger
                            (evidence_key, limit_lux_min, cumulative_lux_min, dose_blocked,
                             registered_by, version, created_at, updated_at)
                        VALUES (?, ?, 0, 0, ?, 1, ?, ?)
                        """,
                evidenceKey, limitLuxMin, registeredBy, now, now);
    }

    /**
     * 按证物业务键查询账本（调用方按需决定是否已持有证物行锁）。
     */
    public Optional<DoseLedger> findByEvidenceKey(String evidenceKey) {
        List<DoseLedger> rows = jdbc.query(
                "SELECT * FROM dose_ledger WHERE evidence_key = ?", ROW_MAPPER, evidenceKey);
        return rows.stream().findFirst();
    }

    /**
     * 剂量归还入账：累加剂量、按上限置阻断标记、版本 +1；带期望版本条件更新。
     *
     * @return 是否更新成功（false 表示版本已被并发变动）
     */
    public boolean applyReturn(String evidenceKey, BigDecimal newCumulative, boolean doseBlocked,
                               int expectedVersion, LocalDateTime now) {
        int updated = jdbc.update("""
                        UPDATE dose_ledger
                        SET cumulative_lux_min = ?, dose_blocked = ?, version = version + 1, updated_at = ?
                        WHERE evidence_key = ? AND version = ?
                        """,
                newCumulative, doseBlocked ? 1 : 0, now, evidenceKey, expectedVersion);
        return updated == 1;
    }

    private static final class DoseLedgerRowMapper implements RowMapper<DoseLedger> {
        @Override
        public DoseLedger mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new DoseLedger(
                    rs.getLong("id"),
                    rs.getString("evidence_key"),
                    rs.getBigDecimal("limit_lux_min"),
                    rs.getBigDecimal("cumulative_lux_min"),
                    rs.getInt("dose_blocked") == 1,
                    rs.getString("registered_by"),
                    rs.getInt("version"),
                    rs.getObject("created_at", LocalDateTime.class),
                    rs.getObject("updated_at", LocalDateTime.class));
        }
    }
}
