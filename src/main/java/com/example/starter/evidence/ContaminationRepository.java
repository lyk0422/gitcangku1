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
 * 污染登记与传播路径表访问。记录只追加，污染不可解除，传播路径历史冻结。
 */
@Repository
public class ContaminationRepository {

    private static final ContaminationRowMapper ROW_MAPPER = new ContaminationRowMapper();
    private static final AffectedRowMapper AFFECTED_ROW_MAPPER = new AffectedRowMapper();

    private final JdbcTemplate jdbc;

    public ContaminationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加污染登记。
     */
    public void insert(String contaminationKey, String evidenceKey, long startSeq,
                       String registeredBy, int affectedCount, LocalDateTime createdAt) {
        jdbc.update("""
                        INSERT INTO contamination_record
                            (contamination_key, evidence_key, start_seq, registered_by, affected_count, created_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                contaminationKey, evidenceKey, startSeq, registeredBy, affectedCount, createdAt);
    }

    /**
     * 追加一条传播路径（受影响证物及其来源批次；起点证物来源批次为 null）。
     */
    public void insertAffected(String contaminationKey, String evidenceKey,
                               String sourceBatchKey, LocalDateTime createdAt) {
        jdbc.update("""
                        INSERT INTO contamination_affected
                            (contamination_key, evidence_key, source_batch_key, created_at)
                        VALUES (?, ?, ?, ?)
                        """,
                contaminationKey, evidenceKey, sourceBatchKey, createdAt);
    }

    /**
     * 按业务键查询污染登记（不加锁）。
     */
    public Optional<ContaminationRecord> findByKey(String contaminationKey) {
        List<ContaminationRecord> rows = jdbc.query(
                "SELECT * FROM contamination_record WHERE contamination_key = ?",
                ROW_MAPPER, contaminationKey);
        return rows.stream().findFirst();
    }

    /**
     * 按登记顺序查询污染登记的全部传播路径。
     */
    public List<ContaminationAffected> findAffected(String contaminationKey) {
        return jdbc.query(
                "SELECT * FROM contamination_affected WHERE contamination_key = ? ORDER BY id",
                AFFECTED_ROW_MAPPER, contaminationKey);
    }

    private static final class ContaminationRowMapper implements RowMapper<ContaminationRecord> {
        @Override
        public ContaminationRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ContaminationRecord(
                    rs.getLong("id"),
                    rs.getString("contamination_key"),
                    rs.getString("evidence_key"),
                    rs.getLong("start_seq"),
                    rs.getString("registered_by"),
                    rs.getInt("affected_count"),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }

    private static final class AffectedRowMapper implements RowMapper<ContaminationAffected> {
        @Override
        public ContaminationAffected mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ContaminationAffected(
                    rs.getLong("id"),
                    rs.getString("contamination_key"),
                    rs.getString("evidence_key"),
                    rs.getString("source_batch_key"),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
