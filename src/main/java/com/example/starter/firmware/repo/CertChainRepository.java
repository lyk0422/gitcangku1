package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.CertChain;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 固件签名证书链数据访问。同一固件版本的链通过 (firmware_version, chain_version) 唯一约束去重；
 * 登记、轮换、撤销与拉取校验统一对同固件版本的链行加锁（SELECT ... FOR UPDATE），按提交顺序裁决。
 */
@Repository
public class CertChainRepository {

    private static final RowMapper<CertChain> MAPPER = (rs, rowNum) -> {
        String validTo = rs.getString("valid_to_utc");
        String revokedAt = rs.getString("revoked_at_utc");
        return new CertChain(rs.getLong("id"), rs.getString("firmware_version"),
                rs.getString("chain_version"), Instant.parse(rs.getString("valid_from_utc")),
                validTo == null ? null : Instant.parse(validTo),
                readDeviceSet(rs.getString("device_set")), rs.getString("operator"),
                rs.getBoolean("revoked"), revokedAt == null ? null : Instant.parse(revokedAt));
    };

    private static final String COLUMNS = "id, firmware_version, chain_version, valid_from_utc,"
            + " valid_to_utc, device_set, operator, revoked, revoked_at_utc";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;

    public CertChainRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static List<String> readDeviceSet(String json) {
        try {
            return JSON.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("证书链设备集合反序列化失败", e);
        }
    }

    private static String writeDeviceSet(List<String> deviceSet) {
        try {
            return JSON.writeValueAsString(deviceSet == null ? List.of() : deviceSet);
        } catch (Exception e) {
            throw new IllegalStateException("证书链设备集合序列化失败", e);
        }
    }

    public long insert(String firmwareVersion, String chainVersion, Instant validFromUtc, Instant validToUtc,
                       List<String> deviceSet, String operator) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO cert_chain (firmware_version, chain_version, valid_from_utc, valid_to_utc,"
                            + " device_set, operator, revoked) VALUES (?, ?, ?, ?, ?, ?, FALSE)",
                    new String[]{"id"});
            ps.setString(1, firmwareVersion);
            ps.setString(2, chainVersion);
            ps.setString(3, validFromUtc.toString());
            ps.setString(4, validToUtc == null ? null : validToUtc.toString());
            ps.setString(5, writeDeviceSet(deviceSet));
            ps.setString(6, operator);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<CertChain> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM cert_chain WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    public Optional<CertChain> findByVersion(String firmwareVersion, String chainVersion) {
        return jdbc.query("SELECT " + COLUMNS + " FROM cert_chain"
                        + " WHERE firmware_version = ? AND chain_version = ?",
                MAPPER, firmwareVersion, chainVersion).stream().findFirst();
    }

    /**
     * 锁定并读取同固件版本的全部链（按链版本排序），用于登记/轮换/撤销/校验的一致提交顺序。
     */
    public List<CertChain> findByFirmwareForUpdate(String firmwareVersion) {
        return jdbc.query("SELECT " + COLUMNS + " FROM cert_chain"
                + " WHERE firmware_version = ? ORDER BY chain_version FOR UPDATE", MAPPER, firmwareVersion);
    }

    public List<CertChain> findByFirmware(String firmwareVersion) {
        return jdbc.query("SELECT " + COLUMNS + " FROM cert_chain"
                + " WHERE firmware_version = ? ORDER BY chain_version", MAPPER, firmwareVersion);
    }

    /**
     * 轮换时截断旧链失效时刻，返回影响行数。
     */
    public int truncateValidTo(long id, Instant newValidToUtc) {
        return jdbc.update("UPDATE cert_chain SET valid_to_utc = ? WHERE id = ?",
                newValidToUtc.toString(), id);
    }

    /**
     * 撤销链：仅当未撤销时生效，返回影响行数。
     */
    public int revoke(long id, Instant revokedAtUtc) {
        return jdbc.update("UPDATE cert_chain SET revoked = TRUE, revoked_at_utc = ?"
                + " WHERE id = ? AND revoked = FALSE", revokedAtUtc.toString(), id);
    }
}
