package com.example.starter.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 批量入库批次表访问。intake_key 全局唯一；批次行与批内证物在同一事务内插入。
 */
@Repository
public class EvidenceBatchRepository {

    private static final BatchRowMapper ROW_MAPPER = new BatchRowMapper();

    private final JdbcTemplate jdbc;

    public EvidenceBatchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 创建批次记录。
     */
    public void insert(String intakeKey, String custodianId, int totalCount, int matchedCount,
                       int discrepantCount, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO evidence_batch
                            (intake_key, custodian_id, total_count, matched_count, discrepant_count, created_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                intakeKey, custodianId, totalCount, matchedCount, discrepantCount, now);
    }

    /**
     * 按批次键查询批次。
     */
    public Optional<EvidenceBatch> findByIntakeKey(String intakeKey) {
        List<EvidenceBatch> rows = jdbc.query(
                "SELECT * FROM evidence_batch WHERE intake_key = ?", ROW_MAPPER, intakeKey);
        return rows.stream().findFirst();
    }

    private static final class BatchRowMapper implements RowMapper<EvidenceBatch> {
        @Override
        public EvidenceBatch mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new EvidenceBatch(
                    rs.getLong("id"),
                    rs.getString("intake_key"),
                    rs.getString("custodian_id"),
                    rs.getInt("total_count"),
                    rs.getInt("matched_count"),
                    rs.getInt("discrepant_count"),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
