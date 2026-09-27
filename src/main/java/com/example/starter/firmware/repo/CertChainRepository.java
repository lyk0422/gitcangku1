package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.CertChainStatus;
import com.example.starter.firmware.domain.FirmwareCertChain;
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
 * 签名证书链数据访问。区间字段以 UTC ISO-8601 字符串存储，按字典序比较与半开区间时间顺序一致。
 * 同一固件 (firmware_id, chain_version) 唯一；区间不重叠由服务层校验（MySQL 无排他约束可移植写法）。
 */
@Repository
public class CertChainRepository {

    private static final RowMapper<FirmwareCertChain> MAPPER = (rs, n) -> new FirmwareCertChain(
            rs.getLong("id"), rs.getLong("firmware_id"), rs.getInt("chain_version"),
            rs.getString("subject_dn"), rs.getString("issuer_dn"), rs.getString("fingerprint_sha256"),
            Instant.parse(rs.getString("valid_from_utc")),
            rs.getString("valid_to_utc") == null ? null : Instant.parse(rs.getString("valid_to_utc")),
            CertChainStatus.valueOf(rs.getString("status")),
            rs.getString("revoked_at_utc") == null ? null : Instant.parse(rs.getString("revoked_at_utc")));

    private static final String COLUMNS = "id, firmware_id, chain_version, subject_dn, issuer_dn,"
            + " fingerprint_sha256, valid_from_utc, valid_to_utc, status, revoked_at_utc";

    private final JdbcTemplate jdbc;

    public CertChainRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 登记新链，返回链ID；chain_version 由服务层按固件内最大值加一。
     */
    public long insert(long firmwareId, int chainVersion, String subjectDn, String issuerDn,
                       String fingerprintSha256, Instant validFrom, Instant validTo) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO firmware_cert_chain (firmware_id, chain_version, subject_dn, issuer_dn,"
                            + " fingerprint_sha256, valid_from_utc, valid_to_utc, status, revoked_at_utc)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', NULL)",
                    new String[]{"id"});
            ps.setLong(1, firmwareId);
            ps.setInt(2, chainVersion);
            ps.setString(3, subjectDn);
            ps.setString(4, issuerDn);
            ps.setString(5, fingerprintSha256);
            ps.setString(6, validFrom.toString());
            if (validTo == null) {
                ps.setNull(7, java.sql.Types.VARCHAR);
            } else {
                ps.setString(7, validTo.toString());
            }
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<FirmwareCertChain> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM firmware_cert_chain WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    public Optional<FirmwareCertChain> findByIdForUpdate(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM firmware_cert_chain WHERE id = ? FOR UPDATE", MAPPER, id)
                .stream().findFirst();
    }

    public Optional<FirmwareCertChain> findByFirmwareAndVersion(long firmwareId, int chainVersion) {
        return jdbc.query("SELECT " + COLUMNS + " FROM firmware_cert_chain"
                + " WHERE firmware_id = ? AND chain_version = ?", MAPPER, firmwareId, chainVersion)
                .stream().findFirst();
    }

    public List<FirmwareCertChain> findByFirmware(long firmwareId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM firmware_cert_chain"
                + " WHERE firmware_id = ? ORDER BY chain_version", MAPPER, firmwareId);
    }

    /**
     * 锁定并读取某固件的全部链（按链版本排序），供轮换/撤销事务内做串行化区间裁决。
     */
    public List<FirmwareCertChain> findByFirmwareForUpdate(long firmwareId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM firmware_cert_chain"
                + " WHERE firmware_id = ? ORDER BY chain_version FOR UPDATE", MAPPER, firmwareId);
    }

    public int maxChainVersion(long firmwareId) {
        Integer max = jdbc.queryForObject(
                "SELECT MAX(chain_version) FROM firmware_cert_chain WHERE firmware_id = ?",
                Integer.class, firmwareId);
        return max == null ? 0 : max;
    }

    /**
     * 将开放区间链截断至给定时刻（轮换旧链下线），仅当链仍 ACTIVE 且当前为开放区间时生效。
     */
    public int truncateOpenEnd(long chainId, Instant to) {
        return jdbc.update("UPDATE firmware_cert_chain SET valid_to_utc = ?"
                + " WHERE id = ? AND status = 'ACTIVE' AND valid_to_utc IS NULL", to.toString(), chainId);
    }

    /**
     * 撤销开放区间链：置 REVOKED，valid_to 截止到给定 end（撤销时刻或未来链的生效时刻，零窗口）。
     */
    public int revokeOpenEnd(long chainId, Instant revokedAt, Instant end) {
        return jdbc.update("UPDATE firmware_cert_chain SET status = 'REVOKED', revoked_at_utc = ?,"
                + " valid_to_utc = ? WHERE id = ? AND status = 'ACTIVE' AND valid_to_utc IS NULL",
                revokedAt.toString(), end.toString(), chainId);
    }

    /**
     * 撤销已有到期时刻的链：置 REVOKED 并记录撤销时刻，不延长既有区间。
     */
    public int revokeKeepEnd(long chainId, Instant revokedAt) {
        return jdbc.update("UPDATE firmware_cert_chain SET status = 'REVOKED', revoked_at_utc = ?"
                + " WHERE id = ? AND status = 'ACTIVE'", revokedAt.toString(), chainId);
    }
}
