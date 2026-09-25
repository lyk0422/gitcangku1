package com.example.starter.blind.repo;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 替补记录数据访问。替补记录不可变：只插入、只查询，不提供任何更新/删除语句。
 * 记录不保存处理代码、席位号与盲码，仅固化原参与者、新参与者、区组、分配序号与时刻。
 */
@Repository
public class ReplacementRepository {

    /** 替补记录行。 */
    public record ReplacementRow(
            long id,
            String replaceKey,
            String experimentId,
            long allocationId,
            int blockNo,
            String originalParticipantId,
            String newParticipantId,
            String operatorActor,
            long originalAssignedAt,
            long originalWithdrawnAt,
            long replacedAt) {
    }

    private static final RowMapper<ReplacementRow> MAPPER = (rs, n) -> new ReplacementRow(
            rs.getLong("id"),
            rs.getString("replace_key"),
            rs.getString("experiment_id"),
            rs.getLong("allocation_id"),
            rs.getInt("block_no"),
            rs.getString("original_participant_id"),
            rs.getString("new_participant_id"),
            rs.getString("operator_actor"),
            rs.getLong("original_assigned_at"),
            rs.getLong("original_withdrawn_at"),
            rs.getLong("replaced_at"));

    private static final String COLUMNS =
            "id, replace_key, experiment_id, allocation_id, block_no, "
                    + "original_participant_id, new_participant_id, operator_actor, "
                    + "original_assigned_at, original_withdrawn_at, replaced_at";

    private final JdbcTemplate jdbc;

    public ReplacementRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(ReplacementRow row) {
        jdbc.update("INSERT INTO replacement ("
                        + "replace_key, experiment_id, allocation_id, block_no, "
                        + "original_participant_id, new_participant_id, operator_actor, "
                        + "original_assigned_at, original_withdrawn_at, replaced_at"
                        + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.replaceKey(), row.experimentId(), row.allocationId(), row.blockNo(),
                row.originalParticipantId(), row.newParticipantId(), row.operatorActor(),
                row.originalAssignedAt(), row.originalWithdrawnAt(), row.replacedAt());
    }

    /** 按原参与者查询替补记录；存在即表示该参与者已转入 REPLACED 终态。 */
    public ReplacementRow findByOriginal(String experimentId, String originalParticipantId) {
        List<ReplacementRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM replacement "
                        + "WHERE experiment_id = ? AND original_participant_id = ?",
                MAPPER, experimentId, originalParticipantId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 全局查询某参与者是否出现在任何替补记录中（无论作为原参与者还是新参与者）。
     * 用于“新参与者不得存在于任何区组或拥有历史分配”的判定。
     */
    public boolean existsAnywhereByParticipant(String participantId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM replacement "
                        + "WHERE original_participant_id = ? OR new_participant_id = ?",
                Long.class, participantId, participantId);
        return count != null && count > 0;
    }

    /** 本实验内某参与者是否已进入过替补流程（作为原参与者或新参与者）。 */
    public boolean existsInExperimentByParticipant(String experimentId, String participantId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM replacement WHERE experiment_id = ? "
                        + "AND (original_participant_id = ? OR new_participant_id = ?)",
                Long.class, experimentId, participantId, participantId);
        return count != null && count > 0;
    }

    /** 区组替补历史：按替补时刻与主键排序；blockNo 为 null 时返回全实验。 */
    public List<ReplacementRow> findHistory(String experimentId, Integer blockNo) {
        if (blockNo == null) {
            return jdbc.query(
                    "SELECT " + COLUMNS + " FROM replacement WHERE experiment_id = ? "
                            + "ORDER BY replaced_at, id",
                    MAPPER, experimentId);
        }
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM replacement WHERE experiment_id = ? AND block_no = ? "
                        + "ORDER BY replaced_at, id",
                MAPPER, experimentId, blockNo);
    }

    public long countByBlock(String experimentId, int blockNo) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM replacement WHERE experiment_id = ? AND block_no = ?",
                Long.class, experimentId, blockNo);
        return count == null ? 0 : count;
    }
}
