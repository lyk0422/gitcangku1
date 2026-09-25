package com.example.starter.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 重量差异明细表访问。仅 DISCREPANT 项写入，只追加、不可变。
 */
@Repository
public class WeightDiscrepancyRepository {

    private static final DiscrepancyRowMapper ROW_MAPPER = new DiscrepancyRowMapper();

    private final JdbcTemplate jdbc;

    public WeightDiscrepancyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条差异明细。
     */
    public void insert(String intakeKey, String evidenceKey, BigDecimal declaredWeight,
                       BigDecimal measuredWeight, BigDecimal deviation, BigDecimal deviationRatio,
                       LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO weight_discrepancy
                            (intake_key, evidence_key, declared_weight, measured_weight,
                             deviation, deviation_ratio, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                intakeKey, evidenceKey, declaredWeight, measuredWeight,
                deviation, deviationRatio, now);
    }

    /**
     * 查询批次全部差异明细（按入库顺序）。
     */
    public List<WeightDiscrepancy> findByIntakeKey(String intakeKey) {
        return jdbc.query(
                "SELECT * FROM weight_discrepancy WHERE intake_key = ? ORDER BY id",
                ROW_MAPPER, intakeKey);
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
                    rs.getBigDecimal("deviation"),
                    rs.getBigDecimal("deviation_ratio"),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
