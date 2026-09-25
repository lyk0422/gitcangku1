package com.example.starter.blind.repo;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 替补记录数据访问；记录不可变，只插入与查询，不提供更新。
 * 表内不保存处理代码、席位号与盲码等盲底。
 */
@Repository
public class ReplacementRepository {

    /** 替补记录行。 */
    public record ReplacementRow(
            long id,
            String replaceKey,
            String experimentId,
            int blockNo,
            long allocationId,
            String originalParticipantId,
            String newParticipantId,
            String actorId,
            long replacedAt) {
    }

    private static final RowMapper<ReplacementRow> MAPPER = (rs, n) -> new ReplacementRow(
            rs.getLong("id"),
            rs.getString("replace_key"),
            rs.getString("experiment_id"),
            rs.getInt("block_no"),
            rs.getLong("allocation_id"),
            rs.getString("original_participant_id"),
            rs.getString("new_participant_id"),
            rs.getString("actor_id"),
            rs.getLong("replaced_at"));

    private static final String COLUMNS =
            "id, replace_key, experiment_id, block_no, allocation_id, "
                    + "original_participant_id, new_participant_id, actor_id, replaced_at";

    private final JdbcTemplate jdbc;

    public ReplacementRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入替补记录；replaceKey、同实验原参与者、同实验替补参与者的唯一索引兜底并发，
     * 冲突时抛出 DuplicateKeyException。
     */
    public void insert(ReplacementRow row) {
        jdbc.update("INSERT INTO replacement ("
                        + "replace_key, experiment_id, block_no, allocation_id, "
                        + "original_participant_id, new_participant_id, actor_id, replaced_at"
                        + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                row.replaceKey(), row.experimentId(), row.blockNo(), row.allocationId(),
                row.originalParticipantId(), row.newParticipantId(), row.actorId(), row.replacedAt());
    }

    /** 按原参与者查询替补记录；用于判定 REPLACED 终态。 */
    public ReplacementRow findByOriginal(String experimentId, String originalParticipantId) {
        List<ReplacementRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM replacement "
                        + "WHERE experiment_id = ? AND original_participant_id = ?",
                MAPPER, experimentId, originalParticipantId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 按替补参与者查询替补记录；用于判定其是否拥有历史分配。 */
    public ReplacementRow findByNew(String experimentId, String newParticipantId) {
        List<ReplacementRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM replacement "
                        + "WHERE experiment_id = ? AND new_participant_id = ?",
                MAPPER, experimentId, newParticipantId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 区组替补历史，按替补时间升序。 */
    public List<ReplacementRow> listByBlock(String experimentId, int blockNo) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM replacement "
                        + "WHERE experiment_id = ? AND block_no = ? ORDER BY replaced_at, id",
                MAPPER, experimentId, blockNo);
    }
}
