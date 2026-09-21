package com.example.starter.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 封条核验记录表访问。记录只追加、不可变，不提供任何更新语句。
 */
@Repository
public class SealInspectionRepository {

    private static final InspectionRowMapper ROW_MAPPER = new InspectionRowMapper();

    private final JdbcTemplate jdbc;

    public SealInspectionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条核验记录。
     */
    public void insert(String evidenceKey, String inspectorId, boolean passed, String note,
                       LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO seal_inspection
                            (evidence_key, inspector_id, passed, note, created_at)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                evidenceKey, inspectorId, passed ? 1 : 0, note, now);
    }

    /**
     * 按发生顺序查询证物全部核验记录。
     */
    public List<SealInspection> findByEvidenceKey(String evidenceKey) {
        return jdbc.query(
                "SELECT * FROM seal_inspection WHERE evidence_key = ? ORDER BY id",
                ROW_MAPPER, evidenceKey);
    }

    private static final class InspectionRowMapper implements RowMapper<SealInspection> {
        @Override
        public SealInspection mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new SealInspection(
                    rs.getLong("id"),
                    rs.getString("evidence_key"),
                    rs.getString("inspector_id"),
                    rs.getInt("passed") == 1,
                    rs.getString("note"),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
