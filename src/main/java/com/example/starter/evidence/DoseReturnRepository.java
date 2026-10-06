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
 * 剂量归还记录表访问。记录只追加、不可变；loan_key 唯一，一笔借出至多一条剂量归还。
 */
@Repository
public class DoseReturnRepository {

    private static final DoseReturnRowMapper ROW_MAPPER = new DoseReturnRowMapper();

    private final JdbcTemplate jdbc;

    public DoseReturnRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条剂量归还记录，冻结当次与归还后累计剂量。
     */
    public void insert(String evidenceKey, String loanKey, BigDecimal doseLuxMinutes,
                       BigDecimal cumulativeLuxMinutes, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO dose_return
                            (evidence_key, loan_key, dose_lux_minutes, cumulative_lux_minutes, created_at)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                evidenceKey, loanKey, doseLuxMinutes, cumulativeLuxMinutes, now);
    }

    /**
     * 按发生顺序查询证物全部剂量归还记录。
     */
    public List<DoseReturnRecord> findByEvidenceKey(String evidenceKey) {
        return jdbc.query(
                "SELECT * FROM dose_return WHERE evidence_key = ? ORDER BY id",
                ROW_MAPPER, evidenceKey);
    }

    private static final class DoseReturnRowMapper implements RowMapper<DoseReturnRecord> {
        @Override
        public DoseReturnRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new DoseReturnRecord(
                    rs.getLong("id"),
                    rs.getString("evidence_key"),
                    rs.getString("loan_key"),
                    rs.getBigDecimal("dose_lux_minutes"),
                    rs.getBigDecimal("cumulative_lux_minutes"),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
