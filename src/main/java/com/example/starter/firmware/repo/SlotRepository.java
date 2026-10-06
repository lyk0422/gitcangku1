package com.example.starter.firmware.repo;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 设备独占安装槽数据访问。device_id 主键保证同设备至多一个 INSTALLING/CANCEL_REQUESTED 任务；
 * 占槽冲突以主键唯一约束兜底，不向调用方暴露唯一键错误。
 */
@Repository
public class SlotRepository {

    private final JdbcTemplate jdbc;

    public SlotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 尝试占槽：成功返回 true；设备槽已被其他任务持有返回 false（主键冲突）。
     */
    public boolean tryAcquire(String deviceId, long taskId) {
        try {
            jdbc.update("INSERT INTO device_install_slot (device_id, task_id) VALUES (?, ?)",
                    deviceId, taskId);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /**
     * 释放槽：仅当槽由该任务持有时删除，返回影响行数；重复释放为 0 行，不会误删他人槽。
     */
    public int release(String deviceId, long taskId) {
        return jdbc.update("DELETE FROM device_install_slot WHERE device_id = ? AND task_id = ?",
                deviceId, taskId);
    }

    /**
     * 当前占用该设备槽的任务ID；空闲为 empty。
     */
    public Optional<Long> findHolder(String deviceId) {
        return jdbc.query("SELECT task_id FROM device_install_slot WHERE device_id = ?",
                (rs, rowNum) -> rs.getLong("task_id"), deviceId).stream().findFirst();
    }
}
