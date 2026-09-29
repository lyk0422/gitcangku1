package com.example.starter.plan.repo;

import com.example.starter.plan.model.BlockClearanceAction;
import com.example.starter.plan.model.WorkBlock;
import com.example.starter.plan.model.WorkBlockAction;
import com.example.starter.plan.model.WorkBlockStatus;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
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
 * 区段维护封锁与处理结果的 JDBC 持久化。窗口时刻以 UTC 毫秒存储，左闭右开。
 */
@Repository
public class WorkBlockRepository {

    private static final RowMapper<WorkBlock> BLOCK_MAPPER = (rs, n) -> new WorkBlock(
            rs.getLong("id"),
            rs.getString("block_key"),
            rs.getObject("op_date", LocalDate.class),
            rs.getString("section_id"),
            Instant.ofEpochMilli(rs.getLong("start_utc")),
            Instant.ofEpochMilli(rs.getLong("end_utc")),
            WorkBlockStatus.valueOf(rs.getString("status")),
            rs.getLong("created_at"),
            rs.getLong("updated_at"));

    private static final RowMapper<WorkBlockAction> ACTION_MAPPER = (rs, n) -> {
        long successor = rs.getLong("successor_plan_id");
        return new WorkBlockAction(
                rs.getLong("id"),
                rs.getLong("block_id"),
                rs.getLong("plan_id"),
                BlockClearanceAction.valueOf(rs.getString("action")),
                rs.wasNull() ? null : successor,
                rs.getLong("created_at"));
    };

    private final JdbcTemplate jdbc;

    public WorkBlockRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入封锁（仅 ACTIVE 一种状态，登记事务最后一步写入即生效），返回自增主键。
     */
    public long insertBlock(String blockKey, LocalDate opDate, String sectionId,
                            Instant startUtc, Instant endUtc, long nowMillis) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rail_work_block"
                            + " (block_key, op_date, section_id, start_utc, end_utc, status,"
                            + " created_at, updated_at) VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, blockKey);
            ps.setDate(2, Date.valueOf(opDate));
            ps.setString(3, sectionId);
            ps.setLong(4, startUtc.toEpochMilli());
            ps.setLong(5, endUtc.toEpochMilli());
            ps.setLong(6, nowMillis);
            ps.setLong(7, nowMillis);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按业务键查询封锁（不加锁）。
     */
    public Optional<WorkBlock> findByKey(String blockKey) {
        return jdbc.query("SELECT id, block_key, op_date, section_id, start_utc, end_utc, status,"
                        + " created_at, updated_at FROM rail_work_block WHERE block_key = ?",
                BLOCK_MAPPER, blockKey).stream().findFirst();
    }

    /**
     * 按业务键查询封锁并加行级写锁，须在事务内调用。
     */
    public Optional<WorkBlock> findByKeyForUpdate(String blockKey) {
        return jdbc.query("SELECT id, block_key, op_date, section_id, start_utc, end_utc, status,"
                        + " created_at, updated_at FROM rail_work_block WHERE block_key = ? FOR UPDATE",
                BLOCK_MAPPER, blockKey).stream().findFirst();
    }

    /**
     * 查询某运营日、给定区段集合上全部已生效封锁（不过滤时间，重叠判断在业务层按左闭右开完成）。
     */
    public List<WorkBlock> findActiveBlocks(LocalDate opDate, Collection<String> sectionIds) {
        if (sectionIds.isEmpty()) {
            return List.of();
        }
        StringJoiner placeholders = new StringJoiner(", ");
        List<Object> args = new ArrayList<>();
        args.add(Date.valueOf(opDate));
        for (String sectionId : sectionIds) {
            placeholders.add("?");
            args.add(sectionId);
        }
        return jdbc.query("SELECT id, block_key, op_date, section_id, start_utc, end_utc, status,"
                        + " created_at, updated_at FROM rail_work_block"
                        + " WHERE status = 'ACTIVE' AND op_date = ? AND section_id IN ("
                        + placeholders + ") ORDER BY start_utc",
                BLOCK_MAPPER, args.toArray());
    }

    /**
     * 追加一条封锁处理结果（不可变）。
     */
    public void insertAction(long blockId, long planId, BlockClearanceAction action,
                             Long successorPlanId, long nowMillis) {
        jdbc.update("INSERT INTO rail_work_block_action"
                        + " (block_id, plan_id, action, successor_plan_id, created_at)"
                        + " VALUES (?, ?, ?, ?, ?)",
                blockId, planId, action.name(), successorPlanId, nowMillis);
    }

    /**
     * 查询某封锁的全部处理结果，按 id 升序（即登记事务内的处理顺序）。
     */
    public List<WorkBlockAction> findActions(long blockId) {
        return jdbc.query("SELECT id, block_id, plan_id, action, successor_plan_id, created_at"
                        + " FROM rail_work_block_action WHERE block_id = ? ORDER BY id",
                ACTION_MAPPER, blockId);
    }
}
