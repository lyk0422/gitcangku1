package com.example.starter.blind.repo;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 不良事件报告数据访问；报告不含席位号与处理代码等盲底信息。
 */
@Repository
public class AdverseEventRepository {

    /** 不良事件报告行。 */
    public record AdverseEventRow(
            long id,
            String experimentId,
            String participantId,
            long allocationId,
            String eventKey,
            String severity,
            String description,
            String reporterActor,
            long createdAt) {
    }

    private static final RowMapper<AdverseEventRow> MAPPER = (rs, n) -> new AdverseEventRow(
            rs.getLong("id"),
            rs.getString("experiment_id"),
            rs.getString("participant_id"),
            rs.getLong("allocation_id"),
            rs.getString("event_key"),
            rs.getString("severity"),
            rs.getString("description"),
            rs.getString("reporter_actor"),
            rs.getLong("created_at"));

    private static final String COLUMNS =
            "id, experiment_id, participant_id, allocation_id, event_key, severity, "
                    + "description, reporter_actor, created_at";

    private final JdbcTemplate jdbc;

    public AdverseEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入报告；eventKey 实验内重复时唯一索引 uq_adverse_event_key 触发异常。
     */
    public void insert(AdverseEventRow row) {
        jdbc.update("INSERT INTO adverse_event ("
                        + "experiment_id, participant_id, allocation_id, event_key, severity, "
                        + "description, reporter_actor, created_at"
                        + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                row.experimentId(), row.participantId(), row.allocationId(), row.eventKey(),
                row.severity(), row.description(), row.reporterActor(), row.createdAt());
    }

    /**
     * 按实验与业务键查询报告（紧急揭盲校验用）。
     */
    public AdverseEventRow findByEventKey(String experimentId, String eventKey) {
        List<AdverseEventRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM adverse_event WHERE experiment_id = ? AND event_key = ?",
                MAPPER, experimentId, eventKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 查询某参与者在该实验下的全部报告历史（按上报时间升序）。
     */
    public List<AdverseEventRow> findByParticipant(String experimentId, String participantId) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM adverse_event "
                        + "WHERE experiment_id = ? AND participant_id = ? ORDER BY created_at, id",
                MAPPER, experimentId, participantId);
    }
}
