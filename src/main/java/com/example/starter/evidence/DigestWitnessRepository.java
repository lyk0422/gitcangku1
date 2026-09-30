package com.example.starter.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 数字摘要见证表访问。记录只追加；(evidence_key, version_no, witness_id) 唯一兜底重复见证。
 */
@Repository
public class DigestWitnessRepository {

    private static final WitnessRowMapper ROW_MAPPER = new WitnessRowMapper();

    private final JdbcTemplate jdbc;

    public DigestWitnessRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条见证；同一保管人重复确认同一版本时触发唯一约束冲突。
     */
    public void insert(String evidenceKey, int versionNo, String witnessId, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO digest_witness (evidence_key, version_no, witness_id, created_at)
                        VALUES (?, ?, ?, ?)
                        """,
                evidenceKey, versionNo, witnessId, now);
    }

    /**
     * 查询某证物某版本的全部见证（按提交顺序）。
     */
    public List<DigestWitness> findByVersion(String evidenceKey, int versionNo) {
        return jdbc.query(
                "SELECT * FROM digest_witness WHERE evidence_key = ? AND version_no = ? ORDER BY id",
                ROW_MAPPER, evidenceKey, versionNo);
    }

    /**
     * 查询某证物全部历史见证（按版本与提交顺序），供查询视图展示。
     */
    public List<DigestWitness> findByEvidenceKey(String evidenceKey) {
        return jdbc.query(
                "SELECT * FROM digest_witness WHERE evidence_key = ? ORDER BY version_no, id",
                ROW_MAPPER, evidenceKey);
    }

    private static final class WitnessRowMapper implements RowMapper<DigestWitness> {
        @Override
        public DigestWitness mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new DigestWitness(
                    rs.getLong("id"),
                    rs.getString("evidence_key"),
                    rs.getInt("version_no"),
                    rs.getString("witness_id"),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
