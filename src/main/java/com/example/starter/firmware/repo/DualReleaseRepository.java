package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.DualRelease;
import com.example.starter.firmware.domain.DualReleaseStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.Optional;

/**
 * 双分区发布单数据访问。创建、暂停与任务结算共用行锁（SELECT ... FOR UPDATE）形成一致提交顺序。
 * 提交/回滚计数只在持有发布单行锁的事务内、任务首次进入终态时加一。
 */
@Repository
public class DualReleaseRepository {

    private static final RowMapper<DualRelease> MAPPER = (rs, rowNum) -> new DualRelease(
            rs.getLong("id"), rs.getInt("version"), rs.getString("model"),
            rs.getString("target_version"), DualReleaseStatus.valueOf(rs.getString("status")),
            rs.getInt("committed_count"), rs.getInt("rolled_back_count"));

    private static final String COLUMNS = "id, version, model, target_version, status,"
            + " committed_count, rolled_back_count";

    private final JdbcTemplate jdbc;

    public DualReleaseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(String model, String targetVersion) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO dual_release (version, model, target_version, status, active_model)"
                            + " VALUES (1, ?, ?, 'ACTIVE', ?)",
                    new String[]{"id"});
            ps.setString(1, model);
            ps.setString(2, targetVersion);
            ps.setString(3, model);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<DualRelease> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM dual_release WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    public Optional<DualRelease> findByIdForUpdate(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM dual_release WHERE id = ? FOR UPDATE", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 按型号查找未终结（ACTIVE 或 PAUSED）双分区发布单；active_model 唯一约束保证至多一条。
     */
    public Optional<DualRelease> findActiveByModel(String model) {
        return jdbc.query("SELECT " + COLUMNS + " FROM dual_release WHERE active_model = ?", MAPPER, model)
                .stream().findFirst();
    }

    /**
     * 暂停：仅当版本匹配且仍为 ACTIVE 时生效，版本加一，返回影响行数。
     */
    public int pauseIfActive(long id, int expectedVersion) {
        return jdbc.update("UPDATE dual_release SET status = 'PAUSED', version = version + 1,"
                + " updated_at = CURRENT_TIMESTAMP WHERE id = ? AND version = ? AND status = 'ACTIVE'",
                id, expectedVersion);
    }

    /**
     * 提交或回滚计数加一，调用方必须已持有发布单行锁且任务确为首次进入终态。
     */
    public void incrementCommitted(long id) {
        jdbc.update("UPDATE dual_release SET committed_count = committed_count + 1,"
                + " updated_at = CURRENT_TIMESTAMP WHERE id = ?", id);
    }

    public void incrementRolledBack(long id) {
        jdbc.update("UPDATE dual_release SET rolled_back_count = rolled_back_count + 1,"
                + " updated_at = CURRENT_TIMESTAMP WHERE id = ?", id);
    }
}
