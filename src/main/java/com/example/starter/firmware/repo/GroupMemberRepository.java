package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.GroupMember;
import com.example.starter.firmware.domain.GroupMemberStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 组发布成员任务数据访问。同设备同组由 uk_member_group_device 保证最多一条；
 * active_device 唯一索引保证同设备至多属于一个未终结组，组终结时统一置 NULL 释放占用。
 */
@Repository
public class GroupMemberRepository {

    private static final RowMapper<GroupMember> MAPPER = (rs, rowNum) -> new GroupMember(
            rs.getLong("id"), rs.getLong("group_id"), rs.getString("device_id"),
            rs.getInt("generation"), GroupMemberStatus.valueOf(rs.getString("status")),
            rs.getString("prepared_at_utc"));

    private static final String COLUMNS = "id, group_id, device_id, generation, status, prepared_at_utc";

    private final JdbcTemplate jdbc;

    public GroupMemberRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long groupId, String deviceId, int generation) {
        jdbc.update("INSERT INTO group_member (group_id, device_id, generation, status, active_device)"
                + " VALUES (?, ?, ?, 'PENDING', ?)", groupId, deviceId, generation, deviceId);
    }

    public List<GroupMember> findByGroup(long groupId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM group_member WHERE group_id = ? ORDER BY id",
                MAPPER, groupId);
    }

    public Optional<GroupMember> findByGroupAndDevice(long groupId, String deviceId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM group_member WHERE group_id = ? AND device_id = ?",
                MAPPER, groupId, deviceId).stream().findFirst();
    }

    public void markPrepared(long id, String preparedAtUtc) {
        jdbc.update("UPDATE group_member SET status = 'PREPARED', prepared_at_utc = ?,"
                + " updated_at = CURRENT_TIMESTAMP WHERE id = ?", preparedAtUtc, id);
    }

    public void markFailed(long id) {
        jdbc.update("UPDATE group_member SET status = 'FAILED', updated_at = CURRENT_TIMESTAMP"
                + " WHERE id = ?", id);
    }

    /**
     * 组进入终态时释放全部成员的设备占用（active_device 置 NULL）。
     */
    public int releaseByGroup(long groupId) {
        return jdbc.update("UPDATE group_member SET active_device = NULL,"
                + " updated_at = CURRENT_TIMESTAMP WHERE group_id = ? AND active_device IS NOT NULL", groupId);
    }

    /**
     * 设备是否属于某个未终结组（预备屏障：活动组内设备拒绝普通 SUCCESS 回执）。
     */
    public boolean existsActiveByDevice(String deviceId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM group_member WHERE active_device = ?", Long.class, deviceId);
        return count != null && count > 0;
    }
}
