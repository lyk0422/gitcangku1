package com.example.starter.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 摘要见证记录表访问。只追加；同一 (evidence_key, version_no, witness_id) 唯一。
 */
@Repository
public class DigestWitnessRepository {

    private static final DigestWitnessRowMapper ROW_MAPPER = new DigestWitnessRowMapper();

    private final JdbcTemplate jdbc;

    public DigestWitnessRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条见证记录；同人重复见证触发唯一约束冲突。
     */
    public void insert(String evidenceKey, int versionNo, String witnessId, String commandKey,
                       LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO digest_witness
                            (evidence_key, version_no, witness_id, command_key, created_at)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                evidenceKey, versionNo, witnessId, commandKey, now);
    }

    /**
     * 按见证顺序查询某版本全部见证人。
     */
    public List<DigestWitness> findByVersion(String evidenceKey, int versionNo) {
        return jdbc.query(
                "SELECT * FROM digest_witness WHERE evidence_key = ? AND version_no = ? ORDER BY id",
                ROW_MAPPER, evidenceKey, versionNo);
    }

    private static final class DigestWitnessRowMapper implements RowMapper<DigestWitness> {
        @Override
        public DigestWitness mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new DigestWitness(
                    rs.getLong("id"),
                    rs.getString("evidence_key"),
                    rs.getInt("version_no"),
                    rs.getString("witness_id"),
                    rs.getString("command_key"),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
