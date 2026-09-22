package com.example.starter.firmware.repo;

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
 * 发布单数据访问。扩量、取消、暂停、恢复、拉取、回执共用行锁（SELECT ... FOR UPDATE）形成一致提交顺序。
 */
@Repository
public class ReleaseRepository {

    private static final RowMapper<ReleaseOrder> MAPPER = (rs, rowNum) -> new ReleaseOrder(
            rs.getLong("id"), rs.getInt("version"), rs.getString("model"),
            rs.getString("from_version"), rs.getString("to_version"),
            rs.getInt("ratio"), ReleaseStatus.valueOf(rs.getString("status")),
            rs.getInt("sample_floor"), rs.getInt("failure_threshold_percent"),
            rs.getInt("monitor_round"));

    private static final String COLUMNS = "id, version, model, from_version, to_version, ratio, status,"
            + " sample_floor, failure_threshold_percent, monitor_round";

    private final JdbcTemplate jdbc;

    public ReleaseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(String model, String fromVersion, String toVersion, int ratio,
                       int sampleFloor, int failureThresholdPercent) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO release_order (version, model, from_version, to_version, ratio, status,"
                            + " active_model, sample_floor, failure_threshold_percent, monitor_round)"
                            + " VALUES (1, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, 1)",
                    new String[]{"id"});
            ps.setString(1, model);
            ps.setString(2, fromVersion);
            ps.setString(3, toVersion);
            ps.setInt(4, ratio);
            ps.setString(5, model);
            ps.setInt(6, sampleFloor);
            ps.setInt(7, failureThresholdPercent);
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
     * 原子暂停：仅当发布单仍为 ACTIVE 时转为 PAUSED，返回影响行数（0 表示已被并发变更）。
     */
    public int pauseIfActive(long id) {
        return jdbc.update("UPDATE release_order SET status = 'PAUSED', updated_at = CURRENT_TIMESTAMP"
                + " WHERE id = ? AND status = 'ACTIVE'", id);
    }

    /**
     * 人工恢复：仅当仍为 PAUSED 且版本匹配时，转回 ACTIVE、版本加一并开启新监控轮次，返回影响行数。
     */
    public int resumeIfPaused(long id, int expectedVersion) {
        return jdbc.update("UPDATE release_order SET status = 'ACTIVE', version = version + 1,"
                + " monitor_round = monitor_round + 1, updated_at = CURRENT_TIMESTAMP"
                + " WHERE id = ? AND status = 'PAUSED' AND version = ?", id, expectedVersion);
    }
}
