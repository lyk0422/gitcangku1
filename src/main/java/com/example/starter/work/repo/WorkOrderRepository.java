package com.example.starter.work.repo;

import com.example.starter.work.model.WorkCancelRecord;
import com.example.starter.work.model.WorkOrder;
import com.example.starter.work.model.WorkStatus;
import com.example.starter.work.model.WorkWindow;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 区段注册表、施工单、施工单区段关联与取消记录的 JDBC 持久化。
 * 所有时刻以 UTC 毫秒存储，时区无关。
 */
@Repository
public class WorkOrderRepository {

    private static final RowMapper<WorkOrder> WORK_ORDER_MAPPER = (rs, n) -> new WorkOrder(
            rs.getLong("id"),
            rs.getString("work_key"),
            rs.getInt("version"),
            WorkStatus.valueOf(rs.getString("status")),
            rs.getString("operator_name"),
            Instant.ofEpochMilli(rs.getLong("start_utc")),
            Instant.ofEpochMilli(rs.getLong("end_utc")));

    private static final RowMapper<WorkWindow> WINDOW_MAPPER = (rs, n) -> new WorkWindow(
            rs.getString("work_key"),
            rs.getString("section_id"),
            Instant.ofEpochMilli(rs.getLong("start_utc")),
            Instant.ofEpochMilli(rs.getLong("end_utc")));

    private static final RowMapper<WorkCancelRecord> CANCEL_MAPPER = (rs, n) -> new WorkCancelRecord(
            rs.getLong("id"),
            rs.getString("work_key"),
            rs.getInt("version"),
            rs.getString("operator_name"),
            Instant.ofEpochMilli(rs.getLong("cancelled_at")));

    private final JdbcTemplate jdbc;

    public WorkOrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 注册区段，返回自增主键；区段 ID 唯一约束冲突时抛出 DuplicateKeyException 由上层裁决。
     */
    public long insertSection(String sectionId, long nowMillis) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rail_section (section_id, created_at) VALUES (?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, sectionId);
            ps.setLong(2, nowMillis);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 区段是否已注册。
     */
    public boolean sectionExists(String sectionId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_section WHERE section_id = ?", Integer.class, sectionId);
        return count != null && count > 0;
    }

    /**
     * 在给定集合中已注册的区段 ID（升序返回，便于稳定差集计算）。
     */
    public List<String> findSections(Collection<String> sectionIds) {
        if (sectionIds.isEmpty()) {
            return List.of();
        }
        StringJoiner placeholders = new StringJoiner(", ");
        sectionIds.forEach(s -> placeholders.add("?"));
        return jdbc.query("SELECT section_id FROM rail_section WHERE section_id IN ("
                        + placeholders + ") ORDER BY section_id",
                (rs, n) -> rs.getString("section_id"), sectionIds.toArray());
    }

    /**
     * 插入施工单（版本 1，状态 ACTIVE），返回自增主键。
     */
    public long insertWorkOrder(String workKey, String operator, Instant startUtc, Instant endUtc,
                                long nowMillis) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rail_work_order"
                            + " (work_key, version, status, operator_name, start_utc, end_utc,"
                            + " created_at, updated_at) VALUES (?, 1, 'ACTIVE', ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, workKey);
            ps.setString(2, operator);
            ps.setLong(3, startUtc.toEpochMilli());
            ps.setLong(4, endUtc.toEpochMilli());
            ps.setLong(5, nowMillis);
            ps.setLong(6, nowMillis);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按业务键查询施工单（不加锁）。
     */
    public Optional<WorkOrder> findByKey(String workKey) {
        return jdbc.query("SELECT id, work_key, version, status, operator_name, start_utc, end_utc"
                        + " FROM rail_work_order WHERE work_key = ?",
                WORK_ORDER_MAPPER, workKey).stream().findFirst();
    }

    /**
     * 按业务键查询施工单并加行级写锁，须在事务内调用，用于串行化同一施工单的修改/取消。
     */
    public Optional<WorkOrder> findByKeyForUpdate(String workKey) {
        return jdbc.query("SELECT id, work_key, version, status, operator_name, start_utc, end_utc"
                        + " FROM rail_work_order WHERE work_key = ? FOR UPDATE",
                WORK_ORDER_MAPPER, workKey).stream().findFirst();
    }

    /**
     * 整体替换施工单区段集合：先删后插，按字典序插入保证存储顺序稳定。
     */
    public void replaceSections(long workOrderId, List<String> sectionIds) {
        jdbc.update("DELETE FROM rail_work_order_section WHERE work_order_id = ?", workOrderId);
        jdbc.batchUpdate(
                "INSERT INTO rail_work_order_section (work_order_id, section_id) VALUES (?, ?)",
                sectionIds, sectionIds.size(),
                (ps, sectionId) -> {
                    ps.setLong(1, workOrderId);
                    ps.setString(2, sectionId);
                });
    }

    /**
     * 查询施工单区段集合，按字典序升序。
     */
    public List<String> findSectionIds(long workOrderId) {
        return jdbc.query("SELECT section_id FROM rail_work_order_section"
                        + " WHERE work_order_id = ? ORDER BY section_id",
                (rs, n) -> rs.getString("section_id"), workOrderId);
    }

    /**
     * 修改施工单窗口与操作者，版本加一由上层计算传入。
     */
    public void updateWorkOrder(long workOrderId, int version, String operator,
                                Instant startUtc, Instant endUtc, long nowMillis) {
        jdbc.update("UPDATE rail_work_order SET version = ?, operator_name = ?,"
                        + " start_utc = ?, end_utc = ?, updated_at = ? WHERE id = ?",
                version, operator, startUtc.toEpochMilli(), endUtc.toEpochMilli(),
                nowMillis, workOrderId);
    }

    /**
     * 仅更新施工单状态（取消不改变版本与窗口）。
     */
    public void updateStatus(long workOrderId, WorkStatus status, long nowMillis) {
        jdbc.update("UPDATE rail_work_order SET status = ?, updated_at = ? WHERE id = ?",
                status.name(), nowMillis, workOrderId);
    }

    /**
     * 查询覆盖给定区段集合的生效（ACTIVE）施工窗口，可排除指定施工单（如修改时的自身）。
     * 结果按 workKey、sectionId 升序，保证冲突列举稳定。
     */
    public List<WorkWindow> findActiveWindows(Collection<String> sectionIds,
                                              Collection<Long> excludeWorkOrderIds) {
        if (sectionIds.isEmpty()) {
            return List.of();
        }
        StringJoiner sectionPlaceholders = new StringJoiner(", ");
        sectionIds.forEach(s -> sectionPlaceholders.add("?"));
        StringBuilder sql = new StringBuilder(
                "SELECT w.work_key, s.section_id, w.start_utc, w.end_utc"
                        + " FROM rail_work_order w"
                        + " JOIN rail_work_order_section s ON s.work_order_id = w.id"
                        + " WHERE w.status = 'ACTIVE'");
        List<Object> args = new ArrayList<>();
        if (!excludeWorkOrderIds.isEmpty()) {
            StringJoiner excludePlaceholders = new StringJoiner(", ");
            excludeWorkOrderIds.forEach(id -> excludePlaceholders.add("?"));
            sql.append(" AND w.id NOT IN (").append(excludePlaceholders).append(')');
            args.addAll(excludeWorkOrderIds);
        }
        sql.append(" AND s.section_id IN (").append(sectionPlaceholders).append(')')
                .append(" ORDER BY w.work_key, s.section_id");
        args.addAll(sectionIds);
        return jdbc.query(sql.toString(), WINDOW_MAPPER, args.toArray());
    }

    /**
     * 追加施工单取消记录（不可变）；唯一约束冲突时抛出 DuplicateKeyException 由上层裁决。
     */
    public void insertCancelRecord(String workKey, int version, String operator, long nowMillis) {
        jdbc.update("INSERT INTO rail_work_cancel_record"
                        + " (work_key, version, operator_name, cancelled_at) VALUES (?, ?, ?, ?)",
                workKey, version, operator, nowMillis);
    }

    /**
     * 按施工单业务键查询取消记录。
     */
    public Optional<WorkCancelRecord> findCancelRecord(String workKey) {
        return jdbc.query("SELECT id, work_key, version, operator_name, cancelled_at"
                        + " FROM rail_work_cancel_record WHERE work_key = ?",
                CANCEL_MAPPER, workKey).stream().findFirst();
    }

    /**
     * 查询全部取消记录，按取消时刻与业务键升序，保证列举稳定。
     */
    public List<WorkCancelRecord> findAllCancelRecords() {
        return jdbc.query("SELECT id, work_key, version, operator_name, cancelled_at"
                        + " FROM rail_work_cancel_record ORDER BY cancelled_at, work_key",
                CANCEL_MAPPER);
    }
}
