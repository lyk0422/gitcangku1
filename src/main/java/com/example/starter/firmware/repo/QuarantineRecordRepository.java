package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.QuarantineAction;
import com.example.starter.firmware.domain.QuarantineRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 设备隔离/解除历史数据访问。记录只增不改，按设备查询。
 */
@Repository
public class QuarantineRecordRepository {

    private static final RowMapper<QuarantineRecord> MAPPER = (rs, rowNum) -> new QuarantineRecord(
            rs.getLong("id"), rs.getString("device_id"), QuarantineAction.valueOf(rs.getString("action")),
            rs.getString("reason_code"), rs.getString("expected_version"), rs.getString("operator"),
            rs.getTimestamp("created_at").toLocalDateTime().toString());

    private static final String COLUMNS = "id, device_id, action, reason_code, expected_version, operator,"
            + " created_at";

    private final JdbcTemplate jdbc;

    public QuarantineRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String deviceId, QuarantineAction action, String reasonCode,
                       String expectedVersion, String operator) {
        jdbc.update("INSERT INTO device_quarantine_record"
                        + " (device_id, action, reason_code, expected_version, operator)"
                        + " VALUES (?, ?, ?, ?, ?)",
                deviceId, action.name(), reasonCode, expectedVersion, operator);
    }

    public List<QuarantineRecord> findByDevice(String deviceId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM device_quarantine_record"
                + " WHERE device_id = ? ORDER BY id", MAPPER, deviceId);
    }

    /**
     * 最近一次隔离（QUARANTINE）记录，用于解除时校验双人确认。
     */
    public Optional<QuarantineRecord> findLatestQuarantine(String deviceId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM device_quarantine_record"
                        + " WHERE device_id = ? AND action = 'QUARANTINE' ORDER BY id DESC LIMIT 1",
                MAPPER, deviceId).stream().findFirst();
    }
}
