package com.example.starter.plan.repo;

import com.example.starter.plan.model.CapacitySwap;
import com.example.starter.plan.model.CapacitySwapItem;
import com.example.starter.plan.model.CapacitySwapPhase;
import com.example.starter.plan.model.CapacitySwapSnapshot;
import com.example.starter.plan.model.CapacitySwapStatus;
import com.example.starter.plan.model.SwapSegment;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 容量交换单、交换项与前后快照的 JDBC 持久化。快照一经写入不可变、不删除。
 */
@Repository
public class SwapRepository {

    private static final String SWAP_COLUMNS =
            "id, swap_key, op_date, status, request_hash, preview_conflicts, created_at, activated_at";

    private final JdbcTemplate jdbc;

    public SwapRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 创建预览交换单（状态 PREVIEW），返回自增主键；swap_key 唯一冲突由上层裁决。
     */
    public long insertSwap(String swapKey, LocalDate opDate, String requestHash,
                           String previewConflictsJson, long nowMillis) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rail_capacity_swap"
                            + " (swap_key, op_date, status, request_hash, preview_conflicts,"
                            + " created_at, activated_at) VALUES (?, ?, 'PREVIEW', ?, ?, ?, NULL)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, swapKey);
            ps.setDate(2, Date.valueOf(opDate));
            ps.setString(3, requestHash);
            ps.setString(4, previewConflictsJson);
            ps.setLong(5, nowMillis);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按业务键查询交换单（不加锁）。
     */
    public Optional<CapacitySwap> findSwapByKey(String swapKey) {
        return jdbc.query("SELECT " + SWAP_COLUMNS + " FROM rail_capacity_swap WHERE swap_key = ?",
                (rs, n) -> mapSwap(rs), swapKey).stream().findFirst();
    }

    /**
     * 按业务键查询交换单并加行级写锁，须在事务内调用。
     */
    public Optional<CapacitySwap> findSwapByKeyForUpdate(String swapKey) {
        return jdbc.query("SELECT " + SWAP_COLUMNS
                        + " FROM rail_capacity_swap WHERE swap_key = ? FOR UPDATE",
                (rs, n) -> mapSwap(rs), swapKey).stream().findFirst();
    }

    /**
     * 追加交换项；返回自增主键。
     */
    public long insertItem(long swapId, int itemSeq, long planId, String scheduleKey,
                           int expectedVersion) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rail_capacity_swap_item"
                            + " (swap_id, item_seq, plan_id, schedule_key, expected_version, final_version)"
                            + " VALUES (?, ?, ?, ?, ?, NULL)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, swapId);
            ps.setInt(2, itemSeq);
            ps.setLong(3, planId);
            ps.setString(4, scheduleKey);
            ps.setInt(5, expectedVersion);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 批量追加不可变快照占用。
     */
    public void insertSnapshots(List<CapacitySwapSnapshot> snapshots) {
        if (snapshots.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(
                "INSERT INTO rail_capacity_swap_snapshot"
                        + " (swap_id, plan_id, item_seq, phase, occ_seq, train_no, section_id,"
                        + " start_utc, end_utc) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                snapshots, snapshots.size(),
                (ps, s) -> {
                    ps.setLong(1, s.swapId());
                    ps.setLong(2, s.planId());
                    ps.setInt(3, s.itemSeq());
                    ps.setString(4, s.phase().name());
                    ps.setInt(5, s.occSeq());
                    ps.setString(6, s.trainNo());
                    ps.setString(7, s.sectionId());
                    ps.setLong(8, s.startUtc().toEpochMilli());
                    ps.setLong(9, s.endUtc().toEpochMilli());
                });
    }

    /**
     * 批量追加预览冻结的提交占用段。
     */
    public void insertSegments(List<SwapSegment> segments) {
        if (segments.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(
                "INSERT INTO rail_capacity_swap_segment"
                        + " (swap_id, item_seq, phase, occ_seq, section_id, start_utc, end_utc)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                segments, segments.size(),
                (ps, s) -> {
                    ps.setLong(1, s.swapId());
                    ps.setInt(2, s.itemSeq());
                    ps.setString(3, s.phase().name());
                    ps.setInt(4, s.occSeq());
                    ps.setString(5, s.sectionId());
                    ps.setLong(6, s.startUtc().toEpochMilli());
                    ps.setLong(7, s.endUtc().toEpochMilli());
                });
    }

    /**
     * 查询预览冻结的全部提交段，按 (item_seq, phase, occ_seq) 稳定排序。
     */
    public List<SwapSegment> findSegments(long swapId) {
        return jdbc.query("SELECT swap_id, item_seq, phase, occ_seq, section_id, start_utc, end_utc"
                        + " FROM rail_capacity_swap_segment WHERE swap_id = ?"
                        + " ORDER BY item_seq, phase, occ_seq",
                (rs, n) -> new SwapSegment(
                        rs.getLong("swap_id"),
                        rs.getInt("item_seq"),
                        CapacitySwapPhase.valueOf(rs.getString("phase")),
                        rs.getInt("occ_seq"),
                        rs.getString("section_id"),
                        java.time.Instant.ofEpochMilli(rs.getLong("start_utc")),
                        java.time.Instant.ofEpochMilli(rs.getLong("end_utc"))),
                swapId);
    }

    /**
     * 查询交换项，按稳定排序序号升序。
     */
    public List<CapacitySwapItem> findItems(long swapId) {
        return jdbc.query("SELECT id, swap_id, item_seq, plan_id, schedule_key, expected_version,"
                        + " final_version FROM rail_capacity_swap_item WHERE swap_id = ?"
                        + " ORDER BY item_seq",
                (rs, n) -> new CapacitySwapItem(
                        rs.getLong("id"),
                        rs.getLong("swap_id"),
                        rs.getInt("item_seq"),
                        rs.getLong("plan_id"),
                        rs.getString("schedule_key"),
                        rs.getInt("expected_version"),
                        (Integer) rs.getObject("final_version")),
                swapId);
    }

    /**
     * 查询交换单全部快照，按 (item_seq, phase, occ_seq) 稳定排序（BEFORE 排在 AFTER 前）。
     */
    public List<CapacitySwapSnapshot> findSnapshots(long swapId) {
        return jdbc.query("SELECT id, swap_id, plan_id, item_seq, phase, occ_seq, train_no,"
                        + " section_id, start_utc, end_utc FROM rail_capacity_swap_snapshot"
                        + " WHERE swap_id = ? ORDER BY item_seq, phase, occ_seq",
                (rs, n) -> new CapacitySwapSnapshot(
                        rs.getLong("id"),
                        rs.getLong("swap_id"),
                        rs.getLong("plan_id"),
                        rs.getInt("item_seq"),
                        CapacitySwapPhase.valueOf(rs.getString("phase")),
                        rs.getInt("occ_seq"),
                        rs.getString("train_no"),
                        rs.getString("section_id"),
                        java.time.Instant.ofEpochMilli(rs.getLong("start_utc")),
                        java.time.Instant.ofEpochMilli(rs.getLong("end_utc"))),
                swapId);
    }

    /**
     * 将交换单置为 ACTIVATED 并写入激活时刻。
     */
    public void markActivated(long swapId, long nowMillis) {
        jdbc.update("UPDATE rail_capacity_swap SET status = 'ACTIVATED', activated_at = ?"
                + " WHERE id = ?", nowMillis, swapId);
    }

    /**
     * 回写单个交换项的激活后版本。
     */
    public void updateItemFinalVersion(long itemId, int finalVersion) {
        jdbc.update("UPDATE rail_capacity_swap_item SET final_version = ? WHERE id = ?",
                finalVersion, itemId);
    }

    private CapacitySwap mapSwap(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new CapacitySwap(
                rs.getLong("id"),
                rs.getString("swap_key"),
                rs.getObject("op_date", LocalDate.class),
                CapacitySwapStatus.valueOf(rs.getString("status")),
                rs.getString("request_hash"),
                rs.getString("preview_conflicts"),
                rs.getLong("created_at"),
                (Long) rs.getObject("activated_at"));
    }
}
