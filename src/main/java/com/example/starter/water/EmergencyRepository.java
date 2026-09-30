package com.example.starter.water;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

/**
 * 紧急储备数据访问层（JdbcTemplate，参数化 SQL）。
 *
 * <p>与普通配水账本物理隔离：仅访问 emergency_* 与 reserve_account 表；
 * 行锁顺序统一为 supply_window → reserve_account → emergency_request，避免与普通分配及归还/核销互锁。</p>
 */
@Repository
public class EmergencyRepository {

    /** 紧急事件行。 */
    public record EventRow(String eventKey, String title, String status, long createdNanos,
                           Long invalidatedNanos) {
    }

    /** 储备账户行（单例 id=1）。 */
    public record ReserveRow(BigDecimal totalVolume, BigDecimal remainingVolume, long updatedNanos) {
    }

    /** 紧急启用请求行。 */
    public record RequestRow(long id, String requestId, String eventKey, long windowId, BigDecimal amount,
                             BigDecimal writtenOffAmount, BigDecimal returnedAmount, String status,
                             long createdNanos, Long activatedNanos, long updatedNanos) {

        /** 当前未结清量 = 首次启用数量 - 已核销 - 已归还。 */
        public BigDecimal outstanding() {
            return amount.subtract(writtenOffAmount).subtract(returnedAmount);
        }
    }

    /** 双角色确认记录行，创建后不可变。 */
    public record ConfirmationRow(long id, String requestId, int seq, String roleCode, String confirmer,
                                  long createdNanos) {
    }

    /** 紧急储备不可变流水行。 */
    public record FlowRow(long id, String requestId, String eventKey, long windowId, String flowType,
                          BigDecimal amount, String actor, long createdNanos) {
    }

    private static final RowMapper<EventRow> EVENT_MAPPER = (rs, n) -> new EventRow(
            rs.getString("event_key"), rs.getString("title"), rs.getString("status"),
            rs.getLong("created_nanos"),
            rs.getObject("invalidated_nanos") == null ? null : rs.getLong("invalidated_nanos"));

    private static final RowMapper<ReserveRow> RESERVE_MAPPER = (rs, n) -> new ReserveRow(
            rs.getBigDecimal("total_volume"), rs.getBigDecimal("remaining_volume"),
            rs.getLong("updated_nanos"));

    private static final RowMapper<RequestRow> REQUEST_MAPPER = (rs, n) -> new RequestRow(
            rs.getLong("id"), rs.getString("request_id"), rs.getString("event_key"),
            rs.getLong("window_id"), rs.getBigDecimal("amount"), rs.getBigDecimal("written_off_amount"),
            rs.getBigDecimal("returned_amount"), rs.getString("status"), rs.getLong("created_nanos"),
            rs.getObject("activated_nanos") == null ? null : rs.getLong("activated_nanos"),
            rs.getLong("updated_nanos"));

    private static final String REQUEST_COLUMNS =
            "SELECT id, request_id, event_key, window_id, amount, written_off_amount, returned_amount,"
                    + " status, created_nanos, activated_nanos, updated_nanos FROM emergency_request";

    private static final RowMapper<ConfirmationRow> CONFIRMATION_MAPPER = (rs, n) -> new ConfirmationRow(
            rs.getLong("id"), rs.getString("request_id"), rs.getInt("seq"), rs.getString("role_code"),
            rs.getString("confirmer"), rs.getLong("created_nanos"));

    private static final RowMapper<FlowRow> FLOW_MAPPER = (rs, n) -> new FlowRow(
            rs.getLong("id"), rs.getString("request_id"), rs.getString("event_key"),
            rs.getLong("window_id"), rs.getString("flow_type"), rs.getBigDecimal("amount"),
            rs.getString("actor"), rs.getLong("created_nanos"));

    private final JdbcTemplate jdbc;

    public EmergencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 插入紧急事件（VALID）。 */
    public void insertEvent(String eventKey, String title, long createdNanos) {
        jdbc.update("INSERT INTO emergency_event (event_key, title, status, created_nanos, invalidated_nanos)"
                + " VALUES (?, ?, 'VALID', ?, NULL)", eventKey, title, createdNanos);
    }

    /** 按业务键查询事件，不存在返回 null。 */
    public EventRow findEventByKey(String eventKey) {
        try {
            return jdbc.queryForObject(
                    "SELECT event_key, title, status, created_nanos, invalidated_nanos"
                            + " FROM emergency_event WHERE event_key = ?", EVENT_MAPPER, eventKey);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 按业务键锁定事件行（FOR UPDATE），不存在返回 null。 */
    public EventRow lockEventByKey(String eventKey) {
        try {
            return jdbc.queryForObject(
                    "SELECT event_key, title, status, created_nanos, invalidated_nanos"
                            + " FROM emergency_event WHERE event_key = ? FOR UPDATE", EVENT_MAPPER, eventKey);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 作废事件：VALID → INVALID。 */
    public void invalidateEvent(String eventKey, long invalidatedNanos) {
        jdbc.update("UPDATE emergency_event SET status = 'INVALID', invalidated_nanos = ? WHERE event_key = ?",
                invalidatedNanos, eventKey);
    }

    /** 锁定唯一储备账户行（FOR UPDATE）。 */
    public ReserveRow lockReserve() {
        return jdbc.queryForObject(
                "SELECT total_volume, remaining_volume, updated_nanos FROM reserve_account WHERE id = 1 FOR UPDATE",
                RESERVE_MAPPER);
    }

    /** 查询唯一储备账户行（不加锁）。 */
    public ReserveRow findReserve() {
        try {
            return jdbc.queryForObject(
                    "SELECT total_volume, remaining_volume, updated_nanos FROM reserve_account WHERE id = 1",
                    RESERVE_MAPPER);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 储备扣减（启用预占），不得为负由事务内校验与 CHECK 约束双重保证。 */
    public void decrementReserve(BigDecimal amount, long updatedNanos) {
        jdbc.update("UPDATE reserve_account SET remaining_volume = remaining_volume - ?, updated_nanos = ?"
                + " WHERE id = 1", amount, updatedNanos);
    }

    /** 储备回补（归还）。 */
    public void incrementReserve(BigDecimal amount, long updatedNanos) {
        jdbc.update("UPDATE reserve_account SET remaining_volume = remaining_volume + ?, updated_nanos = ?"
                + " WHERE id = 1", amount, updatedNanos);
    }

    /** 插入启用请求（PENDING，未扣储备），返回主键。 */
    public long insertRequest(String requestId, String eventKey, long windowId, BigDecimal amount,
                              long createdNanos) {
        jdbc.update("INSERT INTO emergency_request (request_id, event_key, window_id, amount,"
                + " written_off_amount, returned_amount, status, created_nanos, activated_nanos, updated_nanos)"
                + " VALUES (?, ?, ?, ?, 0, 0, 'PENDING', ?, NULL, ?)",
                requestId, eventKey, windowId, amount, createdNanos, createdNanos);
        Long id = jdbc.queryForObject("SELECT id FROM emergency_request WHERE request_id = ?",
                Long.class, requestId);
        return id == null ? 0L : id;
    }

    /** 按 requestId 查询启用请求，不存在返回 null。 */
    public RequestRow findRequestByRequestId(String requestId) {
        try {
            return jdbc.queryForObject(REQUEST_COLUMNS + " WHERE request_id = ?", REQUEST_MAPPER, requestId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 按 requestId 锁定启用请求行（FOR UPDATE），不存在返回 null。 */
    public RequestRow lockRequestByRequestId(String requestId) {
        try {
            return jdbc.queryForObject(REQUEST_COLUMNS + " WHERE request_id = ? FOR UPDATE",
                    REQUEST_MAPPER, requestId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 第二位确认成功：PENDING → ACTIVE，记录启用时间。 */
    public void activateRequest(String requestId, long activatedNanos) {
        jdbc.update("UPDATE emergency_request SET status = 'ACTIVE', activated_nanos = ?, updated_nanos = ?"
                + " WHERE request_id = ?", activatedNanos, activatedNanos, requestId);
    }

    /** 累加核销数量（不回补储备）。 */
    public void addWrittenOff(String requestId, BigDecimal delta, long updatedNanos) {
        jdbc.update("UPDATE emergency_request SET written_off_amount = written_off_amount + ?,"
                + " updated_nanos = ? WHERE request_id = ?", delta, updatedNanos, requestId);
    }

    /** 累加归还数量（回补储备）。 */
    public void addReturned(String requestId, BigDecimal delta, long updatedNanos) {
        jdbc.update("UPDATE emergency_request SET returned_amount = returned_amount + ?,"
                + " updated_nanos = ? WHERE request_id = ?", delta, updatedNanos, requestId);
    }

    /** 未结清量归零：ACTIVE → SETTLED。 */
    public void settleRequest(String requestId, long updatedNanos) {
        jdbc.update("UPDATE emergency_request SET status = 'SETTLED', updated_nanos = ?"
                + " WHERE request_id = ? AND written_off_amount + returned_amount >= amount",
                updatedNanos, requestId);
    }

    /** 插入一条不可变确认记录。 */
    public void insertConfirmation(String requestId, int seq, String roleCode, String confirmer,
                                   long createdNanos) {
        jdbc.update("INSERT INTO emergency_confirmation (request_id, seq, role_code, confirmer, created_nanos)"
                + " VALUES (?, ?, ?, ?, ?)", requestId, seq, roleCode, confirmer, createdNanos);
    }

    /** 启用单的全部确认记录，按确认次序升序。 */
    public List<ConfirmationRow> listConfirmations(String requestId) {
        return jdbc.query(
                "SELECT id, request_id, seq, role_code, confirmer, created_nanos"
                        + " FROM emergency_confirmation WHERE request_id = ? ORDER BY seq",
                CONFIRMATION_MAPPER, requestId);
    }

    /** 插入一条不可变储备流水。 */
    public void insertFlow(String requestId, String eventKey, long windowId, String flowType,
                           BigDecimal amount, String actor, long createdNanos) {
        jdbc.update("INSERT INTO emergency_flow (request_id, event_key, window_id, flow_type, amount, actor,"
                + " created_nanos) VALUES (?, ?, ?, ?, ?, ?, ?)",
                requestId, eventKey, windowId, flowType, amount, actor, createdNanos);
    }

    /** 某启用单的全部流水，按主键升序。 */
    public List<FlowRow> listFlowsByRequest(String requestId) {
        return jdbc.query(
                "SELECT id, request_id, event_key, window_id, flow_type, amount, actor, created_nanos"
                        + " FROM emergency_flow WHERE request_id = ? ORDER BY id",
                FLOW_MAPPER, requestId);
    }

    /** 某目标窗口的全部紧急流水，按主键升序。 */
    public List<FlowRow> listFlowsByWindow(long windowId) {
        return jdbc.query(
                "SELECT id, request_id, event_key, window_id, flow_type, amount, actor, created_nanos"
                        + " FROM emergency_flow WHERE window_id = ? ORDER BY id",
                FLOW_MAPPER, windowId);
    }

    /** 全库某类型流水总量，无则 0。 */
    public BigDecimal sumFlowByType(String flowType) {
        BigDecimal sum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM emergency_flow WHERE flow_type = ?",
                BigDecimal.class, flowType);
        return sum == null ? BigDecimal.ZERO : sum;
    }

    /** 某窗口全部 ACTIVE 启用单（仍有未结清量），按主键升序。 */
    public List<RequestRow> listActiveRequestsByWindow(long windowId) {
        return jdbc.query(REQUEST_COLUMNS + " WHERE window_id = ? AND status = 'ACTIVE' ORDER BY id",
                REQUEST_MAPPER, windowId);
    }

    /** 某事件的全部启用请求（含 PENDING），按主键升序。 */
    public List<RequestRow> listRequestsByEvent(String eventKey) {
        return jdbc.query(REQUEST_COLUMNS + " WHERE event_key = ? ORDER BY id",
                REQUEST_MAPPER, eventKey);
    }

    /** 某窗口紧急水未结清量合计：ACTIVATION - WRITEOFF - RETURN，无则 0。 */
    public BigDecimal sumOutstandingByWindow(long windowId) {
        BigDecimal sum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount - written_off_amount - returned_amount), 0)"
                        + " FROM emergency_request WHERE window_id = ? AND status = 'ACTIVE'",
                BigDecimal.class, windowId);
        return sum == null ? BigDecimal.ZERO : sum;
    }
}
