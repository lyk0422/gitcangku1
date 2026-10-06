package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.GroupRelease;
import com.example.starter.firmware.domain.GroupReleaseStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.Optional;

/**
 * 组发布单数据访问。准备回执、激活、取消共用组行锁（SELECT ... FOR UPDATE）形成一致提交顺序，
 * 竞争只产生一个完整终态。
 */
@Repository
public class GroupReleaseRepository {

    private static final RowMapper<GroupRelease> MAPPER = (rs, rowNum) -> new GroupRelease(
            rs.getLong("id"), rs.getInt("version"), rs.getString("model"), rs.getString("to_version"),
            GroupReleaseStatus.valueOf(rs.getString("status")), rs.getInt("prepare_generation"),
            (Integer) rs.getObject("activation_generation"), rs.getString("failed_device_id"),
            rs.getString("activated_at_utc"));

    private static final String COLUMNS = "id, version, model, to_version, status, prepare_generation,"
            + " activation_generation, failed_device_id, activated_at_utc";

    private final JdbcTemplate jdbc;

    public GroupReleaseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(String model, String toVersion) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO device_group_release (version, model, to_version, status, prepare_generation)"
                            + " VALUES (1, ?, ?, 'PREPARING', 1)",
                    new String[]{"id"});
            ps.setString(1, model);
            ps.setString(2, toVersion);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<GroupRelease> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM device_group_release WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    public Optional<GroupRelease> findByIdForUpdate(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM device_group_release WHERE id = ? FOR UPDATE",
                MAPPER, id).stream().findFirst();
    }

    /**
     * 任一成员准备失败：整组转 ABORTED（终态）并记录失败成员；调用方已持有组行锁。
     */
    public void markAborted(long id, String failedDeviceId) {
        jdbc.update("UPDATE device_group_release SET status = 'ABORTED', failed_device_id = ?,"
                + " updated_at = CURRENT_TIMESTAMP WHERE id = ?", failedDeviceId, id);
    }

    /**
     * 原子激活：仅当版本匹配且仍为 PREPARING 时生效；版本加一并生成组激活代次，返回影响行数。
     */
    public int activate(long id, int expectedVersion, int activationGeneration, String activatedAtUtc) {
        return jdbc.update("UPDATE device_group_release SET status = 'ACTIVATED', version = version + 1,"
                + " activation_generation = ?, activated_at_utc = ?, updated_at = CURRENT_TIMESTAMP"
                + " WHERE id = ? AND version = ? AND status = 'PREPARING'",
                activationGeneration, activatedAtUtc, id, expectedVersion);
    }

    /**
     * 取消：仅当版本匹配且仍为 PREPARING 时生效；版本加一，返回影响行数。
     */
    public int cancel(long id, int expectedVersion) {
        return jdbc.update("UPDATE device_group_release SET status = 'CANCELLED', version = version + 1,"
                + " updated_at = CURRENT_TIMESTAMP"
                + " WHERE id = ? AND version = ? AND status = 'PREPARING'", id, expectedVersion);
    }
}
