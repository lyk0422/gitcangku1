package com.example.starter.blind.repo;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 不良事件报告数据访问；报告表不含处理代码/席位号等盲底列。
 * 同一分配内 event_key 唯一由唯一索引 uq_aer_allocation_event 兜底并发。
 */
@Repository
public class AdverseEventRepository {

    /** 报告行；unblindRequestId 非空表示该报告已被用于紧急揭盲。 */
    public record AdverseEventRow(
            long id,
            String experimentId,
            String participantId,
            long allocationId,
            String eventKey,
            String severity,
            String description,
            String reporterActor,
            String reporterRole,
            long createdAt,
            String unblindRequestId) {
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
            rs.getString("reporter_role"),
            rs.getLong("created_at"),
            rs.getString("unblind_request_id"));

    private static final String COLUMNS =
            "id, experiment_id, participant_id, allocation_id, event_key, severity, description, "
                    + "reporter_actor, reporter_role, created_at, unblind_request_id";

    private final JdbcTemplate jdbc;

    public AdverseEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入报告；同一分配重复 eventKey 由唯一索引触发 DuplicateKeyException。
     */
    public void insert(AdverseEventRow row) {
        jdbc.update("INSERT INTO adverse_event_report ("
                        + "experiment_id, participant_id, allocation_id, event_key, severity, "
                        + "description, reporter_actor, reporter_role, created_at, unblind_request_id"
                        + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)",
                row.experimentId(), row.participantId(), row.allocationId(), row.eventKey(),
                row.severity(), row.description(), row.reporterActor(), row.reporterRole(),
                row.createdAt());
    }

    public AdverseEventRow findByAllocationAndEventKey(long allocationId, String eventKey) {
        List<AdverseEventRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM adverse_event_report "
                        + "WHERE allocation_id = ? AND event_key = ?",
                MAPPER, allocationId, eventKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 行级锁定报告，供紧急揭盲校验与标记原子执行。
     */
    public AdverseEventRow lockByAllocationAndEventKey(long allocationId, String eventKey) {
        List<AdverseEventRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM adverse_event_report "
                        + "WHERE allocation_id = ? AND event_key = ? FOR UPDATE",
                MAPPER, allocationId, eventKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 某参与者（按分配）的报告历史，按报告时间升序。
     */
    public List<AdverseEventRow> findByAllocation(long allocationId) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM adverse_event_report "
                        + "WHERE allocation_id = ? ORDER BY created_at, id",
                MAPPER, allocationId);
    }

    /**
     * 标记报告已用于紧急揭盲；仅未标记时可更新。
     *
     * @return 受影响行数；0 表示已被其他紧急揭盲使用
     */
    public int markUsedForUnblind(long reportId, String unblindRequestId) {
        return jdbc.update(
                "UPDATE adverse_event_report SET unblind_request_id = ? "
                        + "WHERE id = ? AND unblind_request_id IS NULL",
                unblindRequestId, reportId);
    }

    public boolean isDuplicateEventKey(DuplicateKeyException e) {
        // H2 异常信息中约束名为大写，统一转大写后匹配。
        return e.getMessage() != null
                && e.getMessage().toUpperCase().contains("UQ_AER_ALLOCATION_EVENT");
    }
}
