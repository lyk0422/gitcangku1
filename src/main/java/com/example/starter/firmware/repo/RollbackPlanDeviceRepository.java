package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.RollbackPlanDevice;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 回退计划设备成员数据访问。active_device_id 唯一约束保证设备同时只参与一个未终结回退计划。
 */
@Repository
public class RollbackPlanDeviceRepository {

    private static final RowMapper<RollbackPlanDevice> MAPPER = (rs, rowNum) -> new RollbackPlanDevice(
            rs.getLong("plan_id"), rs.getString("device_id"), rs.getInt("hop_count"));

    private static final String COLUMNS = "plan_id, device_id, hop_count";

    private final JdbcTemplate jdbc;

    public RollbackPlanDeviceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 占用设备：active_device_id = device_id。设备已被其他未终结计划占用时抛 DuplicateKeyException。
     */
    public void insert(long planId, String deviceId, int hopCount) {
        jdbc.update("INSERT INTO rollback_plan_device (plan_id, device_id, hop_count, active_device_id)"
                + " VALUES (?, ?, ?, ?)", planId, deviceId, hopCount, deviceId);
    }

    public List<RollbackPlanDevice> findByPlan(long planId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM rollback_plan_device WHERE plan_id = ?"
                + " ORDER BY device_id", MAPPER, planId);
    }

    public Optional<RollbackPlanDevice> find(long planId, String deviceId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM rollback_plan_device"
                        + " WHERE plan_id = ? AND device_id = ?", MAPPER, planId, deviceId)
                .stream().findFirst();
    }

    /**
     * 计划内尚未全部跳成功的设备数；为 0 时计划可完结。
     */
    public long countDevicesNotCompleted(long planId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rollback_plan_device d WHERE d.plan_id = ? AND ("
                        + " SELECT COUNT(DISTINCT t.hop_index) FROM rollback_hop_task t"
                        + " WHERE t.plan_id = d.plan_id AND t.device_id = d.device_id"
                        + " AND t.status = 'SUCCESS') < d.hop_count",
                Long.class, planId);
        return count == null ? 0 : count;
    }

    /**
     * 设备当前是否被某个未终结回退计划占用。
     */
    public boolean isDeviceOccupied(String deviceId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rollback_plan_device WHERE active_device_id = ?",
                Long.class, deviceId);
        return count != null && count > 0;
    }

    /**
     * 计划进入终态后释放全部设备占用。
     */
    public void releaseByPlan(long planId) {
        jdbc.update("UPDATE rollback_plan_device SET active_device_id = NULL WHERE plan_id = ?", planId);
    }
}
