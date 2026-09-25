package com.example.starter.workblock.repo;

import com.example.starter.plan.model.PublishedSlot;
import com.example.starter.workblock.model.ActiveWindow;
import com.example.starter.workblock.model.WorkBlock;
import com.example.starter.workblock.model.WorkBlockCancellation;
import com.example.starter.workblock.model.WorkBlockStatus;
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
 * 施工占用窗口、区段集合与取消记录的 JDBC 持久化。所有时刻以 UTC 毫秒存储。
 * 区间相交统一按左闭右开判定：{@code a.start < b.end 且 b.start < a.end}。
 */
@Repository
public class WorkBlockRepository {

    private static final RowMapper<WorkBlock> WORK_BLOCK_MAPPER = (rs, n) -> new WorkBlock(
            rs.getLong("id"),
            rs.getString("work_key"),
            rs.getInt("version"),
            WorkBlockStatus.valueOf(rs.getString("status")),
            Instant.ofEpochMilli(rs.getLong("start_utc")),
            Instant.ofEpochMilli(rs.getLong("end_utc")),
            rs.getString("operator"),
            rs.getLong("created_at"),
            rs.getLong("updated_at"),
            rs.getObject("cancelled_at", Long.class));

    private static final RowMapper<WorkBlockCancellation> CANCELLATION_MAPPER = (rs, n) ->
            new WorkBlockCancellation(
                    rs.getLong("id"),
                    rs.getLong("work_block_id"),
                    rs.getString("work_key"),
                    rs.getInt("version"),
                    rs.getString("operator"),
                    rs.getLong("cancelled_at"));

    private static final RowMapper<ActiveWindow> ACTIVE_WINDOW_MAPPER = (rs, n) ->
            new ActiveWindow(
                    rs.getString("work_key"),
                    rs.getString("section_id"),
                    rs.getLong("start_utc"),
                    rs.getLong("end_utc"));

    private static final RowMapper<PublishedSlot> PUBLISHED_SLOT_MAPPER = (rs, n) ->
            new PublishedSlot(
                    rs.getString("schedule_key"),
                    rs.getString("train_no"),
                    rs.getString("section_id"),
                    Instant.ofEpochMilli(rs.getLong("start_utc")),
                    Instant.ofEpochMilli(rs.getLong("end_utc")));

    private final JdbcTemplate jdbc;

    public WorkBlockRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入施工单（版本 1，状态 ACTIVE），返回自增主键。
     */
    public long insertWorkBlock(String workKey, Instant startUtc, Instant endUtc,
                                String operator, long nowMillis) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rail_work_block (work_key, version, status, start_utc, end_utc,"
                            + " operator, created_at, updated_at, cancelled_at)"
                            + " VALUES (?, 1, 'ACTIVE', ?, ?, ?, ?, ?, NULL)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, workKey);
            ps.setLong(2, startUtc.toEpochMilli());
            ps.setLong(3, endUtc.toEpochMilli());
            ps.setString(4, operator);
            ps.setLong(5, nowMillis);
            ps.setLong(6, nowMillis);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按业务键查询施工单（不加锁）。
     */
    public Optional<WorkBlock> findByKey(String workKey) {
        return jdbc.query(selectWorkBlock() + " WHERE work_key = ?",
                WORK_BLOCK_MAPPER, workKey).stream().findFirst();
    }

    /**
     * 按业务键查询施工单并加行级写锁，须在事务内调用。
     */
    public Optional<WorkBlock> findByKeyForUpdate(String workKey) {
        return jdbc.query(selectWorkBlock() + " WHERE work_key = ? FOR UPDATE",
                WORK_BLOCK_MAPPER, workKey).stream().findFirst();
    }

    /**
     * 按主键查询施工单（不加锁）。
     */
    public Optional<WorkBlock> findById(long id) {
        return jdbc.query(selectWorkBlock() + " WHERE id = ?",
                WORK_BLOCK_MAPPER, id).stream().findFirst();
    }

    /**
     * 查询全部施工单，按业务键字典序。
     */
    public List<WorkBlock> findAll() {
        return jdbc.query(selectWorkBlock() + " ORDER BY work_key", WORK_BLOCK_MAPPER);
    }

    /**
     * 整体替换区段集合：先删后插，seq 为规范化序号。
     */
    public void replaceSections(long workBlockId, List<String> sortedSectionIds) {
        jdbc.update("DELETE FROM rail_work_block_section WHERE work_block_id = ?", workBlockId);
        if (sortedSectionIds.isEmpty()) {
            return;
        }
        List<Object[]> rows = new ArrayList<>(sortedSectionIds.size());
        for (int i = 0; i < sortedSectionIds.size(); i++) {
            rows.add(new Object[] {workBlockId, i, sortedSectionIds.get(i)});
        }
        jdbc.batchUpdate(
                "INSERT INTO rail_work_block_section (work_block_id, seq, section_id)"
                        + " VALUES (?, ?, ?)",
                rows);
    }

    /**
     * 查询施工单区段集合，按规范化序号升序。
     */
    public List<String> findSectionIds(long workBlockId) {
        return new ArrayList<>(jdbc.queryForList(
                "SELECT section_id FROM rail_work_block_section WHERE work_block_id = ?"
                        + " ORDER BY seq",
                String.class, workBlockId));
    }

    /**
     * 修改窗口时段并推进版本。
     */
    public void updateWindow(long workBlockId, int version, Instant startUtc, Instant endUtc,
                             long nowMillis) {
        jdbc.update("UPDATE rail_work_block SET version = ?, start_utc = ?, end_utc = ?,"
                        + " updated_at = ? WHERE id = ?",
                version, startUtc.toEpochMilli(), endUtc.toEpochMilli(), nowMillis, workBlockId);
    }

    /**
     * 取消施工单：状态置 CANCELLED，记录取消时刻，版本不变。
     */
    public void markCancelled(long workBlockId, long nowMillis) {
        jdbc.update("UPDATE rail_work_block SET status = 'CANCELLED', cancelled_at = ?,"
                        + " updated_at = ? WHERE id = ?",
                nowMillis, nowMillis, workBlockId);
    }

    /**
     * 追加不可变取消记录。
     */
    public void insertCancellation(long workBlockId, String workKey, int version,
                                   String operator, long nowMillis) {
        jdbc.update("INSERT INTO rail_work_block_cancellation"
                        + " (work_block_id, work_key, version, operator, cancelled_at)"
                        + " VALUES (?, ?, ?, ?, ?)",
                workBlockId, workKey, version, operator, nowMillis);
    }

    /**
     * 按施工单业务键查询取消记录。
     */
    public Optional<WorkBlockCancellation> findCancellationByWorkKey(String workKey) {
        return jdbc.query("SELECT id, work_block_id, work_key, version, operator, cancelled_at"
                        + " FROM rail_work_block_cancellation WHERE work_key = ?",
                CANCELLATION_MAPPER, workKey).stream().findFirst();
    }

    /**
     * 查询全部取消记录，按取消时刻升序、主键次序兜底。
     */
    public List<WorkBlockCancellation> findAllCancellations() {
        return jdbc.query("SELECT id, work_block_id, work_key, version, operator, cancelled_at"
                + " FROM rail_work_block_cancellation ORDER BY cancelled_at, id",
                CANCELLATION_MAPPER);
    }

    /**
     * 查询与给定区段集合、时段相交的其他生效窗口投影（每个相交区段一行）。
     * 结果按 workKey、sectionId 稳定排序，供重叠冲突稳定列示。
     */
    public List<ActiveWindow> findOverlappingActiveWindows(Collection<String> sectionIds,
                                                           long startMillis, long endMillis,
                                                           long excludeWorkBlockId) {
        if (sectionIds.isEmpty()) {
            return List.of();
        }
        StringJoiner placeholders = new StringJoiner(", ");
        sectionIds.forEach(s -> placeholders.add("?"));
        List<Object> args = new ArrayList<>();
        args.add(excludeWorkBlockId);
        args.add(endMillis);
        args.add(startMillis);
        args.addAll(sectionIds);
        return jdbc.query(
                "SELECT w.work_key, s.section_id, w.start_utc, w.end_utc"
                        + " FROM rail_work_block w"
                        + " JOIN rail_work_block_section s ON s.work_block_id = w.id"
                        + " WHERE w.status = 'ACTIVE' AND w.id <> ?"
                        + " AND w.start_utc < ? AND w.end_utc > ?"
                        + " AND s.section_id IN (" + placeholders + ")"
                        + " ORDER BY w.work_key, s.section_id",
                ACTIVE_WINDOW_MAPPER, args.toArray());
    }

    /**
     * 查询全部生效窗口投影（每个生效施工单的每个区段一行），按 workKey、sectionId 排序。
     */
    public List<ActiveWindow> findAllActiveWindows() {
        return jdbc.query(
                "SELECT w.work_key, s.section_id, w.start_utc, w.end_utc"
                        + " FROM rail_work_block w"
                        + " JOIN rail_work_block_section s ON s.work_block_id = w.id"
                        + " WHERE w.status = 'ACTIVE' ORDER BY w.work_key, s.section_id",
                ACTIVE_WINDOW_MAPPER);
    }

    /**
     * 查询与指定窗口区段集合、时段相交的全部已发布计划占用，
     * 按区段、计划、开始时刻稳定排序（首行即首个受影响占用）。
     */
    public List<PublishedSlot> findPublishedOccupancies(Instant windowStart, Instant windowEnd,
                                                        Collection<String> sectionIds) {
        if (sectionIds.isEmpty()) {
            return List.of();
        }
        StringJoiner placeholders = new StringJoiner(", ");
        sectionIds.forEach(s -> placeholders.add("?"));
        List<Object> args = new ArrayList<>();
        args.add(windowEnd.toEpochMilli());
        args.add(windowStart.toEpochMilli());
        args.addAll(sectionIds);
        return jdbc.query(
                "SELECT p.schedule_key, o.train_no, o.section_id, o.start_utc, o.end_utc"
                        + " FROM rail_plan_occupancy o JOIN rail_day_plan p ON p.id = o.plan_id"
                        + " WHERE p.status = 'PUBLISHED'"
                        + " AND o.start_utc < ? AND o.end_utc > ?"
                        + " AND o.section_id IN (" + placeholders + ")"
                        + " ORDER BY o.section_id, p.schedule_key, o.start_utc, o.train_no",
                PUBLISHED_SLOT_MAPPER, args.toArray());
    }

    /**
     * 获取计划发布/施工写入全局互斥锁（单行 FOR UPDATE），
     * 串行化所有按事务提交顺序裁决的写操作。
     */
    public void acquireGlobalLock() {
        jdbc.queryForObject("SELECT id FROM publish_lock WHERE id = 1 FOR UPDATE", Integer.class);
    }

    private String selectWorkBlock() {
        return "SELECT id, work_key, version, status, start_utc, end_utc, operator,"
                + " created_at, updated_at, cancelled_at FROM rail_work_block";
    }
}
