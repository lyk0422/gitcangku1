package com.example.starter.blind.repo;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 合规隔离单数据访问；确认仅把版本快照置为 CONFIRMED，不删除披露边。
 */
@Repository
public class QuarantineRepository {

    /** 隔离单行；snapshotJson 为发起时闭包快照 JSON，不含处理代码。 */
    public record QuarantineRow(
            String id,
            String experimentId,
            String participantId,
            long versionId,
            int versionNo,
            String initiatorActor,
            String confirmerActor,
            String status,
            String snapshotJson,
            long createdAt,
            Long confirmedAt) {
    }

    private static final RowMapper<QuarantineRow> MAPPER = (rs, n) -> new QuarantineRow(
            rs.getString("id"),
            rs.getString("experiment_id"),
            rs.getString("participant_id"),
            rs.getLong("version_id"),
            rs.getInt("version_no"),
            rs.getString("initiator_actor"),
            rs.getString("confirmer_actor"),
            rs.getString("status"),
            rs.getString("snapshot_json"),
            rs.getLong("created_at"),
            (Long) rs.getObject("confirmed_at"));

    private static final String COLUMNS =
            "id, experiment_id, participant_id, version_id, version_no, initiator_actor, "
                    + "confirmer_actor, status, snapshot_json, created_at, confirmed_at";

    private final JdbcTemplate jdbc;

    public QuarantineRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(QuarantineRow row) {
        jdbc.update("INSERT INTO quarantine_order "
                        + "(id, experiment_id, participant_id, version_id, version_no, "
                        + "initiator_actor, confirmer_actor, status, snapshot_json, "
                        + "created_at, confirmed_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, NULL, 'OPEN', ?, ?, NULL)",
                row.id(), row.experimentId(), row.participantId(), row.versionId(),
                row.versionNo(), row.initiatorActor(), row.snapshotJson(), row.createdAt());
    }

    public QuarantineRow findById(String orderId) {
        List<QuarantineRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM quarantine_order WHERE id = ?", MAPPER, orderId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public QuarantineRow lockById(String orderId) {
        List<QuarantineRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM quarantine_order WHERE id = ? FOR UPDATE",
                MAPPER, orderId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<QuarantineRow> findByParticipant(String experimentId, String participantId) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM quarantine_order "
                        + "WHERE experiment_id = ? AND participant_id = ? ORDER BY created_at, id",
                MAPPER, experimentId, participantId);
    }

    /**
     * 确认隔离单：仅 OPEN 可确认，写入确认人与时间。
     *
     * @return 受影响行数；0 表示不存在或已确认
     */
    public int confirm(String orderId, String confirmerActor, long confirmedAt) {
        return jdbc.update("UPDATE quarantine_order SET status = 'CONFIRMED', "
                        + "confirmer_actor = ?, confirmed_at = ? "
                        + "WHERE id = ? AND status = 'OPEN'",
                confirmerActor, confirmedAt, orderId);
    }
}
