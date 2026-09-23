package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.DeviceAssignment;
import com.example.starter.firmware.domain.InstallStatus;
import com.example.starter.firmware.domain.ReceiptResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 设备队列分配数据访问。同设备同活动由主键 (campaign_id, device_id) 保证至多一条。
 */
@Repository
public class AssignmentRepository {

    private static final RowMapper<DeviceAssignment> MAPPER = (rs, rowNum) -> {
        String settledResult = rs.getString("settled_result");
        return new DeviceAssignment(rs.getLong("campaign_id"), rs.getString("device_id"),
                rs.getLong("cohort_id"), rs.getInt("assignment_version"),
                rs.getInt("assignment_generation"),
                InstallStatus.valueOf(rs.getString("install_status")),
                rs.getInt("settled_generation"),
                settledResult == null ? null : ReceiptResult.valueOf(settledResult));
    };

    private static final String COLUMNS = "campaign_id, device_id, cohort_id, assignment_version,"
            + " assignment_generation, install_status, settled_generation, settled_result";

    private final JdbcTemplate jdbc;

    public AssignmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long campaignId, String deviceId, long cohortId) {
        jdbc.update("INSERT INTO device_assignment (campaign_id, device_id, cohort_id,"
                        + " assignment_version, assignment_generation, install_status,"
                        + " settled_generation)"
                        + " VALUES (?, ?, ?, 1, 1, 'NONE', 0)",
                campaignId, deviceId, cohortId);
    }

    public Optional<DeviceAssignment> find(long campaignId, String deviceId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM device_assignment"
                        + " WHERE campaign_id = ? AND device_id = ?",
                MAPPER, campaignId, deviceId).stream().findFirst();
    }

    public Optional<DeviceAssignment> findForUpdate(long campaignId, String deviceId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM device_assignment"
                        + " WHERE campaign_id = ? AND device_id = ? FOR UPDATE",
                MAPPER, campaignId, deviceId).stream().findFirst();
    }

    public long countByCampaign(long campaignId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM device_assignment WHERE campaign_id = ?",
                Long.class, campaignId);
        return count == null ? 0 : count;
    }

    /**
     * 回执结算：记录已结算代次与结果；SUCCESS 同时确认安装成功。
     */
    public void markSettled(long campaignId, String deviceId, int generation, ReceiptResult result) {
        String installStatus = result == ReceiptResult.SUCCESS ? "SUCCESS" : "NONE";
        jdbc.update("UPDATE device_assignment SET settled_generation = ?, settled_result = ?,"
                        + " install_status = ?, updated_at = CURRENT_TIMESTAMP"
                        + " WHERE campaign_id = ? AND device_id = ?",
                generation, result.name(), installStatus, campaignId, deviceId);
    }

    /**
     * 迁移：切换队列、分配版本加一、指令代次加一；已结算代次保持不变（旧代次回执此后判为迟到）。
     */
    public void migrate(long campaignId, String deviceId, long toCohortId) {
        jdbc.update("UPDATE device_assignment SET cohort_id = ?,"
                        + " assignment_version = assignment_version + 1,"
                        + " assignment_generation = assignment_generation + 1,"
                        + " updated_at = CURRENT_TIMESTAMP"
                        + " WHERE campaign_id = ? AND device_id = ?",
                toCohortId, campaignId, deviceId);
    }
}
