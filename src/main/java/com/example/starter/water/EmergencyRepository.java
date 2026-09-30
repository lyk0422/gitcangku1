package com.example.starter.water;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Objects;

/**
 * 紧急储备水数据访问层（JdbcTemplate，参数化 SQL）。
 *
 * <p>与 {@link WaterRepository} 共用同一数据源与供水窗口表，但读写各自独立的紧急储备表，
 * 普通配水仓储不访问这些表，保证储备不可被普通排班直接读取。</p>
 *
 * <p>时间一律为 UTC 纳秒时间戳；水量为 DECIMAL(19,3)，单位立方米。</p>
 */
@Repository
public class EmergencyRepository {

    /** 紧急事件行。status：ACTIVE 有效 / CANCELLED 已失效。 */
    public record EventRow(long id, String eventKey, String title, String description, String status,
                          long createdNanos, Long cancelledNanos) {
    }

    /** 储备总账单行（id 恒为 1）；reservedVolume 为已转入窗口且未归还的总量。 */
    public record ReserveRow(BigDecimal totalVolume, BigDecimal reservedVolume, long updatedNanos) {
    }

    /** 紧急启用审批行；数量计数器满足 writtenOff + returned <= volume。 */
    public record ActivationRow(long id, String requestId, String eventKey, long windowId, BigDecimal volume,
                                BigDecimal writtenOffVolume, BigDecimal returnedVolume, String confirmerA,
                                String confirmerB, String status, long createdNanos) {

        /** 窗口中尚未核销且未归还的数量（可归还/可核销上限）。 */
        public BigDecimal outstanding() {
            return volume.subtract(writtenOffVolume).subtract(returnedVolume);
        }
    }

    /** 核销/归还不可变流水行。flowType：WRITE_OFF / RETURN。 */
    public record FlowRow(long id, String requestId, String eventKey, long windowId, String flowType,
                          BigDecimal volume, String actor, long createdNanos) {
    }

    private static final RowMapper<EventRow> EVENT_MAPPER = (rs, n) -> new EventRow(
            rs.getLong("id"), rs.getString("event_key"), rs.getString("title"), rs.getString("description"),
            rs.getString("status"), rs.getLong("created_nanos"),
            rs.getObject("cancelled_nanos") == null ? null : rs.getLong("cancelled_nanos"));

    private static final RowMapper<ReserveRow> RESERVE_MAPPER = (rs, n) -> new ReserveRow(
            rs.getBigDecimal("total_volume"), rs.getBigDecimal("reserved_volume"),
            rs.getLong("updated_nanos"));

    private static final RowMapper<ActivationRow> ACTIVATION_MAPPER = (rs, n) -> new ActivationRow(
            rs.getLong("id"), rs.getString("request_id"), rs.getString("event_key"),
            rs.getLong("window_id"), rs.getBigDecimal("volume"),
            rs.getBigDecimal("written_off_volume"), rs.getBigDecimal("returned_volume"),
            rs.getString("confirmer_a"), rs.getString("confirmer_b"),
            rs.getString("status"), rs.getLong("created_nanos"));

    private static final String ACTIVATION_SELECT =
            "SELECT id, request_id, event_key, window_id, volume, written_off_volume, returned_volume,"
                    + " confirmer_a, confirmer_b, status, created_nanos";

    private static final RowMapper<FlowRow> FLOW_MAPPER = (rs, n) -> new FlowRow(
            rs.getLong("id"), rs.getString("request_id"), rs.getString("event_key"),
            rs.getLong("window_id"), rs.getString("flow_type"),
            rs.getBigDecimal("volume"), rs.getString("actor"), rs.getLong("created_nanos"));

    private final JdbcTemplate jdbc;

    public EmergencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------
    // 紧急事件
    // ------------------------------------------------------------------

    /** 登记紧急事件（ACTIVE）并返回主键。 */
    public long insertEvent(String eventKey, String title, String description, long createdNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO emergency_event (event_key, title, description, status, created_nanos, cancelled_nanos)"
                            + " VALUES (?, ?, ?, 'ACTIVE', ?, NULL)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, eventKey);
            ps.setString(2, title);
            ps.setString(3, description);
            ps.setLong(4, createdNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 按业务键查询紧急事件，不存在返回 null。 */
    public EventRow findEvent(String eventKey) {
        try {
            return jdbc.queryForObject(
                    "SELECT id, event_key, title, description, status, created_nanos, cancelled_nanos"
                            + " FROM emergency_event WHERE event_key = ?", EVENT_MAPPER, eventKey);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 按业务键锁定紧急事件行（FOR UPDATE）。 */
    public EventRow lockEvent(String eventKey) {
        try {
            return jdbc.queryForObject(
                    "SELECT id, event_key, title, description, status, created_nanos, cancelled_nanos"
                            + " FROM emergency_event WHERE event_key = ? FOR UPDATE", EVENT_MAPPER, eventKey);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 将事件置为 CANCELLED 并记录失效时间。 */
    public void cancelEvent(long id, long cancelledNanos) {
        jdbc.update("UPDATE emergency_event SET status = 'CANCELLED', cancelled_nanos = ? WHERE id = ?",
                cancelledNanos, id);
    }

    // ------------------------------------------------------------------
    // 储备总账
    // ------------------------------------------------------------------

    /** 锁定并读取储备总账单行（FOR UPDATE），串行化全部划转裁决。 */
    public ReserveRow lockReserve() {
        return jdbc.queryForObject(
                "SELECT total_volume, reserved_volume, updated_nanos FROM reserve_ledger WHERE id = 1 FOR UPDATE",
                RESERVE_MAPPER);
    }

    /** 读取储备总账（不加锁，用于查询）。 */
    public ReserveRow findReserve() {
        return jdbc.queryForObject(
                "SELECT total_volume, reserved_volume, updated_nanos FROM reserve_ledger WHERE id = 1",
                RESERVE_MAPPER);
    }

    /** 启用：增加已预占数量（不得超过总量由事务内校验与 CHECK 共同保证）。 */
    public void increaseReserved(BigDecimal delta, long updatedNanos) {
        jdbc.update("UPDATE reserve_ledger SET reserved_volume = reserved_volume + ?, updated_nanos = ?"
                + " WHERE id = 1", delta, updatedNanos);
    }

    /** 归还：减少已预占数量（不得为负由事务内校验与 CHECK 共同保证）。 */
    public void decreaseReserved(BigDecimal delta, long updatedNanos) {
        jdbc.update("UPDATE reserve_ledger SET reserved_volume = reserved_volume - ?, updated_nanos = ?"
                + " WHERE id = 1", delta, updatedNanos);
    }

    // ------------------------------------------------------------------
    // 启用审批
    // ------------------------------------------------------------------

    /** 插入启用审批并返回主键。 */
    public long insertActivation(String requestId, String eventKey, long windowId, BigDecimal volume,
                                 String confirmerA, String confirmerB, long createdNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO reserve_activation (request_id, event_key, window_id, volume,"
                            + " written_off_volume, returned_volume, confirmer_a, confirmer_b, status, created_nanos)"
                            + " VALUES (?, ?, ?, ?, 0, 0, ?, ?, 'ACTIVE', ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, requestId);
            ps.setString(2, eventKey);
            ps.setLong(3, windowId);
            ps.setBigDecimal(4, volume);
            ps.setString(5, confirmerA);
            ps.setString(6, confirmerB);
            ps.setLong(7, createdNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 按请求键查询启用审批，不存在返回 null。 */
    public ActivationRow findActivation(String requestId) {
        try {
            return jdbc.queryForObject(
                    ACTIVATION_SELECT + " FROM reserve_activation WHERE request_id = ?",
                    ACTIVATION_MAPPER, requestId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 按请求键锁定启用审批行（FOR UPDATE）。 */
    public ActivationRow lockActivation(String requestId) {
        try {
            return jdbc.queryForObject(
                    ACTIVATION_SELECT + " FROM reserve_activation WHERE request_id = ? FOR UPDATE",
                    ACTIVATION_MAPPER, requestId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 累加核销计数（不超过剩余由事务内校验与 CHECK 保证）。 */
    public void addWrittenOff(long id, BigDecimal delta) {
        jdbc.update("UPDATE reserve_activation SET written_off_volume = written_off_volume + ? WHERE id = ?",
                delta, id);
    }

    /** 累加归还计数（不超过剩余由事务内校验与 CHECK 保证）。 */
    public void addReturned(long id, BigDecimal delta) {
        jdbc.update("UPDATE reserve_activation SET returned_volume = returned_volume + ? WHERE id = ?",
                delta, id);
    }

    /** 窗口全部启用审批（历史记录），按主键升序。 */
    public List<ActivationRow> listActivationsByWindow(long windowId) {
        return jdbc.query(
                ACTIVATION_SELECT + " FROM reserve_activation WHERE window_id = ? ORDER BY id",
                ACTIVATION_MAPPER, windowId);
    }

    /** 事件全部启用审批（历史记录），按主键升序。 */
    public List<ActivationRow> listActivationsByEvent(String eventKey) {
        return jdbc.query(
                ACTIVATION_SELECT + " FROM reserve_activation WHERE event_key = ? ORDER BY id",
                ACTIVATION_MAPPER, eventKey);
    }

    // ------------------------------------------------------------------
    // 核销/归还流水
    // ------------------------------------------------------------------

    /** 插入不可变核销/归还流水并返回主键。 */
    public long insertFlow(String requestId, String eventKey, long windowId, String flowType,
                           BigDecimal volume, String actor, long createdNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO reserve_flow (request_id, event_key, window_id, flow_type, volume, actor, created_nanos)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, requestId);
            ps.setString(2, eventKey);
            ps.setLong(3, windowId);
            ps.setString(4, flowType);
            ps.setBigDecimal(5, volume);
            ps.setString(6, actor);
            ps.setLong(7, createdNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 窗口全部核销/归还流水，按主键升序。 */
    public List<FlowRow> listFlowsByWindow(long windowId) {
        return jdbc.query(
                "SELECT id, request_id, event_key, window_id, flow_type, volume, actor, created_nanos"
                        + " FROM reserve_flow WHERE window_id = ? ORDER BY id", FLOW_MAPPER, windowId);
    }

    /** 事件全部核销/归还流水，按主键升序。 */
    public List<FlowRow> listFlowsByEvent(String eventKey) {
        return jdbc.query(
                "SELECT id, request_id, event_key, window_id, flow_type, volume, actor, created_nanos"
                        + " FROM reserve_flow WHERE event_key = ? ORDER BY id", FLOW_MAPPER, eventKey);
    }
}
