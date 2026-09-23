package com.example.starter.plan.repo;

import com.example.starter.plan.model.CapacitySwap;
import com.example.starter.plan.model.CapacitySwapItem;
import com.example.starter.plan.model.CapacitySwapSnapshot;
import com.example.starter.plan.model.SwapStatus;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 容量交换单及其参与项、不可变快照的 JDBC 持久化。
 */
@Repository
public class CapacitySwapRepository {

    private static final RowMapper<CapacitySwap> SWAP_MAPPER = (rs, n) -> new CapacitySwap(
            rs.getLong("id"),
            rs.getString("swap_key"),
            rs.getObject("op_date", LocalDate.class),
            SwapStatus.valueOf(rs.getString("status")),
            rs.getInt("item_count"),
            rs.getString("request_hash"),
            rs.getLong("created_at"),
            (Long) rs.getObject("activated_at"));

    private static final RowMapper<CapacitySwapItem> ITEM_MAPPER = (rs, n) -> new CapacitySwapItem(
            rs.getLong("swap_id"),
            rs.getInt("item_seq"),
            rs.getLong("plan_id"),
            rs.getString("schedule_key"),
            rs.getInt("expected_version"),
            rs.getString("current_json"),
            rs.getString("target_json"));

    private static final RowMapper<CapacitySwapSnapshot> SNAPSHOT_MAPPER = (rs, n) ->
            new CapacitySwapSnapshot(
                    rs.getLong("id"),
                    rs.getLong("swap_id"),
                    rs.getString("phase"),
                    rs.getInt("item_seq"),
                    rs.getLong("plan_id"),
                    rs.getString("schedule_key"),
                    rs.getInt("version"),
                    rs.getString("occupancies_json"),
                    rs.getLong("created_at"));

    private final JdbcTemplate jdbc;

    public CapacitySwapRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入交换单主记录（PREVIEW），返回自增主键；swapKey 唯一冲突时由上层裁决。
     */
    public long insertSwap(String swapKey, LocalDate opDate, int itemCount, String requestHash,
                           long nowMillis) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rail_capacity_swap"
                            + " (swap_key, op_date, status, item_count, request_hash, created_at)"
                            + " VALUES (?, ?, 'PREVIEW', ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, swapKey);
            ps.setDate(2, Date.valueOf(opDate));
            ps.setInt(3, itemCount);
            ps.setString(4, requestHash);
            ps.setLong(5, nowMillis);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按业务键查询交换单（不加锁）。
     */
    public Optional<CapacitySwap> findByKey(String swapKey) {
        return jdbc.query("SELECT id, swap_key, op_date, status, item_count, request_hash,"
                        + " created_at, activated_at FROM rail_capacity_swap WHERE swap_key = ?",
                SWAP_MAPPER, swapKey).stream().findFirst();
    }

    /**
     * 按主键查询交换单（不加锁）。
     */
    public Optional<CapacitySwap> findById(long swapId) {
        return jdbc.query("SELECT id, swap_key, op_date, status, item_count, request_hash,"
                        + " created_at, activated_at FROM rail_capacity_swap WHERE id = ?",
                SWAP_MAPPER, swapId).stream().findFirst();
    }

    /**
     * 按业务键查询交换单并加行级写锁，须在事务内调用。
     */
    public Optional<CapacitySwap> findByKeyForUpdate(String swapKey) {
        return jdbc.query("SELECT id, swap_key, op_date, status, item_count, request_hash,"
                        + " created_at, activated_at FROM rail_capacity_swap WHERE swap_key = ? FOR UPDATE",
                SWAP_MAPPER, swapKey).stream().findFirst();
    }

    /**
     * 插入一条参与项。
     */
    public void insertItem(long swapId, int itemSeq, long planId, String scheduleKey,
                           int expectedVersion, String currentJson, String targetJson) {
        jdbc.update("INSERT INTO rail_capacity_swap_item"
                        + " (swap_id, item_seq, plan_id, schedule_key, expected_version,"
                        + " current_json, target_json) VALUES (?, ?, ?, ?, ?, ?, ?)",
                swapId, itemSeq, planId, scheduleKey, expectedVersion, currentJson, targetJson);
    }

    /**
     * 查询交换单全部参与项，按 item_seq 升序（稳定排序）。
     */
    public List<CapacitySwapItem> findItems(long swapId) {
        return jdbc.query("SELECT swap_id, item_seq, plan_id, schedule_key, expected_version,"
                        + " current_json, target_json FROM rail_capacity_swap_item"
                        + " WHERE swap_id = ? ORDER BY item_seq",
                ITEM_MAPPER, swapId);
    }

    /**
     * 写入一条交换前/后不可变快照。
     */
    public void insertSnapshot(long swapId, String phase, int itemSeq, long planId,
                               String scheduleKey, int version, String occupanciesJson,
                               long nowMillis) {
        jdbc.update("INSERT INTO rail_capacity_swap_snapshot"
                        + " (swap_id, phase, item_seq, plan_id, schedule_key, version,"
                        + " occupancies_json, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                swapId, phase, itemSeq, planId, scheduleKey, version, occupanciesJson, nowMillis);
    }

    /**
     * 查询交换单全部快照，按 phase（BEFORE 先于 AFTER）与 item_seq 稳定排序。
     */
    public List<CapacitySwapSnapshot> findSnapshots(long swapId) {
        return jdbc.query("SELECT id, swap_id, phase, item_seq, plan_id, schedule_key, version,"
                        + " occupancies_json, created_at FROM rail_capacity_swap_snapshot"
                        + " WHERE swap_id = ? ORDER BY phase, item_seq",
                SNAPSHOT_MAPPER, swapId);
    }

    /**
     * 将交换单标记为 ACTIVE 并记录激活时刻。
     */
    public void markActive(long swapId, long nowMillis) {
        jdbc.update("UPDATE rail_capacity_swap SET status = 'ACTIVE', activated_at = ? WHERE id = ?",
                nowMillis, swapId);
    }
}
