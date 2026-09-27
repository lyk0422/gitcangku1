package com.example.starter.maintenance.store;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.maintenance.domain.Equipment;
import com.example.starter.maintenance.domain.IsolationConfirmation;
import com.example.starter.maintenance.domain.IsolationLockPoint;
import com.example.starter.maintenance.domain.IsolationOrder;
import com.example.starter.maintenance.domain.MaintenanceRecord;
import com.example.starter.maintenance.domain.Reading;
import com.example.starter.maintenance.domain.RejectedReading;

/**
 * 设备工时保养数据访问层。所有 SQL 参数化；时刻字段（TIMESTAMP WITH TIME ZONE）以 UTC 存取。
 */
@Repository
public class EquipmentRepository {

    private final JdbcTemplate jdbc;

    public EquipmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant readInstant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }

    /** 可空时刻列读取：SQL NULL 映射为 null，保持空值语义。 */
    private static Instant readInstantNullable(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static final RowMapper<Equipment> EQUIPMENT_MAPPER = (rs, rowNum) -> new Equipment(
            rs.getString("equipment_id"),
            rs.getLong("maintenance_period_minutes"),
            rs.getLong("version"),
            rs.getString("state"));

    private static final RowMapper<Reading> READING_MAPPER = (rs, rowNum) -> new Reading(
            rs.getString("equipment_id"),
            rs.getString("reading_id"),
            readInstant(rs, "sampled_at"),
            rs.getLong("cumulative_minutes"),
            rs.getInt("revision_no"));

    private static final RowMapper<MaintenanceRecord> MAINTENANCE_MAPPER = (rs, rowNum) -> new MaintenanceRecord(
            rs.getLong("maintenance_id"),
            rs.getString("equipment_id"),
            rs.getString("reading_id"),
            rs.getInt("anchor_revision_no"),
            readInstant(rs, "anchor_sampled_at"),
            rs.getLong("anchor_cumulative_minutes"),
            readInstant(rs, "completed_at"));

    // ---------- 设备 ----------

    public void insertEquipment(String equipmentId, long maintenancePeriodMinutes, Instant createdAt) {
        jdbc.update("INSERT INTO equipment (equipment_id, maintenance_period_minutes, version, state, created_at)"
                        + " VALUES (?, ?, 1, 'ACTIVE', ?)",
                equipmentId, maintenancePeriodMinutes, utc(createdAt));
    }

    public Optional<Equipment> findEquipment(String equipmentId) {
        List<Equipment> rows = jdbc.query(
                "SELECT equipment_id, maintenance_period_minutes, version, state FROM equipment"
                        + " WHERE equipment_id = ?",
                EQUIPMENT_MAPPER, equipmentId);
        return rows.stream().findFirst();
    }

    /** 悲观行锁：同一设备的写操作串行化，保证版本校验与业务变更原子。 */
    public Optional<Equipment> findEquipmentForUpdate(String equipmentId) {
        List<Equipment> rows = jdbc.query(
                "SELECT equipment_id, maintenance_period_minutes, version, state FROM equipment"
                        + " WHERE equipment_id = ? FOR UPDATE",
                EQUIPMENT_MAPPER, equipmentId);
        return rows.stream().findFirst();
    }

    /** 批量按主键顺序加行锁，避免多设备事务间的锁等待环（死锁）。 */
    public List<Equipment> lockEquipmentsInIdOrder(List<String> equipmentIds) {
        List<String> sorted = equipmentIds.stream().distinct().sorted().toList();
        if (sorted.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", sorted.stream().map(id -> "?").toList());
        return jdbc.query(
                "SELECT equipment_id, maintenance_period_minutes, version, state FROM equipment"
                        + " WHERE equipment_id IN (" + placeholders + ") ORDER BY equipment_id ASC FOR UPDATE",
                EQUIPMENT_MAPPER, sorted.toArray());
    }

    public void incrementVersion(String equipmentId) {
        jdbc.update("UPDATE equipment SET version = version + 1 WHERE equipment_id = ?", equipmentId);
    }

    public void decommissionEquipment(String equipmentId, Instant decommissionedAt) {
        jdbc.update("UPDATE equipment SET state = 'DECOMMISSIONED', decommissioned_at = ?"
                + " WHERE equipment_id = ?", utc(decommissionedAt), equipmentId);
    }

    // ---------- 能源隔离令 ----------

    public long insertIsolationOrder(IsolationOrder order) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO isolation_order (equipment_id, state, created_by, created_at,"
                            + " effective_at, released_at, release_reason, released_by)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, order.equipmentId());
            ps.setString(2, order.state());
            ps.setString(3, order.createdBy());
            ps.setObject(4, utc(order.createdAt()));
            ps.setObject(5, order.effectiveAt() == null ? null : utc(order.effectiveAt()));
            ps.setObject(6, order.releasedAt() == null ? null : utc(order.releasedAt()));
            ps.setString(7, order.releaseReason());
            ps.setString(8, order.releasedBy());
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("隔离令主键生成失败");
        }
        return key.longValue();
    }

    public Optional<IsolationOrder> findIsolationOrder(long isolationId) {
        return queryIsolation("WHERE isolation_id = ?", isolationId).stream().findFirst();
    }

    /** 设备最近一条隔离令（含已解除），按创建时刻与主键倒序。 */
    public Optional<IsolationOrder> findLatestIsolationOrder(String equipmentId) {
        return queryIsolation("WHERE equipment_id = ? ORDER BY created_at DESC, isolation_id DESC"
                + " LIMIT 1", equipmentId).stream().findFirst();
    }

    /** 当前未解除（PENDING 或 ACTIVE）的隔离令；同一设备至多一条。 */
    public Optional<IsolationOrder> findOpenIsolationOrder(String equipmentId) {
        return queryIsolation("WHERE equipment_id = ? AND state IN ('PENDING','ACTIVE')"
                + " ORDER BY created_at DESC, isolation_id DESC LIMIT 1", equipmentId).stream().findFirst();
    }

    /** 当前生效（ACTIVE）的隔离令；判断读数是否被拒使用左闭右开区间。 */
    public Optional<IsolationOrder> findActiveIsolationOrder(String equipmentId) {
        return queryIsolation("WHERE equipment_id = ? AND state = 'ACTIVE'"
                + " ORDER BY created_at DESC, isolation_id DESC LIMIT 1", equipmentId).stream().findFirst();
    }

    public List<IsolationOrder> listIsolationOrders(String equipmentId) {
        return queryIsolation("WHERE equipment_id = ? ORDER BY created_at ASC, isolation_id ASC", equipmentId);
    }

    private List<IsolationOrder> queryIsolation(String whereClause, Object... args) {
        return jdbc.query(
                "SELECT isolation_id, equipment_id, state, created_by, created_at, effective_at,"
                        + " released_at, release_reason, released_by FROM isolation_order " + whereClause,
                ISOLATION_MAPPER, args);
    }

    private static final RowMapper<IsolationOrder> ISOLATION_MAPPER = (rs, rowNum) -> new IsolationOrder(
            rs.getLong("isolation_id"),
            rs.getString("equipment_id"),
            rs.getString("state"),
            rs.getString("created_by"),
            readInstant(rs, "created_at"),
            readInstantNullable(rs, "effective_at"),
            readInstantNullable(rs, "released_at"),
            rs.getString("release_reason"),
            rs.getString("released_by"));

    // ---------- 锁定点 ----------

    public void insertLockPoint(long isolationId, String lockPointId, String label, int seqNo,
                                Instant lockedAt) {
        jdbc.update("INSERT INTO isolation_lock_point (isolation_id, lock_point_id, label, seq_no,"
                        + " locked_at) VALUES (?, ?, ?, ?, ?)",
                isolationId, lockPointId, label, seqNo, utc(lockedAt));
    }

    public Optional<IsolationLockPoint> findLockPoint(long isolationId, String lockPointId) {
        return jdbc.query(
                "SELECT id, isolation_id, lock_point_id, label, seq_no, release_reason, locked_at,"
                        + " released_at, released_by FROM isolation_lock_point"
                        + " WHERE isolation_id = ? AND lock_point_id = ?",
                LOCK_POINT_MAPPER, isolationId, lockPointId).stream().findFirst();
    }

    public List<IsolationLockPoint> listLockPoints(long isolationId) {
        return jdbc.query(
                "SELECT id, isolation_id, lock_point_id, label, seq_no, release_reason, locked_at,"
                        + " released_at, released_by FROM isolation_lock_point"
                        + " WHERE isolation_id = ? ORDER BY seq_no ASC, lock_point_id ASC",
                LOCK_POINT_MAPPER, isolationId);
    }

    /** 解除单个锁定点：仅当当前未解除时更新一行，返回受影响行数。 */
    public int releaseLockPoint(long isolationId, String lockPointId, String reason,
                                String releasedBy, Instant releasedAt) {
        return jdbc.update("UPDATE isolation_lock_point SET release_reason = ?, released_by = ?,"
                        + " released_at = ? WHERE isolation_id = ? AND lock_point_id = ? AND released_at IS NULL",
                reason, releasedBy, utc(releasedAt), isolationId, lockPointId);
    }

    private static final RowMapper<IsolationLockPoint> LOCK_POINT_MAPPER = (rs, rowNum) ->
            new IsolationLockPoint(
                    rs.getLong("id"),
                    rs.getLong("isolation_id"),
                    rs.getString("lock_point_id"),
                    rs.getString("label"),
                    rs.getInt("seq_no"),
                    rs.getString("release_reason"),
                    readInstant(rs, "locked_at"),
                    readInstantNullable(rs, "released_at"),
                    rs.getString("released_by"));

    // ---------- 确认 ----------

    public void insertConfirmation(IsolationConfirmation confirmation) {
        jdbc.update("INSERT INTO isolation_confirmation (isolation_id, operator_id, role, request_id,"
                        + " seq_no, confirmed_at) VALUES (?, ?, ?, ?, ?, ?)",
                confirmation.isolationId(), confirmation.operatorId(), confirmation.role(),
                confirmation.requestId(), confirmation.seqNo(), utc(confirmation.confirmedAt()));
    }

    public List<IsolationConfirmation> listConfirmations(long isolationId) {
        return jdbc.query(
                "SELECT id, isolation_id, operator_id, role, request_id, seq_no, confirmed_at"
                        + " FROM isolation_confirmation WHERE isolation_id = ?"
                        + " ORDER BY seq_no ASC, id ASC",
                CONFIRMATION_MAPPER, isolationId);
    }

    public Optional<IsolationConfirmation> findConfirmationByRequestId(String requestId) {
        return jdbc.query(
                "SELECT id, isolation_id, operator_id, role, request_id, seq_no, confirmed_at"
                        + " FROM isolation_confirmation WHERE request_id = ?",
                CONFIRMATION_MAPPER, requestId).stream().findFirst();
    }

    private static final RowMapper<IsolationConfirmation> CONFIRMATION_MAPPER = (rs, rowNum) ->
            new IsolationConfirmation(
                    rs.getLong("id"),
                    rs.getLong("isolation_id"),
                    rs.getString("operator_id"),
                    rs.getString("role"),
                    rs.getString("request_id"),
                    rs.getInt("seq_no"),
                    readInstant(rs, "confirmed_at"));

    /** 隔离令生效：写入生效时刻（UTC）。 */
    public void activateIsolation(long isolationId, Instant effectiveAt) {
        jdbc.update("UPDATE isolation_order SET state = 'ACTIVE', effective_at = ?"
                + " WHERE isolation_id = ? AND state = 'PENDING'", utc(effectiveAt), isolationId);
    }

    /** 全部锁定点解除后收尾隔离令：状态 RELEASED，固化解除原因快照与登记人。 */
    public void markIsolationReleased(long isolationId, String reasonsJson, String releasedBy,
                                      Instant releasedAt) {
        jdbc.update("UPDATE isolation_order SET state = 'RELEASED', released_at = ?,"
                        + " release_reason = ?, released_by = ? WHERE isolation_id = ?",
                utc(releasedAt), reasonsJson, releasedBy, isolationId);
    }

    // ---------- 被拒读数诊断 ----------

    public void insertRejectedReading(RejectedReading rejected) {
        jdbc.update("INSERT INTO rejected_reading (equipment_id, isolation_id, reading_id, sampled_at,"
                        + " cumulative_minutes, operator_id, request_id, lock_points_json, rejected_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                rejected.equipmentId(), rejected.isolationId(), rejected.readingId(),
                utc(rejected.sampledAt()), rejected.cumulativeMinutes(),
                rejected.operatorId(), rejected.requestId(), rejected.lockPointsJson(),
                utc(rejected.rejectedAt()));
    }

    public List<RejectedReading> listRejectedReadings(String equipmentId) {
        return jdbc.query(
                "SELECT rejected_id, equipment_id, isolation_id, reading_id, sampled_at,"
                        + " cumulative_minutes, operator_id, request_id, lock_points_json, rejected_at"
                        + " FROM rejected_reading WHERE equipment_id = ?"
                        + " ORDER BY rejected_at ASC, rejected_id ASC",
                REJECTED_MAPPER, equipmentId);
    }

    private static final RowMapper<RejectedReading> REJECTED_MAPPER = (rs, rowNum) -> new RejectedReading(
            rs.getLong("rejected_id"),
            rs.getString("equipment_id"),
            rs.getLong("isolation_id"),
            rs.getString("reading_id"),
            readInstant(rs, "sampled_at"),
            rs.getLong("cumulative_minutes"),
            rs.getString("operator_id"),
            rs.getString("request_id"),
            rs.getString("lock_points_json"),
            readInstant(rs, "rejected_at"));

    // ---------- 读数 ----------

    public void insertReading(Reading reading, Instant createdAt) {
        jdbc.update("INSERT INTO reading (equipment_id, reading_id, sampled_at, cumulative_minutes,"
                        + " revision_no, created_at, updated_at) VALUES (?, ?, ?, ?, 1, ?, ?)",
                reading.equipmentId(), reading.readingId(), utc(reading.sampledAt()),
                reading.cumulativeMinutes(), utc(createdAt), utc(createdAt));
    }

    public Optional<Reading> findReading(String equipmentId, String readingId) {
        List<Reading> rows = jdbc.query(
                "SELECT equipment_id, reading_id, sampled_at, cumulative_minutes, revision_no"
                        + " FROM reading WHERE equipment_id = ? AND reading_id = ?",
                READING_MAPPER, equipmentId, readingId);
        return rows.stream().findFirst();
    }

    public Optional<Reading> findReadingAt(String equipmentId, Instant sampledAt) {
        List<Reading> rows = jdbc.query(
                "SELECT equipment_id, reading_id, sampled_at, cumulative_minutes, revision_no"
                        + " FROM reading WHERE equipment_id = ? AND sampled_at = ?",
                READING_MAPPER, equipmentId, utc(sampledAt));
        return rows.stream().findFirst();
    }

    /** 采样时刻严格早于给定时刻的最近一条读数。 */
    public Optional<Reading> findPrevReading(String equipmentId, Instant sampledAt) {
        List<Reading> rows = jdbc.query(
                "SELECT equipment_id, reading_id, sampled_at, cumulative_minutes, revision_no"
                        + " FROM reading WHERE equipment_id = ? AND sampled_at < ?"
                        + " ORDER BY sampled_at DESC LIMIT 1",
                READING_MAPPER, equipmentId, utc(sampledAt));
        return rows.stream().findFirst();
    }

    /** 采样时刻严格晚于给定时刻的最近一条读数。 */
    public Optional<Reading> findNextReading(String equipmentId, Instant sampledAt) {
        List<Reading> rows = jdbc.query(
                "SELECT equipment_id, reading_id, sampled_at, cumulative_minutes, revision_no"
                        + " FROM reading WHERE equipment_id = ? AND sampled_at > ?"
                        + " ORDER BY sampled_at ASC LIMIT 1",
                READING_MAPPER, equipmentId, utc(sampledAt));
        return rows.stream().findFirst();
    }

    public Optional<Reading> findLatestReading(String equipmentId) {
        List<Reading> rows = jdbc.query(
                "SELECT equipment_id, reading_id, sampled_at, cumulative_minutes, revision_no"
                        + " FROM reading WHERE equipment_id = ? ORDER BY sampled_at DESC LIMIT 1",
                READING_MAPPER, equipmentId);
        return rows.stream().findFirst();
    }

    public void updateReadingValue(String equipmentId, String readingId, long cumulativeMinutes,
                                   int newRevisionNo, Instant updatedAt) {
        jdbc.update("UPDATE reading SET cumulative_minutes = ?, revision_no = ?, updated_at = ?"
                        + " WHERE equipment_id = ? AND reading_id = ?",
                cumulativeMinutes, newRevisionNo, utc(updatedAt), equipmentId, readingId);
    }

    public List<Reading> listReadings(String equipmentId) {
        return jdbc.query(
                "SELECT equipment_id, reading_id, sampled_at, cumulative_minutes, revision_no"
                        + " FROM reading WHERE equipment_id = ? ORDER BY sampled_at ASC, reading_id ASC",
                READING_MAPPER, equipmentId);
    }

    // ---------- 修订历史 ----------

    public void insertRevision(String equipmentId, String readingId, int revisionNo,
                               long cumulativeMinutes, String requestId, Instant createdAt) {
        jdbc.update("INSERT INTO reading_revision (equipment_id, reading_id, revision_no,"
                        + " cumulative_minutes, request_id, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                equipmentId, readingId, revisionNo, cumulativeMinutes, requestId, utc(createdAt));
    }

    public List<RevisionRow> listRevisions(String equipmentId, String readingId) {
        return jdbc.query(
                "SELECT revision_no, cumulative_minutes, request_id, created_at FROM reading_revision"
                        + " WHERE equipment_id = ? AND reading_id = ? ORDER BY revision_no ASC",
                (rs, rowNum) -> new RevisionRow(
                        rs.getInt("revision_no"),
                        rs.getLong("cumulative_minutes"),
                        rs.getString("request_id"),
                        readInstant(rs, "created_at")),
                equipmentId, readingId);
    }

    public record RevisionRow(int revisionNo, long cumulativeMinutes, String requestId, Instant createdAt) {
    }

    // ---------- 保养记录 ----------

    public long insertMaintenance(String equipmentId, String readingId, int anchorRevisionNo,
                                  Instant anchorSampledAt, long anchorCumulativeMinutes,
                                  String requestId, Instant completedAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO maintenance (equipment_id, reading_id, anchor_revision_no,"
                            + " anchor_sampled_at, anchor_cumulative_minutes, request_id, completed_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, equipmentId);
            ps.setString(2, readingId);
            ps.setInt(3, anchorRevisionNo);
            ps.setObject(4, utc(anchorSampledAt));
            ps.setLong(5, anchorCumulativeMinutes);
            ps.setString(6, requestId);
            ps.setObject(7, utc(completedAt));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("保养记录主键生成失败");
        }
        return key.longValue();
    }

    /** 最近一次保养（锚点时间最大者；锚点时间严格递增，故唯一）。 */
    public Optional<MaintenanceRecord> findLastMaintenance(String equipmentId) {
        List<MaintenanceRecord> rows = jdbc.query(
                "SELECT maintenance_id, equipment_id, reading_id, anchor_revision_no,"
                        + " anchor_sampled_at, anchor_cumulative_minutes, completed_at"
                        + " FROM maintenance WHERE equipment_id = ?"
                        + " ORDER BY anchor_sampled_at DESC, maintenance_id DESC LIMIT 1",
                MAINTENANCE_MAPPER, equipmentId);
        return rows.stream().findFirst();
    }

    public List<MaintenanceRecord> listMaintenances(String equipmentId) {
        return jdbc.query(
                "SELECT maintenance_id, equipment_id, reading_id, anchor_revision_no,"
                        + " anchor_sampled_at, anchor_cumulative_minutes, completed_at"
                        + " FROM maintenance WHERE equipment_id = ?"
                        + " ORDER BY anchor_sampled_at ASC, maintenance_id ASC",
                MAINTENANCE_MAPPER, equipmentId);
    }

    /** 该读数是否已被任意历史保养记录锚定（锚定后不可修订）。 */
    public boolean existsMaintenanceAnchoringReading(String equipmentId, String readingId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance WHERE equipment_id = ? AND reading_id = ?",
                Integer.class, equipmentId, readingId);
        return count != null && count > 0;
    }

    // ---------- 幂等去重 ----------

    public Optional<IdempotencyRow> findIdempotency(String requestId) {
        List<IdempotencyRow> rows = jdbc.query(
                "SELECT request_id, operation, request_fingerprint, response_body"
                        + " FROM idempotency_request WHERE request_id = ?",
                (rs, rowNum) -> new IdempotencyRow(
                        rs.getString("request_id"),
                        rs.getString("operation"),
                        rs.getString("request_fingerprint"),
                        rs.getString("response_body")),
                requestId);
        return rows.stream().findFirst();
    }

    public void insertIdempotency(String requestId, String operation, String fingerprint,
                                  String responseBody, Instant createdAt) {
        jdbc.update("INSERT INTO idempotency_request (request_id, operation, request_fingerprint,"
                        + " response_body, created_at) VALUES (?, ?, ?, ?, ?)",
                requestId, operation, fingerprint, responseBody, utc(createdAt));
    }

    public record IdempotencyRow(String requestId, String operation, String fingerprint, String responseBody) {
    }
}
