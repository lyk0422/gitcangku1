package com.example.starter.plan;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 日计划、占用、幂等记录与区段日锁的 JDBC 持久层。
 * 时刻字段以 UTC 墙钟时间存取，避免会话时区影响。
 */
@Repository
public class PlanRepository {

    /**
     * 计划头行。
     *
     * @param id 主键
     * @param scheduleKey 计划业务键
     * @param operatingDate 运营日期（Asia/Shanghai 日历日）
     * @param version 当前版本
     * @param status 计划状态
     */
    public record PlanRow(long id, String scheduleKey, LocalDate operatingDate, long version, PlanStatus status) {
    }

    /**
     * 占用行。
     *
     * @param planId 所属计划主键
     * @param trainNo 列车编号
     * @param sectionId 区段 ID
     * @param startUtc 开始时刻（UTC，左闭）
     * @param endUtc 结束时刻（UTC，右开）
     */
    public record OccupancyRow(long planId, String trainNo, String sectionId, Instant startUtc, Instant endUtc) {
    }

    /**
     * 幂等记录行。
     *
     * @param requestKey 幂等键
     * @param operation 操作类型
     * @param requestHash 请求参数摘要
     * @param responseStatus 首次成功响应状态码，未完成为 null
     * @param responseBody 首次成功响应体，未完成为 null
     */
    public record IdemRow(String requestKey, String operation, String requestHash,
                          Integer responseStatus, String responseBody) {
    }

    /**
     * 已发布时隙行（含来源计划键）。
     *
     * @param scheduleKey 来源计划业务键
     * @param trainNo 列车编号
     * @param sectionId 区段 ID
     * @param startUtc 开始时刻（UTC）
     * @param endUtc 结束时刻（UTC）
     */
    public record SlotRow(String scheduleKey, String trainNo, String sectionId, Instant startUtc, Instant endUtc) {
    }

    private static final RowMapper<PlanRow> PLAN_MAPPER = (rs, n) -> new PlanRow(
            rs.getLong("id"),
            rs.getString("schedule_key"),
            rs.getObject("operating_date", LocalDate.class),
            rs.getLong("version"),
            PlanStatus.valueOf(rs.getString("status")));

    private static final RowMapper<OccupancyRow> OCCUPANCY_MAPPER = (rs, n) -> new OccupancyRow(
            rs.getLong("plan_id"),
            rs.getString("train_no"),
            rs.getString("section_id"),
            toInstant(rs, "start_utc"),
            toInstant(rs, "end_utc"));

    private static final RowMapper<SlotRow> SLOT_MAPPER = (rs, n) -> new SlotRow(
            rs.getString("schedule_key"),
            rs.getString("train_no"),
            rs.getString("section_id"),
            toInstant(rs, "start_utc"),
            toInstant(rs, "end_utc"));

    private final JdbcTemplate jdbc;

    public PlanRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Instant toInstant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, LocalDateTime.class).toInstant(ZoneOffset.UTC);
    }

    private static LocalDateTime toUtc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);
    }

    /**
     * 按业务键查询计划头。
     */
    public Optional<PlanRow> findPlanByKey(String scheduleKey) {
        List<PlanRow> rows = jdbc.query(
                "SELECT id, schedule_key, operating_date, version, status FROM rail_plan WHERE schedule_key = ?",
                PLAN_MAPPER, scheduleKey);
        return rows.stream().findFirst();
    }

    /**
     * 插入新草稿计划，版本为 1，返回生成主键。
     */
    public long insertPlan(String scheduleKey, LocalDate operatingDate) {
        LocalDateTime now = nowUtc();
        jdbc.update(
                "INSERT INTO rail_plan (schedule_key, operating_date, version, status, created_at, updated_at)"
                        + " VALUES (?, ?, 1, 'DRAFT', ?, ?)",
                scheduleKey, operatingDate, now, now);
        Long id = jdbc.queryForObject("SELECT id FROM rail_plan WHERE schedule_key = ?", Long.class, scheduleKey);
        return id;
    }

    /**
     * 整体替换计划占用：删除旧占用并按顺序写入新占用，版本加一。
     */
    public void replaceOccupancies(long planId, long newVersion, List<OccupancyInput> occupancies) {
        jdbc.update("DELETE FROM rail_plan_occupancy WHERE plan_id = ?", planId);
        insertOccupancies(planId, occupancies);
        jdbc.update("UPDATE rail_plan SET version = ?, updated_at = ? WHERE id = ?", newVersion, nowUtc(), planId);
    }

    /**
     * 按计划内顺序写入占用清单。
     */
    public void insertOccupancies(long planId, List<OccupancyInput> occupancies) {
        for (int i = 0; i < occupancies.size(); i++) {
            OccupancyInput o = occupancies.get(i);
            jdbc.update(
                    "INSERT INTO rail_plan_occupancy (plan_id, seq, train_no, section_id, start_utc, end_utc)"
                            + " VALUES (?, ?, ?, ?, ?, ?)",
                    planId, i, o.trainNo(), o.sectionId(), toUtc(o.startUtc()), toUtc(o.endUtc()));
        }
    }

    /**
     * 查询计划全部占用（按顺序号）。
     */
    public List<OccupancyRow> findOccupancies(long planId) {
        return jdbc.query(
                "SELECT plan_id, train_no, section_id, start_utc, end_utc FROM rail_plan_occupancy"
                        + " WHERE plan_id = ? ORDER BY seq",
                OCCUPANCY_MAPPER, planId);
    }

    /**
     * 更新计划状态（发布或取消）。
     */
    public void updateStatus(long planId, PlanStatus status) {
        jdbc.update("UPDATE rail_plan SET status = ?, updated_at = ? WHERE id = ?", status.name(), nowUtc(), planId);
    }

    /**
     * 查询指定运营日、指定区段上其他已发布计划的全部占用（用于发布冲突校验）。
     */
    public List<SlotRow> findPublishedSlotsOnSections(LocalDate operatingDate, List<String> sectionIds,
                                                      long excludePlanId) {
        if (sectionIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(", ", sectionIds.stream().map(s -> "?").toList());
        return jdbc.query(
                "SELECT p.schedule_key, o.train_no, o.section_id, o.start_utc, o.end_utc"
                        + " FROM rail_plan_occupancy o JOIN rail_plan p ON p.id = o.plan_id"
                        + " WHERE p.status = 'PUBLISHED' AND p.operating_date = ? AND p.id <> ?"
                        + " AND o.section_id IN (" + placeholders + ")",
                SLOT_MAPPER, concat(operatingDate, excludePlanId, sectionIds));
    }

    private static Object[] concat(LocalDate date, long excludePlanId, List<String> sectionIds) {
        Object[] args = new Object[2 + sectionIds.size()];
        args[0] = date;
        args[1] = excludePlanId;
        for (int i = 0; i < sectionIds.size(); i++) {
            args[2 + i] = sectionIds.get(i);
        }
        return args;
    }

    /**
     * 查询指定运营日、指定区段当前已发布的时隙，按开始时刻排序。
     */
    public List<SlotRow> findPublishedSlots(LocalDate operatingDate, String sectionId) {
        return jdbc.query(
                "SELECT p.schedule_key, o.train_no, o.section_id, o.start_utc, o.end_utc"
                        + " FROM rail_plan_occupancy o JOIN rail_plan p ON p.id = o.plan_id"
                        + " WHERE p.status = 'PUBLISHED' AND p.operating_date = ? AND o.section_id = ?"
                        + " ORDER BY o.start_utc, o.train_no",
                SLOT_MAPPER, operatingDate, sectionId);
    }

    /**
     * 确保区段-日期锁行存在（已存在时忽略唯一键冲突）。
     */
    public void ensureSectionDayLock(LocalDate operatingDate, String sectionId) {
        try {
            jdbc.update("INSERT INTO rail_section_day_lock (operating_date, section_id) VALUES (?, ?)",
                    operatingDate, sectionId);
        } catch (org.springframework.dao.DuplicateKeyException ignored) {
            // 锁行已存在，无需处理
        }
    }

    /**
     * 对区段-日期锁行加行级写锁，串行化同一区段日的并发发布。
     */
    public void lockSectionDay(LocalDate operatingDate, String sectionId) {
        jdbc.queryForObject(
                "SELECT section_id FROM rail_section_day_lock WHERE operating_date = ? AND section_id = ? FOR UPDATE",
                String.class, operatingDate, sectionId);
    }

    /**
     * 按幂等键查询记录。
     */
    public Optional<IdemRow> findIdem(String requestKey) {
        List<IdemRow> rows = jdbc.query(
                "SELECT request_key, operation, request_hash, response_status, response_body"
                        + " FROM rail_idempotency WHERE request_key = ?",
                (rs, n) -> new IdemRow(
                        rs.getString("request_key"),
                        rs.getString("operation"),
                        rs.getString("request_hash"),
                        (Integer) rs.getObject("response_status"),
                        rs.getString("response_body")),
                requestKey);
        return rows.stream().findFirst();
    }

    /**
     * 写入幂等占位记录；键已存在时抛出 DuplicateKeyException。
     */
    public void insertIdemPending(String requestKey, String operation, String requestHash) {
        jdbc.update(
                "INSERT INTO rail_idempotency (request_key, operation, request_hash, response_status, response_body, created_at)"
                        + " VALUES (?, ?, ?, NULL, NULL, ?)",
                requestKey, operation, requestHash, nowUtc());
    }

    /**
     * 回填幂等记录的首次成功响应。
     */
    public void completeIdem(String requestKey, int responseStatus, String responseBody) {
        jdbc.update("UPDATE rail_idempotency SET response_status = ?, response_body = ? WHERE request_key = ?",
                responseStatus, responseBody, requestKey);
    }
}
