package com.example.starter.blackstart;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 黑启动计划仓储。时间列以 UTC 墙钟时间（LocalDateTime）写入 TIMESTAMP 字段，读取时按 UTC 还原为 Instant。
 */
@Repository
public class PlanRepository {

    private static final String COLUMNS =
            "id, name, status, current_step, created_at, completed_at, terminated_at, termination_reason";

    private static final RowMapper<BlackStartPlan> ROW_MAPPER = (rs, rowNum) -> new BlackStartPlan(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getString("status"),
            rs.getInt("current_step"),
            toInstant(rs.getObject("created_at", LocalDateTime.class)),
            toInstant(rs.getObject("completed_at", LocalDateTime.class)),
            toInstant(rs.getObject("terminated_at", LocalDateTime.class)),
            rs.getString("termination_reason"));

    private final JdbcTemplate jdbcTemplate;

    public PlanRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static Instant toInstant(LocalDateTime utcDateTime) {
        return utcDateTime == null ? null : utcDateTime.toInstant(ZoneOffset.UTC);
    }

    private static LocalDateTime toUtc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /**
     * 新建执行中的计划，当前步骤为 1，返回生成的主键。
     */
    public long insert(String name, Instant createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO black_start_plan (name, status, current_step, created_at)"
                            + " VALUES (?, 'IN_PROGRESS', 1, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, name);
            ps.setObject(2, toUtc(createdAt));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 按主键查询（不加锁），用于只读查询。
     */
    public Optional<BlackStartPlan> findById(long id) {
        return jdbcTemplate.query(
                        "SELECT " + COLUMNS + " FROM black_start_plan WHERE id = ?",
                        ROW_MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 按主键查询并加行写锁（SELECT ... FOR UPDATE），用于事务内串行化状态变更。
     */
    public Optional<BlackStartPlan> findByIdForUpdate(long id) {
        return jdbcTemplate.query(
                        "SELECT " + COLUMNS + " FROM black_start_plan WHERE id = ? FOR UPDATE",
                        ROW_MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 条件推进步骤：仅当计划仍处于执行中且当前步骤号匹配时才更新，返回受影响行数。
     * 最后一步同时把状态置为 STARTED 并记录完成时刻。返回 0 表示存在并发或状态已变化。
     */
    public int advanceStep(long planId, int expectedStep, boolean lastStep, Instant now) {
        if (lastStep) {
            return jdbcTemplate.update(
                    "UPDATE black_start_plan SET status = 'STARTED', completed_at = ?"
                            + " WHERE id = ? AND current_step = ? AND status = 'IN_PROGRESS'",
                    toUtc(now), planId, expectedStep);
        }
        return jdbcTemplate.update(
                "UPDATE black_start_plan SET current_step = current_step + 1"
                        + " WHERE id = ? AND current_step = ? AND status = 'IN_PROGRESS'",
                planId, expectedStep);
    }

    /**
     * 条件终止：仅当计划仍处于执行中时才更新为 TERMINATED，返回受影响行数。
     */
    public int terminate(long planId, Instant terminatedAt, String reason) {
        return jdbcTemplate.update(
                "UPDATE black_start_plan SET status = 'TERMINATED', terminated_at = ?,"
                        + " termination_reason = ? WHERE id = ? AND status = 'IN_PROGRESS'",
                toUtc(terminatedAt), reason, planId);
    }
}