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
 * 剂量归还与区间段表访问。记录只追加、冻结不可变；
 * 与借出归还、账本累计在同一事务落库。
 */
@Repository
public class DoseReturnRepository {

    private static final DoseReturnRowMapper RETURN_MAPPER = new DoseReturnRowMapper();
    private static final DoseSegmentRowMapper SEGMENT_MAPPER = new DoseSegmentRowMapper();

    private final JdbcTemplate jdbc;

    public DoseReturnRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一次剂量归还记录，冻结当次与累计剂量；loan_key 唯一约束保证一笔借出至多一条。
     */
    public void insertReturn(String evidenceKey, String loanKey, BigDecimal returnMilliLuxNanos,
                             BigDecimal cumMilliLuxNanos, LocalDateTime returnedAt,
                             LocalDateTime createdAt) {
        jdbc.update("""
                        INSERT INTO dose_return
                            (evidence_key, loan_key, return_milli_lux_nanos, cum_milli_lux_nanos,
                             returned_at, created_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                evidenceKey, loanKey, returnMilliLuxNanos, cumMilliLuxNanos, returnedAt, createdAt);
    }

    /**
     * 追加一段剂量区间，冻结照度与段剂量；(loan_key, seq) 唯一约束保证历史不可覆盖。
     */
    public void insertSegment(String evidenceKey, String loanKey, int seq,
                              LocalDateTime startAt, LocalDateTime endAt, BigDecimal lux,
                              BigDecimal milliLuxNanos, LocalDateTime createdAt) {
        jdbc.update("""
                        INSERT INTO dose_segment
                            (evidence_key, loan_key, seq, start_at, end_at, lux, milli_lux_nanos, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                evidenceKey, loanKey, seq, startAt, endAt, lux, milliLuxNanos, createdAt);
    }

    /**
     * 按证物查询全部剂量归还记录（按发生顺序）。
     */
    public List<DoseReturnRecord> findReturnsByEvidenceKey(String evidenceKey) {
        return jdbc.query(
                "SELECT * FROM dose_return WHERE evidence_key = ? ORDER BY id",
                RETURN_MAPPER, evidenceKey);
    }

    /**
     * 按证物查询全部剂量区间段（按发生顺序），由服务层按借出分组。
     */
    public List<DoseSegmentRecord> findSegmentsByEvidenceKey(String evidenceKey) {
        return jdbc.query(
                "SELECT * FROM dose_segment WHERE evidence_key = ? ORDER BY id",
                SEGMENT_MAPPER, evidenceKey);
    }

    private static final class DoseReturnRowMapper implements RowMapper<DoseReturnRecord> {
        @Override
        public DoseReturnRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new DoseReturnRecord(
                    rs.getLong("id"),
                    rs.getString("evidence_key"),
                    rs.getString("loan_key"),
                    rs.getBigDecimal("return_milli_lux_nanos"),
                    rs.getBigDecimal("cum_milli_lux_nanos"),
                    rs.getObject("returned_at", LocalDateTime.class),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }

    private static final class DoseSegmentRowMapper implements RowMapper<DoseSegmentRecord> {
        @Override
        public DoseSegmentRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new DoseSegmentRecord(
                    rs.getLong("id"),
                    rs.getString("evidence_key"),
                    rs.getString("loan_key"),
                    rs.getInt("seq"),
                    rs.getObject("start_at", LocalDateTime.class),
                    rs.getObject("end_at", LocalDateTime.class),
                    rs.getBigDecimal("lux"),
                    rs.getBigDecimal("milli_lux_nanos"),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
