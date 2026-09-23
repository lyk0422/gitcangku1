package com.example.starter.firmware.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 设备冲突任务占用数据访问。device_id 主键保证同设备同时至多一个未终结冲突任务：
 * 正向投放 PENDING 任务期间占用 FORWARD；回退计划生命周期内占用 ROLLBACK；终结或取消后释放。
 */
@Repository
public class DeviceOccupationRepository {

    public static final String SCOPE_FORWARD = "FORWARD";
    public static final String SCOPE_ROLLBACK = "ROLLBACK";

    private final JdbcTemplate jdbc;

    public DeviceOccupationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 尝试占用设备；已被占用时由唯一约束抛出 DuplicateKeyException，调用方转 409 或放弃派发。
     */
    public void tryOccupy(String deviceId, String scope, long refId) {
        jdbc.update("INSERT INTO device_task_occupation (device_id, scope, ref_id) VALUES (?, ?, ?)",
                deviceId, scope, refId);
    }

    public boolean isOccupied(String deviceId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM device_task_occupation WHERE device_id = ?",
                Long.class, deviceId);
        return count != null && count > 0;
    }

    /**
     * 释放指定设备上的指定占用（设备/作用域/占用方均匹配才删除），避免误释放新占用。
     */
    public int release(String deviceId, String scope, long refId) {
        return jdbc.update("DELETE FROM device_task_occupation WHERE device_id = ? AND scope = ? AND ref_id = ?",
                deviceId, scope, refId);
    }

    /**
     * 释放某占用方名下全部设备（回退计划取消/完成、正向发布单取消时使用）。
     */
    public int releaseAll(String scope, long refId) {
        return jdbc.update("DELETE FROM device_task_occupation WHERE scope = ? AND ref_id = ?", scope, refId);
    }
}
