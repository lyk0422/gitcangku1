package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.RollbackPlanHop;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 回退计划逐跳冻结路径数据访问，只增不改。
 */
@Repository
public class RollbackHopRepository {

    private static final RowMapper<RollbackPlanHop> MAPPER = (rs, rowNum) -> new RollbackPlanHop(
            rs.getLong("id"), rs.getLong("plan_id"), rs.getString("device_id"), rs.getInt("hop_index"),
            rs.getString("expected_version"), rs.getString("to_version"),
            rs.getLong("source_release_id"), rs.getLong("source_task_id"));

    private static final String COLUMNS = "id, plan_id, device_id, hop_index, expected_version, to_version,"
            + " source_release_id, source_task_id";

    private final JdbcTemplate jdbc;

    public RollbackHopRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long planId, String deviceId, int hopIndex, String expectedVersion, String toVersion,
                       long sourceReleaseId, long sourceTaskId) {
        jdbc.update("INSERT INTO rollback_plan_hop (plan_id, device_id, hop_index, expected_version, to_version,"
                        + " source_release_id, source_task_id) VALUES (?, ?, ?, ?, ?, ?, ?)",
                planId, deviceId, hopIndex, expectedVersion, toVersion, sourceReleaseId, sourceTaskId);
    }

    public List<RollbackPlanHop> findByPlan(long planId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM rollback_plan_hop WHERE plan_id = ?"
                + " ORDER BY device_id, hop_index", MAPPER, planId);
    }

    public List<RollbackPlanHop> findByPlanAndHop(long planId, int hopIndex) {
        return jdbc.query("SELECT " + COLUMNS + " FROM rollback_plan_hop WHERE plan_id = ? AND hop_index = ?"
                + " ORDER BY device_id", MAPPER, planId, hopIndex);
    }

    /**
     * 设备在本计划内的最大跳号（路径长度减1）；设备无路径时返回 -1。
     */
    public int findMaxHopByDevice(long planId, String deviceId) {
        Integer max = jdbc.queryForObject("SELECT COALESCE(MAX(hop_index), -1) FROM rollback_plan_hop"
                + " WHERE plan_id = ? AND device_id = ?", Integer.class, planId, deviceId);
        return max == null ? -1 : max;
    }
}
