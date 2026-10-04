package com.example.starter.blind.repo;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 盲态样本记录数据访问；只保存摘要与采集 UTC 时刻，不保存真实样本。
 * 同一参与者同一阶段至多一条 ACTIVE 记录由唯一索引 uq_sample_active 保证。
 */
@Repository
public class SampleRepository {

    /** 样本记录行。collectedAt/createdAt 为 Unix 毫秒 UTC；supersedesId 为被替代的最近有效记录。 */
    public record SampleRow(
            long id,
            String experimentId,
            String participantId,
            String phase,
            String sampleCode,
            String digest,
            long collectedAt,
            int chainVersion,
            Long supersedesId,
            String status,
            String submittedActor,
            long createdAt) {
    }

    private static final String COLUMNS =
            "id, experiment_id, participant_id, phase, sample_code, digest, collected_at, "
                    + "chain_version, supersedes_id, status, submitted_actor, created_at";

    private static final RowMapper<SampleRow> MAPPER = (rs, n) -> new SampleRow(
            rs.getLong("id"),
            rs.getString("experiment_id"),
            rs.getString("participant_id"),
            rs.getString("phase"),
            rs.getString("sample_code"),
            rs.getString("digest"),
            rs.getLong("collected_at"),
            rs.getInt("chain_version"),
            (Long) rs.getObject("supersedes_id"),
            rs.getString("status"),
            rs.getString("submitted_actor"),
            rs.getLong("created_at"));

    private final JdbcTemplate jdbc;

    public SampleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入样本记录；activeSlot 仅 ACTIVE 记录非空，唯一索引兜底同阶段并发。
     */
    public void insert(SampleRow row, String activeSlot) {
        jdbc.update("INSERT INTO sample_record ("
                        + "experiment_id, participant_id, phase, sample_code, digest, collected_at, "
                        + "chain_version, supersedes_id, status, active_slot, submitted_actor, created_at"
                        + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.experimentId(), row.participantId(), row.phase(), row.sampleCode(), row.digest(),
                row.collectedAt(), row.chainVersion(), row.supersedesId(), row.status(),
                activeSlot, row.submittedActor(), row.createdAt());
    }

    /** 查询某参与者某阶段当前有效样本；无则返回 null。 */
    public SampleRow findActive(String experimentId, String participantId, String phase) {
        List<SampleRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM sample_record "
                        + "WHERE experiment_id = ? AND participant_id = ? AND phase = ? AND status = 'ACTIVE'",
                MAPPER, experimentId, participantId, phase);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 行级锁定当前有效样本，用于更正与锁定的并发串行化。 */
    public SampleRow lockActive(String experimentId, String participantId, String phase) {
        List<SampleRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM sample_record "
                        + "WHERE experiment_id = ? AND participant_id = ? AND phase = ? AND status = 'ACTIVE' "
                        + "FOR UPDATE",
                MAPPER, experimentId, participantId, phase);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 把有效记录置为被替代并释放有效占位；条件更新保证并发更正只有一个胜出。
     *
     * @return 受影响行数；0 表示记录已非 ACTIVE
     */
    public int markSuperseded(long sampleId) {
        return jdbc.update(
                "UPDATE sample_record SET status = 'SUPERSEDED', active_slot = NULL "
                        + "WHERE id = ? AND status = 'ACTIVE'",
                sampleId);
    }

    /** 协调员查询自己提交的全部样本记录（含被替代历史），按主键顺序。 */
    public List<SampleRow> findByParticipantAndActor(String experimentId, String participantId,
                                                     String actorId) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM sample_record "
                        + "WHERE experiment_id = ? AND participant_id = ? AND submitted_actor = ? "
                        + "ORDER BY id",
                MAPPER, experimentId, participantId, actorId);
    }

    public long countByExperiment(String experimentId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sample_record WHERE experiment_id = ?", Long.class, experimentId);
        return count == null ? 0 : count;
    }

    /** 样本编号全局唯一冲突（同一编号不得用于不同参与者或不同阶段）。 */
    public boolean isDuplicateSampleCode(DuplicateKeyException e) {
        return e.getMessage() != null && e.getMessage().contains("uq_sample_code");
    }

    /** 同参与者同阶段有效占位冲突。 */
    public boolean isDuplicateActiveSlot(DuplicateKeyException e) {
        return e.getMessage() != null && e.getMessage().contains("uq_sample_active");
    }
}