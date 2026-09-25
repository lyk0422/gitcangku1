package com.example.starter.maintenance.store;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.maintenance.domain.MaintenanceSnapshot;
import com.example.starter.maintenance.domain.WorkOrder;

/**
 * 保养工单数据访问层：工单状态机、关闭快照与 workOrderKey 幂等记录。
 * 所有 SQL 参数化；时刻字段（TIMESTAMP WITH TIME ZONE）以 UTC 存取。
 */
@Repository
public class WorkOrderRepository {

    private final JdbcTemplate jdbc;

    public WorkOrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant readInstant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        java.time.OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static final RowMapper<WorkOrder> WORK_ORDER_MAPPER = (rs, rowNum) -> new WorkOrder(
            rs.getString("work_order_id"),
            rs.getString("equipment_id"),
            rs.getLong("version"),
            rs.getString("status"),
            rs.getString("baseline_reading_id"),
            rs.getInt("baseline_revision_no"),
            readInstant(rs, "baseline_sampled_at"),
            rs.getLong("baseline_cumulative_minutes"),
            readInstant(rs, "window_start"),
            readInstant(rs, "window_end"),
            rs.getString("last_valid_reading_id"),
            readInstant(rs, "last_valid_sampled_at"),
            (Long) rs.getObject("last_valid_cumulative_minutes"),
            (Integer) rs.getObject("last_valid_revision_no"),
            readInstant(rs, "created_at"),
            readInstant(rs, "started_at"),
            readInstant(rs, "closed_at"),
            readInstant(rs, "cancelled_at"),
            readInstant(rs, "terminated_at"),
            rs.getString("terminate_reason"));

    private static final String WORK_ORDER_COLUMNS = String.join(", ",
            "work_order_id", "equipment_id", "version", "status",
            "baseline_reading_id", "baseline_revision_no", "baseline_sampled_at",
            "baseline_cumulative_minutes", "window_start", "window_end",
            "last_valid_reading_id", "last_valid_sampled_at", "last_valid_cumulative_minutes",
            "last_valid_revision_no", "created_at", "started_at", "closed_at",
            "cancelled_at", "terminated_at", "terminate_reason");

    private static final RowMapper<MaintenanceSnapshot> SNAPSHOT_MAPPER = (rs, rowNum) ->
            new MaintenanceSnapshot(
                    rs.getLong("snapshot_id"),
                    rs.getString("work_order_id"),
                    rs.getString("equipment_id"),
                    rs.getString("baseline_reading_id"),
                    rs.getInt("baseline_revision_no"),
                    readInstant(rs, "baseline_sampled_at"),
                    rs.getLong("baseline_cumulative_minutes"),
                    rs.getString("last_valid_reading_id"),
                    rs.getInt("last_valid_revision_no"),
                    readInstant(rs, "last_valid_sampled_at"),
                    rs.getLong("last_valid_cumulative_minutes"),
                    readInstant(rs, "closed_at"));

    // ---------- 工单 ----------

    public void insertWorkOrder(WorkOrder order, Instant createdAt) {
        jdbc.update("INSERT INTO work_order (work_order_id, equipment_id, version, status,"
                        + " baseline_reading_id, baseline_revision_no, baseline_sampled_at,"
                        + " baseline_cumulative_minutes, window_start, window_end,"
                        + " last_valid_reading_id, last_valid_sampled_at, last_valid_cumulative_minutes,"
                        + " last_valid_revision_no, created_at, started_at, closed_at,"
                        + " cancelled_at, terminated_at, terminate_reason)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                order.workOrderId(), order.equipmentId(), order.version(), order.status(),
                order.baselineReadingId(), order.baselineRevisionNo(),
                utc(order.baselineSampledAt()), order.baselineCumulativeMinutes(),
                utc(order.windowStart()), utc(order.windowEnd()),
                order.lastValidReadingId(),
                order.lastValidSampledAt() == null ? null : utc(order.lastValidSampledAt()),
                order.lastValidCumulativeMinutes(), order.lastValidRevisionNo(),
                utc(createdAt),
                order.startedAt() == null ? null : utc(order.startedAt()),
                order.closedAt() == null ? null : utc(order.closedAt()),
                order.cancelledAt() == null ? null : utc(order.cancelledAt()),
                order.terminatedAt() == null ? null : utc(order.terminatedAt()),
                order.terminateReason());
    }

    public Optional<WorkOrder> findWorkOrder(String equipmentId, String workOrderId) {
        List<WorkOrder> rows = jdbc.query(
                "SELECT " + WORK_ORDER_COLUMNS + " FROM work_order"
                        + " WHERE equipment_id = ? AND work_order_id = ?",
                WORK_ORDER_MAPPER, equipmentId, workOrderId);
        return rows.stream().findFirst();
    }

    /** 设备当前未终结（未开始/进行中）的工单；同一设备同时只允许一个开放工单。 */
    public Optional<WorkOrder> findOpenWorkOrder(String equipmentId) {
        List<WorkOrder> rows = jdbc.query(
                "SELECT " + WORK_ORDER_COLUMNS + " FROM work_order"
                        + " WHERE equipment_id = ? AND status IN ('CREATED', 'IN_PROGRESS')"
                        + " ORDER BY created_at DESC LIMIT 1",
                WORK_ORDER_MAPPER, equipmentId);
        return rows.stream().findFirst();
    }

    public List<WorkOrder> listWorkOrders(String equipmentId) {
        return jdbc.query(
                "SELECT " + WORK_ORDER_COLUMNS + " FROM work_order WHERE equipment_id = ?"
                        + " ORDER BY created_at ASC, work_order_id ASC",
                WORK_ORDER_MAPPER, equipmentId);
    }

    /** 条件推进工单状态与版本：仅当状态与版本都匹配时生效，返回受影响行数。 */
    public int compareAndUpdateStatus(String equipmentId, String workOrderId,
                                      String expectedStatus, long expectedVersion,
                                      String newStatus, String statusTimeColumn, Instant statusTime) {
        return jdbc.update("UPDATE work_order SET status = ?, version = version + 1,"
                        + " " + statusTimeColumn + " = ?"
                        + " WHERE equipment_id = ? AND work_order_id = ? AND status = ? AND version = ?",
                newStatus, utc(statusTime), equipmentId, workOrderId,
                expectedStatus, expectedVersion);
    }

    /** 开始工单：状态 CREATED→IN_PROGRESS，并写 started_at。 */
    public int markStarted(String equipmentId, String workOrderId, long expectedVersion, Instant startedAt) {
        return jdbc.update("UPDATE work_order SET status = 'IN_PROGRESS', version = version + 1,"
                        + " started_at = ? WHERE equipment_id = ? AND work_order_id = ?"
                        + " AND status = 'CREATED' AND version = ?",
                utc(startedAt), equipmentId, workOrderId, expectedVersion);
    }

    /** 终止工单：状态 IN_PROGRESS→TERMINATED，写终止时刻与原因。 */
    public int markTerminated(String equipmentId, String workOrderId, long expectedVersion,
                              String reason, Instant terminatedAt) {
        return jdbc.update("UPDATE work_order SET status = 'TERMINATED', version = version + 1,"
                        + " terminated_at = ?, terminate_reason = ?"
                        + " WHERE equipment_id = ? AND work_order_id = ?"
                        + " AND status = 'IN_PROGRESS' AND version = ?",
                utc(terminatedAt), reason, equipmentId, workOrderId, expectedVersion);
    }

    /** 更新工单最近有效读数（窗口内批量登记成功后）。 */
    public void updateLastValidReading(String equipmentId, String workOrderId,
                                       String readingId, Instant sampledAt,
                                       long cumulativeMinutes, int revisionNo) {
        jdbc.update("UPDATE work_order SET last_valid_reading_id = ?, last_valid_sampled_at = ?,"
                        + " last_valid_cumulative_minutes = ?, last_valid_revision_no = ?"
                        + " WHERE equipment_id = ? AND work_order_id = ?",
                readingId, utc(sampledAt), cumulativeMinutes, revisionNo,
                equipmentId, workOrderId);
    }

    // ---------- 关闭快照 ----------

    public long insertSnapshot(MaintenanceSnapshot snapshot) {
        org.springframework.jdbc.support.KeyHolder keyHolder =
                new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(connection -> {
            java.sql.PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO maintenance_snapshot (work_order_id, equipment_id,"
                            + " baseline_reading_id, baseline_revision_no, baseline_sampled_at,"
                            + " baseline_cumulative_minutes, last_valid_reading_id,"
                            + " last_valid_revision_no, last_valid_sampled_at,"
                            + " last_valid_cumulative_minutes, closed_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    java.sql.Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, snapshot.workOrderId());
            ps.setString(2, snapshot.equipmentId());
            ps.setString(3, snapshot.baselineReadingId());
            ps.setInt(4, snapshot.baselineRevisionNo());
            ps.setObject(5, utc(snapshot.baselineSampledAt()));
            ps.setLong(6, snapshot.baselineCumulativeMinutes());
            ps.setString(7, snapshot.lastValidReadingId());
            ps.setInt(8, snapshot.lastValidRevisionNo());
            ps.setObject(9, utc(snapshot.lastValidSampledAt()));
            ps.setLong(10, snapshot.lastValidCumulativeMinutes());
            ps.setObject(11, utc(snapshot.closedAt()));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("工单关闭快照主键生成失败");
        }
        return key.longValue();
    }

    public Optional<MaintenanceSnapshot> findSnapshot(String equipmentId, String workOrderId) {
        List<MaintenanceSnapshot> rows = jdbc.query(
                "SELECT snapshot_id, work_order_id, equipment_id, baseline_reading_id,"
                        + " baseline_revision_no, baseline_sampled_at, baseline_cumulative_minutes,"
                        + " last_valid_reading_id, last_valid_revision_no, last_valid_sampled_at,"
                        + " last_valid_cumulative_minutes, closed_at"
                        + " FROM maintenance_snapshot WHERE equipment_id = ? AND work_order_id = ?",
                SNAPSHOT_MAPPER, equipmentId, workOrderId);
        return rows.stream().findFirst();
    }

    // ---------- 工单操作幂等 ----------

    public Optional<IdempotencyRow> findIdempotency(String workOrderKey) {
        List<IdempotencyRow> rows = jdbc.query(
                "SELECT work_order_key, operation, request_fingerprint, response_body"
                        + " FROM work_order_idempotency WHERE work_order_key = ?",
                (rs, rowNum) -> new IdempotencyRow(
                        rs.getString("work_order_key"),
                        rs.getString("operation"),
                        rs.getString("request_fingerprint"),
                        rs.getString("response_body")),
                workOrderKey);
        return rows.stream().findFirst();
    }

    public void insertIdempotency(String workOrderKey, String operation, String fingerprint,
                                  String responseBody, Instant createdAt) {
        jdbc.update("INSERT INTO work_order_idempotency (work_order_key, operation,"
                        + " request_fingerprint, response_body, created_at) VALUES (?, ?, ?, ?, ?)",
                workOrderKey, operation, fingerprint, responseBody, utc(createdAt));
    }

    public record IdempotencyRow(String workOrderKey, String operation,
                                 String fingerprint, String responseBody) {
    }
}
