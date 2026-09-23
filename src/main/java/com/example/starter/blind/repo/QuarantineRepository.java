package com.example.starter.blind.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 污染隔离单数据访问；同一参与者至多一个待确认隔离单由 pending_key 唯一列保证。
 */
@Repository
public class QuarantineRepository {

    /** 隔离单行；closureActorsJson 为冻结的闭包操作者 JSON 数组。 */
    public record QuarantineRow(
            String id,
            String experimentId,
            String participantId,
            int version,
            String status,
            String initiatorActor,
            String confirmerActor,
            String closureActorsJson,
            long createdAt,
            Long confirmedAt,
            String pendingKey) {
    }

    private static final String COLUMNS =
            "id, experiment_id, participant_id, version, status, initiator_actor, "
                    + "confirmer_actor, closure_actors, created_at, confirmed_at, pending_key";

    private static final RowMapper<QuarantineRow> MAPPER = (rs, n) -> new QuarantineRow(
            rs.getString("id"),
            rs.getString("experiment_id"),
            rs.getString("participant_id"),
            rs.getInt("version"),
            rs.getString("status"),
            rs.getString("initiator_actor"),
            rs.getString("confirmer_actor"),
            rs.getString("closure_actors"),
            rs.getLong("created_at"),
            (Long) rs.getObject("confirmed_at"),
            rs.getString("pending_key"));

    private final JdbcTemplate jdbc;

    public QuarantineRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 插入待确认隔离单；同参与者已有待确认单时唯一列 pending_key 触发 DuplicateKeyException。 */
    public void insertOpen(QuarantineRow row) {
        jdbc.update("INSERT INTO quarantine_order ("
                        + "id, experiment_id, participant_id, version, status, initiator_actor, "
                        + "confirmer_actor, closure_actors, created_at, confirmed_at, pending_key"
                        + ") VALUES (?, ?, ?, ?, 'OPEN', ?, NULL, ?, ?, NULL, ?)",
                row.id(), row.experimentId(), row.participantId(), row.version(),
                row.initiatorActor(), row.closureActorsJson(), row.createdAt(), row.pendingKey());
    }

    public QuarantineRow findById(String orderId) {
        List<QuarantineRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM quarantine_order WHERE id = ?", MAPPER, orderId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 行级锁定隔离单。 */
    public QuarantineRow lockById(String orderId) {
        List<QuarantineRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM quarantine_order WHERE id = ? FOR UPDATE",
                MAPPER, orderId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 查询某参与者全部隔离单，按发起时间升序。 */
    public List<QuarantineRow> findByParticipant(String experimentId, String participantId) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM quarantine_order "
                        + "WHERE experiment_id = ? AND participant_id = ? ORDER BY created_at, id",
                MAPPER, experimentId, participantId);
    }

    /** 确认关闭：仅 OPEN 可关闭，写入确认人/时间并释放 pending_key，返回受影响行数。 */
    public int confirm(String orderId, String confirmerActor, long confirmedAt) {
        return jdbc.update("UPDATE quarantine_order SET status = 'CLOSED', confirmer_actor = ?, "
                        + "confirmed_at = ?, pending_key = NULL WHERE id = ? AND status = 'OPEN'",
                confirmerActor, confirmedAt, orderId);
    }
}
