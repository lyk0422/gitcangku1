package com.example.starter.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 证物合并记录表访问。记录只追加、不可变；alias_key 全局唯一，
 * 保证任一证物至多被合并一次，避免环或多层隐式追随。
 */
@Repository
public class MergeRecordRepository {

    private static final MergeRowMapper ROW_MAPPER = new MergeRowMapper();

    private final JdbcTemplate jdbc;

    public MergeRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条合并记录；alias_key 冲突时抛出 DuplicateKeyException。
     */
    public void insert(String primaryKey, String aliasKey, String custodianId,
                       long primaryVersion, long aliasVersion,
                       String primaryChain, String aliasChain,
                       String mergedBy, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO evidence_merge
                            (primary_key, alias_key, custodian_id, primary_version, alias_version,
                             primary_chain, alias_chain, merged_by, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                primaryKey, aliasKey, custodianId, primaryVersion, aliasVersion,
                primaryChain, aliasChain, mergedBy, now);
    }

    /**
     * 查询证物参与的全部合并记录（无论作为主号还是副号），按发生顺序。
     */
    public List<MergeRecord> findByEvidenceKey(String evidenceKey) {
        return jdbc.query(
                "SELECT * FROM evidence_merge WHERE primary_key = ? OR alias_key = ? ORDER BY id",
                ROW_MAPPER, evidenceKey, evidenceKey);
    }

    /**
     * 判断证物是否已作为某次合并的主号（用于阻止主号再被合并为副号形成多层追随）。
     */
    public boolean existsByPrimaryKey(String primaryKey) {
        List<Long> rows = jdbc.query(
                "SELECT id FROM evidence_merge WHERE primary_key = ?",
                (rs, rowNum) -> rs.getLong("id"), primaryKey);
        return !rows.isEmpty();
    }

    private static final class MergeRowMapper implements RowMapper<MergeRecord> {
        @Override
        public MergeRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new MergeRecord(
                    rs.getLong("id"),
                    rs.getString("primary_key"),
                    rs.getString("alias_key"),
                    rs.getString("custodian_id"),
                    rs.getLong("primary_version"),
                    rs.getLong("alias_version"),
                    rs.getString("primary_chain"),
                    rs.getString("alias_chain"),
                    rs.getString("merged_by"),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
