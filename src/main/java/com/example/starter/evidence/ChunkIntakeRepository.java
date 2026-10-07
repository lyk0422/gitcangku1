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
 * 分块入库待接收记录表访问。补块、完成、放弃必须先通过
 * {@link #findByKeyForUpdate} 锁定记录行，保证三类操作串行校验。
 */
@Repository
public class ChunkIntakeRepository {

    private static final ChunkIntakeRowMapper ROW_MAPPER = new ChunkIntakeRowMapper();

    private final JdbcTemplate jdbc;

    public ChunkIntakeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入待接收记录，初始状态 RECEIVING、版本 0；intake_key 全局唯一，冲突由唯一约束拒绝。
     */
    public void insert(String intakeKey, String evidenceKey, String caseKey, String category,
                       String sealNo, int totalBytes, String sha256, String creatorId,
                       LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO chunk_intake
                            (intake_key, evidence_key, case_key, category, seal_no, total_bytes,
                             sha256, creator_id, status, version, created_at, decided_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, NULL)
                        """,
                intakeKey, evidenceKey, caseKey, category, sealNo, totalBytes, sha256,
                creatorId, ChunkIntakeStatus.RECEIVING.name(), now);
    }

    /**
     * 按业务键查询（不加锁），用于只读场景。
     */
    public Optional<ChunkIntake> findByKey(String intakeKey) {
        List<ChunkIntake> rows = jdbc.query(
                "SELECT * FROM chunk_intake WHERE intake_key = ?", ROW_MAPPER, intakeKey);
        return rows.stream().findFirst();
    }

    /**
     * 按业务键查询并锁定记录行（SELECT ... FOR UPDATE），用于补块、完成、放弃。
     */
    public Optional<ChunkIntake> findByKeyForUpdate(String intakeKey) {
        List<ChunkIntake> rows = jdbc.query(
                "SELECT * FROM chunk_intake WHERE intake_key = ? FOR UPDATE", ROW_MAPPER, intakeKey);
        return rows.stream().findFirst();
    }

    /**
     * 查询指定证物键当前接收中的待接收记录（同一证物键至多一条）。
     */
    public Optional<ChunkIntake> findReceivingByEvidenceKey(String evidenceKey) {
        List<ChunkIntake> rows = jdbc.query(
                "SELECT * FROM chunk_intake WHERE evidence_key = ? AND status = ?",
                ROW_MAPPER, evidenceKey, ChunkIntakeStatus.RECEIVING.name());
        return rows.stream().findFirst();
    }

    /**
     * 查询指定证物键已完成的分块入库记录（用于读取完整字节）。
     */
    public Optional<ChunkIntake> findCompletedByEvidenceKey(String evidenceKey) {
        List<ChunkIntake> rows = jdbc.query(
                "SELECT * FROM chunk_intake WHERE evidence_key = ? AND status = ?",
                ROW_MAPPER, evidenceKey, ChunkIntakeStatus.COMPLETED.name());
        return rows.stream().findFirst();
    }

    /**
     * 版本加 1（补块成功时调用，调用前必须已锁定记录行）。
     */
    public void bumpVersion(long id) {
        jdbc.update("UPDATE chunk_intake SET version = version + 1 WHERE id = ?", id);
    }

    /**
     * 完成或放弃：写入终态、版本加 1 并记录决定时间（调用前必须已锁定记录行）。
     */
    public void decide(long id, ChunkIntakeStatus status, LocalDateTime now) {
        jdbc.update(
                "UPDATE chunk_intake SET status = ?, version = version + 1, decided_at = ? WHERE id = ?",
                status.name(), now, id);
    }

    private static final class ChunkIntakeRowMapper implements RowMapper<ChunkIntake> {
        @Override
        public ChunkIntake mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ChunkIntake(
                    rs.getLong("id"),
                    rs.getString("intake_key"),
                    rs.getString("evidence_key"),
                    rs.getString("case_key"),
                    rs.getString("category"),
                    rs.getString("seal_no"),
                    rs.getInt("total_bytes"),
                    rs.getString("sha256"),
                    rs.getString("creator_id"),
                    ChunkIntakeStatus.valueOf(rs.getString("status")),
                    rs.getInt("version"),
                    rs.getObject("created_at", LocalDateTime.class),
                    rs.getObject("decided_at", LocalDateTime.class));
        }
    }
}
