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
 * 剂量账本表访问。账本随证物行锁（SELECT ... FOR UPDATE）串行读写：
 * 上限首次登记后不可变；累计只增不减，保管权交接不清账。
 */
@Repository
public class DoseLedgerRepository {

    private static final DoseLedgerRowMapper ROW_MAPPER = new DoseLedgerRowMapper();

    private final JdbcTemplate jdbc;

    public DoseLedgerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 首次登记剂量上限：初始累计为 0，版本为 1。evidence_key 唯一约束拒绝重复登记。
     */
    public void insert(String evidenceKey, BigDecimal limitLuxMinutes, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO dose_ledger
                            (evidence_key, limit_lux_minutes, cum_milli_lux_nanos, version, created_at, updated_at)
                        VALUES (?, ?, 0, 1, ?, ?)
                        """,
                evidenceKey, limitLuxMinutes, now, now);
    }

    /**
     * 按证物业务键查询账本（调用前必须已锁定证物行或处于只读事务）。
     */
    public Optional<DoseLedger> findByEvidenceKey(String evidenceKey) {
        List<DoseLedger> rows = jdbc.query(
                "SELECT * FROM dose_ledger WHERE evidence_key = ?", ROW_MAPPER, evidenceKey);
        return rows.stream().findFirst();
    }

    /**
     * 累加剂量并递增版本：仅在持有证物行锁的事务内调用，累计只增不减。
     */
    public void addDose(String evidenceKey, BigDecimal newCumMilliLuxNanos, LocalDateTime now) {
        jdbc.update("""
                        UPDATE dose_ledger
                        SET cum_milli_lux_nanos = ?, version = version + 1, updated_at = ?
                        WHERE evidence_key = ?
                        """,
                newCumMilliLuxNanos, now, evidenceKey);
    }

    private static final class DoseLedgerRowMapper implements RowMapper<DoseLedger> {
        @Override
        public DoseLedger mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new DoseLedger(
                    rs.getLong("id"),
                    rs.getString("evidence_key"),
                    rs.getBigDecimal("limit_lux_minutes"),
                    rs.getBigDecimal("cum_milli_lux_nanos"),
                    rs.getLong("version"),
                    rs.getObject("created_at", LocalDateTime.class),
                    rs.getObject("updated_at", LocalDateTime.class));
        }
    }
}
