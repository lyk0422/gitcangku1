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
 * 固定设备组发布数据访问。准备、取消、激活共用组行锁（SELECT ... FOR UPDATE）形成一致提交顺序。
 */
@Repository
public class GroupReleaseRepository {

    private static final RowMapper<GroupRelease> MAPPER = (rs, rowNum) -> new GroupRelease(
            rs.getLong("id"), rs.getString("model"), rs.getString("to_version"),
            rs.getInt("generation"), GroupReleaseStatus.valueOf(rs.getString("status")),
            rs.getObject("activation_generation", Integer.class), rs.getInt("success_count"),
            rs.getInt("failed_count"), rs.getString("failed_device"));

    private static final String COLUMNS = "id, model, to_version, generation, status,"
            + " activation_generation, success_count, failed_count, failed_device";

    private final JdbcTemplate jdbc;

    public GroupReleaseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(String model, String toVersion) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO group_release (model, to_version, generation, status)"
                            + " VALUES (?, ?, 1, 'PREPARING')",
                    new String[]{"id"});
            ps.setString(1, model);
            ps.setString(2, toVersion);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<GroupRelease> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM group_release WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    public Optional<GroupRelease> findByIdForUpdate(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM group_release WHERE id = ? FOR UPDATE", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 任一成员准备失败：仅当仍 PREPARING 时整组中止，失败计数加一并记录失败成员。
     */
    public int abort(long id, String failedDevice) {
        return jdbc.update("UPDATE group_release SET status = 'ABORTED', failed_count = failed_count + 1,"
                + " failed_device = ?, updated_at = CURRENT_TIMESTAMP"
                + " WHERE id = ? AND status = 'PREPARING'", failedDevice, id);
    }

    /**
     * 激活：仅当仍 PREPARING 时生效，生成全组一致的激活代次并按成员数统一记成功。
     */
    public int activate(long id, int activationGeneration, int successCount) {
        return jdbc.update("UPDATE group_release SET status = 'ACTIVATED', activation_generation = ?,"
                + " success_count = ?, updated_at = CURRENT_TIMESTAMP"
                + " WHERE id = ? AND status = 'PREPARING'", activationGeneration, successCount, id);
    }

    /**
     * 取消：仅当仍 PREPARING 时生效；激活后不允许取消。
     */
    public int cancel(long id) {
        return jdbc.update("UPDATE group_release SET status = 'CANCELLED', updated_at = CURRENT_TIMESTAMP"
                + " WHERE id = ? AND status = 'PREPARING'", id);
    }
}
