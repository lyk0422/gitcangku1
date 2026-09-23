package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.RollbackHop;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 回退计划逐跳冻结定义数据访问。创建计划时写入，创建后不可改。
 */
@Repository
public class RollbackHopRepository {

    private static final RowMapper<RollbackHop> MAPPER = (rs, rowNum) -> new RollbackHop(
            rs.getLong("id"), rs.getLong("plan_id"), rs.getString("device_id"), rs.getInt("hop_index"),
            rs.getString("expected_version"), rs.getString("target_version"),
            rs.getLong("source_release_id"));

    private static final String COLUMNS = "id, plan_id, device_id, hop_index, expected_version,"
            + " target_version, source_release_id";

    private final JdbcTemplate jdbc;

    public RollbackHopRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long planId, String deviceId, int hopIndex, String expectedVersion,
                      String targetVersion, long sourceReleaseId) {
        jdbc.update("INSERT INTO rollback_plan_hop"
                        + " (plan_id, device_id, hop_index, expected_version, target_version, source_release_id)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                planId, deviceId, hopIndex, expectedVersion, targetVersion, sourceReleaseId);
    }

    public Optional<RollbackHop> find(long planId, String deviceId, int hopIndex) {
        return jdbc.query("SELECT " + COLUMNS + " FROM rollback_plan_hop"
                        + " WHERE plan_id = ? AND device_id = ? AND hop_index = ?",
                MAPPER, planId, deviceId, hopIndex).stream().findFirst();
    }

    public List<RollbackHop> findByPlanAndDevice(long planId, String deviceId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM rollback_plan_hop"
                        + " WHERE plan_id = ? AND device_id = ? ORDER BY hop_index",
                MAPPER, planId, deviceId);
    }
}
