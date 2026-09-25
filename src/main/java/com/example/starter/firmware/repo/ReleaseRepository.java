package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.domain.ReleaseOrder;
import com.example.starter.firmware.domain.ReleaseStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.Optional;

/**
 * 发布单数据访问。扩量、取消、恢复、拉取、回执共用行锁（SELECT ... FOR UPDATE）形成一致提交顺序。
 * 当前轮次统计（round_success/round_failed）只在持有发布单行锁的事务内增减。
 */
@Repository
public class ReleaseRepository {

    private static final RowMapper<ReleaseOrder> MAPPER = (rs, rowNum) -> new ReleaseOrder(
            rs.getLong("id"), rs.getInt("version"), rs.getString("model"),
            rs.getString("from_version"), rs.getString("to_version"),
            rs.getInt("ratio"), ReleaseStatus.valueOf(rs.getString("status")),
            rs.getInt("sample_floor"), rs.getInt("failure_threshold_percent"),
            rs.getInt("monitor_round"), rs.getInt("round_success"), rs.getInt("round_failed"),
            rs.getBoolean("allow_skip"));

    private static final String COLUMNS = "id, version, model, from_version, to_version, ratio, status,"
            + " sample_floor, failure_threshold_percent, monitor_round, round_success, round_failed, allow_skip";

    private final JdbcTemplate jdbc;

    public ReleaseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(String model, String fromVersion, String toVersion, int ratio,
                       int sampleFloor, int failureThresholdPercent, boolean allowSkip) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO release_order (version, model, from_version, to_version, ratio, status,"
                            + " sample_floor, failure_threshold_percent, monitor_round, active_model, allow_skip)"
                            + " VALUES (1, ?, ?, ?, ?, 'ACTIVE', ?, ?, 1, ?, ?)",
                    new String[]{"id"});
            ps.setString(1, model);
            ps.setString(2, fromVersion);
            ps.setString(3, toVersion);
            ps.setInt(4, ratio);
            ps.setInt(5, sampleFloor);
            ps.setInt(6, failureThresholdPercent);
            ps.setString(7, model);
            ps.setBoolean(8, allowSkip);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<ReleaseOrder> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM release_order WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    public Optional<ReleaseOrder> findByIdForUpdate(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM release_order WHERE id = ? FOR UPDATE", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 按型号查找未终结（ACTIVE 或 PAUSED）发布单；active_model 唯一约束保证至多一条。
     */
    public Optional<ReleaseOrder> findActiveByModel(String model) {
        return jdbc.query("SELECT " + COLUMNS + " FROM release_order WHERE active_model = ?", MAPPER, model)
                .stream().findFirst();
    }

    /**
     * 乐观扩量：仅当版本与状态匹配时生效，返回影响行数。
     */
    public int expand(long id, int expectedVersion, int newRatio) {
        return jdbc.update("UPDATE release_order SET version = version + 1, ratio = ?, updated_at = CURRENT_TIMESTAMP"
                + " WHERE id = ? AND version = ? AND status = 'ACTIVE'", newRatio, id, expectedVersion);
    }

    public void cancel(long id) {
        jdbc.update("UPDATE release_order SET status = 'CANCELLED', active_model = NULL,"
                + " updated_at = CURRENT_TIMESTAMP WHERE id = ?", id);
    }

    /**
     * 当前监控轮次统计加一（成功或失败），调用方必须已持有发布单行锁。
     */
    public void incrementRoundStats(long id, ReceiptResult result) {
        if (result == ReceiptResult.SUCCESS) {
            jdbc.update("UPDATE release_order SET round_success = round_success + 1,"
                    + " updated_at = CURRENT_TIMESTAMP WHERE id = ?", id);
        } else {
            jdbc.update("UPDATE release_order SET round_failed = round_failed + 1,"
                    + " updated_at = CURRENT_TIMESTAMP WHERE id = ?", id);
        }
    }

    /**
     * 仅当仍为 ACTIVE 时转为 PAUSED，返回影响行数；调用方已持有行锁，并发回执至多一行生效。
     */
    public int pauseIfActive(long id) {
        return jdbc.update("UPDATE release_order SET status = 'PAUSED', updated_at = CURRENT_TIMESTAMP"
                + " WHERE id = ? AND status = 'ACTIVE'", id);
    }

    /**
     * 人工恢复：仅当版本匹配且仍为 PAUSED 时生效；版本加一、开启新监控轮次并清零本轮统计。
     */
    public int resume(long id, int expectedVersion) {
        return jdbc.update("UPDATE release_order SET status = 'ACTIVE', version = version + 1,"
                + " monitor_round = monitor_round + 1, round_success = 0, round_failed = 0,"
                + " updated_at = CURRENT_TIMESTAMP"
                + " WHERE id = ? AND version = ? AND status = 'PAUSED'", id, expectedVersion);
    }

    /**
     * 跳级开关：仅当版本匹配且未终结时生效，版本加一；只影响后续拉取，不改写已下发任务。
     */
    public int updateAllowSkip(long id, int expectedVersion, boolean allowSkip) {
        return jdbc.update("UPDATE release_order SET allow_skip = ?, version = version + 1,"
                + " updated_at = CURRENT_TIMESTAMP"
                + " WHERE id = ? AND version = ? AND status <> 'CANCELLED'", allowSkip, id, expectedVersion);
    }
}
