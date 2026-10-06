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
 * 剂量段表访问。记录只追加、不可变，不提供任何更新或删除语句。
 */
@Repository
public class DoseSegmentRepository {

    private static final DoseSegmentRowMapper ROW_MAPPER = new DoseSegmentRowMapper();

    private final JdbcTemplate jdbc;

    public DoseSegmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条剂量段记录。
     */
    public void insert(String evidenceKey, String loanKey, int seq, LocalDateTime startAt,
                       LocalDateTime endAt, BigDecimal lux, BigDecimal doseLuxMinutes,
                       LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO dose_segment
                            (evidence_key, loan_key, seq, start_at, end_at, lux,
                             dose_lux_minutes, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                evidenceKey, loanKey, seq, startAt, endAt, lux, doseLuxMinutes, now);
    }

    /**
     * 按借出业务键查询全部剂量段（按段序号升序）。
     */
    public List<DoseSegment> findByLoanKey(String loanKey) {
        return jdbc.query(
                "SELECT * FROM dose_segment WHERE loan_key = ? ORDER BY seq",
                ROW_MAPPER, loanKey);
    }

    private static final class DoseSegmentRowMapper implements RowMapper<DoseSegment> {
        @Override
        public DoseSegment mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new DoseSegment(
                    rs.getLong("id"),
                    rs.getString("evidence_key"),
                    rs.getString("loan_key"),
                    rs.getInt("seq"),
                    rs.getObject("start_at", LocalDateTime.class),
                    rs.getObject("end_at", LocalDateTime.class),
                    rs.getBigDecimal("lux"),
                    rs.getBigDecimal("dose_lux_minutes"),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
