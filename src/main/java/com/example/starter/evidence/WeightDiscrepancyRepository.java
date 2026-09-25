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
 * 重量差异明细表访问。记录只追加、不可变，不提供任何更新语句。
 */
@Repository
public class WeightDiscrepancyRepository {

    private static final DiscrepancyRowMapper ROW_MAPPER = new DiscrepancyRowMapper();

    private final JdbcTemplate jdbc;

    public WeightDiscrepancyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条差异明细（每件证物至多一条，由唯一约束保证）。
     */
    public void insert(String intakeKey, String evidenceKey, BigDecimal declaredWeight,
                       BigDecimal measuredWeight, BigDecimal diffPercent, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO weight_discrepancy
                            (intake_key, evidence_key, declared_weight, measured_weight, diff_percent, created_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                intakeKey, evidenceKey, declaredWeight, measuredWeight, diffPercent, now);
    }

    /**
     * 按证物键查询差异明细；无差异（MATCHED 或单件入库）时为空。
     */
    public Optional<WeightDiscrepancy> findByEvidenceKey(String evidenceKey) {
        List<WeightDiscrepancy> rows = jdbc.query(
                "SELECT * FROM weight_discrepancy WHERE evidence_key = ?", ROW_MAPPER, evidenceKey);
        return rows.stream().findFirst();
    }

    private static final class DiscrepancyRowMapper implements RowMapper<WeightDiscrepancy> {
        @Override
        public WeightDiscrepancy mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new WeightDiscrepancy(
                    rs.getLong("id"),
                    rs.getString("intake_key"),
                    rs.getString("evidence_key"),
                    rs.getBigDecimal("declared_weight"),
                    rs.getBigDecimal("measured_weight"),
                    rs.getBigDecimal("diff_percent"),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
