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
 * 光照剂量段表访问。记录只追加、不可变；(loan_key, segment_no) 唯一，
 * 重放归还在服务层幂等短路，不会重复写入。
 */
@Repository
public class DoseSegmentRepository {

    private static final DoseSegmentRowMapper ROW_MAPPER = new DoseSegmentRowMapper();

    private final JdbcTemplate jdbc;

    public DoseSegmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一段冻结的剂量段。
     */
    public void insert(String evidenceKey, String loanKey, int segmentNo,
                       LocalDateTime startAt, LocalDateTime endAt,
                       BigDecimal illuminanceLux, BigDecimal doseLuxMin, LocalDateTime createdAt) {
        jdbc.update("""
                        INSERT INTO dose_segment
                            (evidence_key, loan_key, segment_no, start_at, end_at,
                             illuminance_lux, dose_lux_min, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                evidenceKey, loanKey, segmentNo, startAt, endAt,
                illuminanceLux, doseLuxMin, createdAt);
    }

    /**
     * 按证物查询全部剂量段（按借出与段序号顺序），用于剂量明细。
     */
    public List<DoseSegment> findByEvidenceKey(String evidenceKey) {
        return jdbc.query(
                "SELECT * FROM dose_segment WHERE evidence_key = ? ORDER BY loan_key, segment_no",
                ROW_MAPPER, evidenceKey);
    }

    private static final class DoseSegmentRowMapper implements RowMapper<DoseSegment> {
        @Override
        public DoseSegment mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new DoseSegment(
                    rs.getLong("id"),
                    rs.getString("evidence_key"),
                    rs.getString("loan_key"),
                    rs.getInt("segment_no"),
                    rs.getObject("start_at", LocalDateTime.class),
                    rs.getObject("end_at", LocalDateTime.class),
                    rs.getBigDecimal("illuminance_lux"),
                    rs.getBigDecimal("dose_lux_min"),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
