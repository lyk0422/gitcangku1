package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.GroupMember;
import com.example.starter.firmware.domain.GroupMemberStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 组发布成员任务数据访问。同设备同组由 uk_group_member_device 保证最多一条；
 * active_device 唯一索引保证一台设备至多属于一个活动（PREPARING）组，组终态时置 NULL 释放占用。
 */
@Repository
public class GroupMemberRepository {

    private static final RowMapper<GroupMember> MAPPER = (rs, rowNum) -> new GroupMember(
            rs.getLong("id"), rs.getLong("group_id"), rs.getString("device_id"),
            GroupMemberStatus.valueOf(rs.getString("status")), rs.getInt("prepare_generation"),
            (Integer) rs.getObject("activation_generation"));

    private static final String COLUMNS = "id, group_id, device_id, status, prepare_generation,"
            + " activation_generation";

    private final JdbcTemplate jdbc;

    public GroupMemberRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long groupId, String deviceId, int prepareGeneration) {
        jdbc.update("INSERT INTO device_group_member"
                        + " (group_id, device_id, status, prepare_generation, active_device)"
                        + " VALUES (?, ?, 'PENDING', ?, ?)",
                groupId, deviceId, prepareGeneration, deviceId);
    }

    public Optional<GroupMember> findByGroupAndDevice(long groupId, String deviceId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM device_group_member"
                        + " WHERE group_id = ? AND device_id = ?",
                MAPPER, groupId, deviceId).stream().findFirst();
    }

    public List<GroupMember> findByGroup(long groupId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM device_group_member WHERE group_id = ? ORDER BY id",
                MAPPER, groupId);
    }

    public void markPrepared(long id) {
        jdbc.update("UPDATE device_group_member SET status = 'PREPARED', updated_at = CURRENT_TIMESTAMP"
                + " WHERE id = ?", id);
    }

    public void markFailed(long id) {
        jdbc.update("UPDATE device_group_member SET status = 'FAILED', updated_at = CURRENT_TIMESTAMP"
                + " WHERE id = ?", id);
    }

    /**
     * 组激活：全部已暂存成员原子转 ACTIVATED 并写入同一组激活代次；调用方已持有组行锁。
     */
    public int activateByGroup(long groupId, int activationGeneration) {
        return jdbc.update("UPDATE device_group_member SET status = 'ACTIVATED',"
                + " activation_generation = ?, updated_at = CURRENT_TIMESTAMP"
                + " WHERE group_id = ? AND status = 'PREPARED'", activationGeneration, groupId);
    }

    /**
     * 组取消：未终结成员（PENDING/PREPARED）转 CANCELLED；取消不计失败。
     */
    public int cancelUnfinishedByGroup(long groupId) {
        return jdbc.update("UPDATE device_group_member SET status = 'CANCELLED',"
                + " updated_at = CURRENT_TIMESTAMP"
                + " WHERE group_id = ? AND status IN ('PENDING', 'PREPARED')", groupId);
    }

    /**
     * 释放组占用：组进入终态后清空 active_device，设备可加入新组。
     */
    public void releaseOccupancyByGroup(long groupId) {
        jdbc.update("UPDATE device_group_member SET active_device = NULL,"
                + " updated_at = CURRENT_TIMESTAMP WHERE group_id = ?", groupId);
    }

    /**
     * 设备是否属于某个活动（PREPARING）组；用于拒绝普通 SUCCESS 回执绕过预备屏障。
     */
    public boolean existsActiveByDeviceId(String deviceId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM device_group_member WHERE active_device = ?",
                Long.class, deviceId);
        return count != null && count > 0;
    }
}
