package com.example.starter.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 复核记录表访问。记录只追加、不可变，不提供任何更新语句。
 */
@Repository
public class ReviewRecordRepository {

    private static final ReviewRowMapper ROW_MAPPER = new ReviewRowMapper();

    private final JdbcTemplate jdbc;

    public ReviewRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条不可变复核记录。
     */
    public void insert(String intakeKey, String evidenceKey, String reviewerId, String note,
                       LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO review_record
                            (intake_key, evidence_key, reviewer_id, note, created_at)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                intakeKey, evidenceKey, reviewerId, note, now);
    }

    /**
     * 按证物键查询全部复核记录（正常业务中每件至多一条）。
     */
    public List<ReviewRecord> findByEvidenceKey(String evidenceKey) {
        return jdbc.query(
                "SELECT * FROM review_record WHERE evidence_key = ? ORDER BY id",
                ROW_MAPPER, evidenceKey);
    }

    /**
     * 按批次查询全部复核记录（按提交顺序）。
     */
    public List<ReviewRecord> findByIntakeKey(String intakeKey) {
        return jdbc.query(
                "SELECT * FROM review_record WHERE intake_key = ? ORDER BY id",
                ROW_MAPPER, intakeKey);
    }

    private static final class ReviewRowMapper implements RowMapper<ReviewRecord> {
        @Override
        public ReviewRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ReviewRecord(
                    rs.getLong("id"),
                    rs.getString("intake_key"),
                    rs.getString("evidence_key"),
                    rs.getString("reviewer_id"),
                    rs.getString("note"),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
