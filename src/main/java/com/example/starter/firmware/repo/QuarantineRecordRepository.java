package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.QuarantineOperation;
import com.example.starter.firmware.domain.QuarantineRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;

/**
 * 设备隔离/解除隔离历史数据访问。历史只增不改，按ID升序即操作时间顺序。
 */
@Repository
public class QuarantineRecordRepository {

    private static final RowMapper<QuarantineRecord> MAPPER = (rs, rowNum) -> new QuarantineRecord(
            rs.getLong("id"), rs.getString("device_id"),
            QuarantineOperation.valueOf(rs.getString("operation")),
            rs.getString("operator_name"), rs.getString("reason_code"),
            rs.getString("device_version"), rs.getString("created_at_utc"));

    private static final String COLUMNS = "id, device_id, operation, operator_name, reason_code,"
            + " device_version, created_at_utc";

    private final JdbcTemplate jdbc;

    public QuarantineRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(String deviceId, QuarantineOperation operation, String operatorName,
                       String reasonCode, String deviceVersion, String createdAtUtc) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO device_quarantine_record"
                            + " (device_id, operation, operator_name, reason_code, device_version, created_at_utc)"
                            + " VALUES (?, ?, ?, ?, ?, ?)",
                    new String[]{"id"});
            ps.setString(1, deviceId);
            ps.setString(2, operation.name());
            ps.setString(3, operatorName);
            ps.setString(4, reasonCode);
            ps.setString(5, deviceVersion);
            ps.setString(6, createdAtUtc);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public List<QuarantineRecord> findByDevice(String deviceId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM device_quarantine_record"
                + " WHERE device_id = ? ORDER BY id", MAPPER, deviceId);
    }

    /**
     * 设备最近一次隔离操作记录；解除隔离校验操作人必须与该记录操作人不同。
     */
    public Optional<QuarantineRecord> findLatestByDevice(String deviceId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM device_quarantine_record"
                        + " WHERE device_id = ? ORDER BY id DESC LIMIT 1", MAPPER, deviceId)
                .stream().findFirst();
    }

    public Optional<QuarantineRecord> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM device_quarantine_record WHERE id = ?",
                MAPPER, id).stream().findFirst();
    }
}
