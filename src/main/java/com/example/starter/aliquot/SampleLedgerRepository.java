package com.example.starter.aliquot;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 母样台账表访问。登记只允许一次；总量与单位登记后不可修改，不提供更新语句。
 */
@Repository
public class SampleLedgerRepository {

    private static final LedgerRowMapper ROW_MAPPER = new LedgerRowMapper();

    private final JdbcTemplate jdbc;

    public SampleLedgerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 登记母样总量与单位；sample_key 冲突由唯一约束拒绝。
     */
    public void insert(String sampleKey, long totalQuantity, String unit, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO sample_ledger (sample_key, total_quantity, unit, created_at)
                        VALUES (?, ?, ?, ?)
                        """,
                sampleKey, totalQuantity, unit, now);
    }

    /**
     * 按母样键查询（不加锁）。
     */
    public Optional<SampleLedger> findByKey(String sampleKey) {
        List<SampleLedger> rows = jdbc.query(
                "SELECT * FROM sample_ledger WHERE sample_key = ?", ROW_MAPPER, sampleKey);
        return rows.stream().findFirst();
    }

    /**
     * 按母样键查询并锁定台账行（SELECT ... FOR UPDATE），预留与耗用前必须持锁。
     */
    public Optional<SampleLedger> findByKeyForUpdate(String sampleKey) {
        List<SampleLedger> rows = jdbc.query(
                "SELECT * FROM sample_ledger WHERE sample_key = ? FOR UPDATE", ROW_MAPPER, sampleKey);
        return rows.stream().findFirst();
    }

    private static final class LedgerRowMapper implements RowMapper<SampleLedger> {
        @Override
        public SampleLedger mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new SampleLedger(
                    rs.getLong("id"),
                    rs.getString("sample_key"),
                    rs.getLong("total_quantity"),
                    rs.getString("unit"),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
