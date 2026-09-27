package com.example.starter.firmware.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 证书域拒绝原因审计日志数据访问。失败操作的业务事务回滚后，由调用方在新事务外补写，
 * 因此拒绝记录不随业务回滚消失，也不构成半成品业务状态。
 */
@Repository
public class CertRejectionRepository {

    /**
     * 拒绝记录视图行。
     */
    public record RejectionRow(long id, String operation, String firmwareVersion, String chainVersion,
                               String deviceId, String reasonCode, String detail, String rejectedAtUtc) {
    }

    private static final RowMapper<RejectionRow> MAPPER = (rs, rowNum) -> new RejectionRow(
            rs.getLong("id"), rs.getString("operation"), rs.getString("firmware_version"),
            rs.getString("chain_version"), rs.getString("device_id"), rs.getString("reason_code"),
            rs.getString("detail"), rs.getString("rejected_at_utc"));

    private final JdbcTemplate jdbc;

    public CertRejectionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String operation, String firmwareVersion, String chainVersion, String deviceId,
                       String reasonCode, String detail, String rejectedAtUtc) {
        jdbc.update("INSERT INTO cert_rejection_record"
                        + " (operation, firmware_version, chain_version, device_id, reason_code, detail, rejected_at_utc)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                operation, firmwareVersion, chainVersion, deviceId, reasonCode, detail, rejectedAtUtc);
    }

    public List<RejectionRow> findAll() {
        return jdbc.query("SELECT id, operation, firmware_version, chain_version, device_id, reason_code,"
                + " detail, rejected_at_utc FROM cert_rejection_record ORDER BY id", MAPPER);
    }
}
