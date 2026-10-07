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
 * 分块接收表访问。状态与版本变更必须先通过 {@link #findByKeyForUpdate} 锁定接收行，
 * 保证补块、完成、放弃在同一接收上串行校验。
 */
@Repository
public class ChunkIntakeRepository {

    private static final ChunkIntakeRowMapper ROW_MAPPER = new ChunkIntakeRowMapper();

    private final JdbcTemplate jdbc;

    public ChunkIntakeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 创建待接收记录，初始状态 RECEIVING、版本 0；intake_key 冲突由唯一约束拒绝。
     */
    public void insert(String intakeKey, String caseKey, String category, String sealNo,
                       String custodianId, int totalLength, String sha256, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO chunk_intake
                            (intake_key, case_key, category, seal_no, custodian_id,
                             total_length, sha256, status, version, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                intakeKey, caseKey, category, sealNo, custodianId, totalLength,
                sha256.toLowerCase(), ChunkIntakeStatus.RECEIVING.name(), 0, now, now);
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
     * 按业务键查询并锁定接收行（SELECT ... FOR UPDATE），补块/完成/放弃前必须持锁。
     */
    public Optional<ChunkIntake> findByKeyForUpdate(String intakeKey) {
        List<ChunkIntake> rows = jdbc.query(
                "SELECT * FROM chunk_intake WHERE intake_key = ? FOR UPDATE", ROW_MAPPER, intakeKey);
        return rows.stream().findFirst();
    }

    /**
     * 成功写入一个新块后递增版本号并刷新变更时间。
     */
    public void incrementVersion(String intakeKey, int version, LocalDateTime now) {
        jdbc.update(
                "UPDATE chunk_intake SET version = ?, updated_at = ? WHERE intake_key = ?",
                version, now, intakeKey);
    }

    /**
     * 显式完成：状态置为 SEALED，块清单此后只读。
     */
    public void markSealed(String intakeKey, LocalDateTime now) {
        jdbc.update(
                "UPDATE chunk_intake SET status = ?, updated_at = ? WHERE intake_key = ?",
                ChunkIntakeStatus.SEALED.name(), now, intakeKey);
    }

    /**
     * 放弃接收：状态置为 ABANDONED（终态）。
     */
    public void markAbandoned(String intakeKey, LocalDateTime now) {
        jdbc.update(
                "UPDATE chunk_intake SET status = ?, updated_at = ? WHERE intake_key = ?",
                ChunkIntakeStatus.ABANDONED.name(), now, intakeKey);
    }

    private static final class ChunkIntakeRowMapper implements RowMapper<ChunkIntake> {
        @Override
        public ChunkIntake mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ChunkIntake(
                    rs.getLong("id"),
                    rs.getString("intake_key"),
                    rs.getString("case_key"),
                    rs.getString("category"),
                    rs.getString("seal_no"),
                    rs.getString("custodian_id"),
                    rs.getInt("total_length"),
                    rs.getString("sha256"),
                    ChunkIntakeStatus.valueOf(rs.getString("status")),
                    rs.getInt("version"),
                    rs.getObject("created_at", LocalDateTime.class),
                    rs.getObject("updated_at", LocalDateTime.class));
        }
    }
}
