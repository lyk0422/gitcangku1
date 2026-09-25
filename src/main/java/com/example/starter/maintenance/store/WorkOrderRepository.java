package com.example.starter.maintenance.store;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.maintenance.domain.WorkOrder;
import com.example.starter.maintenance.domain.WorkOrderStatus;

/**
 * 保养工单数据访问层。所有 SQL 参数化；时刻字段（TIMESTAMP WITH TIME ZONE）以 UTC 存取。
 */
@Repository
public class WorkOrderRepository {

    private final JdbcTemplate jdbc;

    public WorkOrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant readInstant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static final RowMapper<WorkOrder> WORK_ORDER_MAPPER = (rs, rowNum) -> new WorkOrder(
            rs.getString("work_order_key"),
            rs.getString("equipment_id"),
            WorkOrderStatus.valueOf(rs.getString("status")),
            rs.getLong("work_order_version"),
            rs.getString("baseline_reading_id"),
            readInstant(rs, "baseline_sampled_at"),
            rs.getLong("baseline_cumulative_minutes"),
            readInstant(rs, "window_start"),
            readInstant(rs, "window_end"),
            readInstant(rs, "started_at"),
            readInstant(rs, "closed_at"),
            readInstant(rs, "cancelled_at"),
            readInstant(rs, "terminated_at"),
            rs.getString("snapshot_last_reading_id"),
            readInstant(rs, "snapshot_last_sampled_at"),
            (Long) rs.getObject("snapshot_last_cumulative_minutes"),
            readInstant(rs, "created_at"),
            readInstant(rs, "updated_at"));

    private static final String SELECT_COLUMNS =
            "work_order_key, equipment_id, status, work_order_version,"
                    + " baseline_reading_id, baseline_sampled_at, baseline_cumulative_minutes,"
                    + " window_start, window_end, started_at, closed_at, cancelled_at, terminated_at,"
                    + " snapshot_last_reading_id, snapshot_last_sampled_at,"
                    + " snapshot_last_cumulative_minutes, created_at, updated_at";

    public void insertWorkOrder(WorkOrder order) {
        jdbc.update("INSERT INTO work_order (work_order_key, equipment_id, status, work_order_version,"
                        + " baseline_reading_id, baseline_sampled_at, baseline_cumulative_minutes,"
                        + " window_start, window_end, started_at, closed_at, cancelled_at, terminated_at,"
                        + " snapshot_last_reading_id, snapshot_last_sampled_at,"
                        + " snapshot_last_cumulative_minutes, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                order.workOrderKey(), order.equipmentId(), order.status().name(), order.workOrderVersion(),
                order.baselineReadingId(), utc(order.baselineSampledAt()), order.baselineCumulativeMinutes(),
                utc(order.windowStart()), utc(order.windowEnd()), utc(order.startedAt()), utc(order.closedAt()),
                utc(order.cancelledAt()), utc(order.terminatedAt()),
                order.snapshotLastReadingId(), utc(order.snapshotLastSampledAt()),
                order.snapshotLastCumulativeMinutes(), utc(order.createdAt()), utc(order.updatedAt()));
    }

    /** 按工单标识查询（work_order_key 全局唯一）。 */
    public Optional<WorkOrder> findByKey(String workOrderKey) {
        List<WorkOrder> rows = jdbc.query(
                "SELECT " + SELECT_COLUMNS + " FROM work_order WHERE work_order_key = ?",
                WORK_ORDER_MAPPER, workOrderKey);
        return rows.stream().findFirst();
    }

    /** 悲观行锁：同一工单的写操作串行化，保证按提交顺序裁决。 */
    public Optional<WorkOrder> findByKeyForUpdate(String workOrderKey) {
        List<WorkOrder> rows = jdbc.query(
                "SELECT " + SELECT_COLUMNS + " FROM work_order WHERE work_order_key = ? FOR UPDATE",
                WORK_ORDER_MAPPER, workOrderKey);
        return rows.stream().findFirst();
    }

    /** 设备当前未完结（CREATED 或 STARTED）的工单，至多一个。 */
    public Optional<WorkOrder> findActiveByEquipment(String equipmentId) {
        List<WorkOrder> rows = jdbc.query(
                "SELECT " + SELECT_COLUMNS + " FROM work_order"
                        + " WHERE equipment_id = ? AND status IN ('CREATED', 'STARTED')"
                        + " ORDER BY created_at ASC LIMIT 1",
                WORK_ORDER_MAPPER, equipmentId);
        return rows.stream().findFirst();
    }

    /** 设备当前进行中（STARTED）的工单；用于约束工单期间的新读数。 */
    public Optional<WorkOrder> findStartedByEquipment(String equipmentId) {
        List<WorkOrder> rows = jdbc.query(
                "SELECT " + SELECT_COLUMNS + " FROM work_order"
                        + " WHERE equipment_id = ? AND status = 'STARTED'"
                        + " ORDER BY created_at ASC LIMIT 1",
                WORK_ORDER_MAPPER, equipmentId);
        return rows.stream().findFirst();
    }

    /** 状态推进：更新状态、版本、各状态时刻与关闭快照；快照字段仅在关闭时写入一次。 */
    public void updateWorkOrder(WorkOrder order) {
        jdbc.update("UPDATE work_order SET status = ?, work_order_version = ?,"
                        + " started_at = ?, closed_at = ?, cancelled_at = ?, terminated_at = ?,"
                        + " snapshot_last_reading_id = ?, snapshot_last_sampled_at = ?,"
                        + " snapshot_last_cumulative_minutes = ?, updated_at = ?"
                        + " WHERE work_order_key = ?",
                order.status().name(), order.workOrderVersion(),
                utc(order.startedAt()), utc(order.closedAt()), utc(order.cancelledAt()),
                utc(order.terminatedAt()),
                order.snapshotLastReadingId(), utc(order.snapshotLastSampledAt()),
                order.snapshotLastCumulativeMinutes(), utc(order.updatedAt()),
                order.workOrderKey());
    }

    public List<WorkOrder> listByEquipment(String equipmentId) {
        return jdbc.query(
                "SELECT " + SELECT_COLUMNS + " FROM work_order"
                        + " WHERE equipment_id = ? ORDER BY created_at ASC, work_order_key ASC",
                WORK_ORDER_MAPPER, equipmentId);
    }
}
