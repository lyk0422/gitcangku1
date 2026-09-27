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

    /**
     * 证物当前最大核验记录 id（跨案移交前水位）；无记录时为 null。
     */
    public Long findMaxIdByEvidenceKey(String evidenceKey) {
        List<Long> ids = jdbc.queryForList(
                "SELECT COALESCE(MAX(id), NULL) FROM seal_inspection WHERE evidence_key = ?",
                Long.class, evidenceKey);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /**
     * 水位之后是否发生过封签复核（撤销时识别目标案件后续复核，含归还时核验）。
     */
    public boolean existsAfterId(String evidenceKey, Long afterId) {
        Long watermark = afterId == null ? 0L : afterId;
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM seal_inspection WHERE evidence_key = ? AND id > ?",
                Integer.class, evidenceKey, watermark);
        return count != null && count > 0;
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
