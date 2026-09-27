package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.CertChain;
import com.example.starter.firmware.domain.CertChainStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;

/**
 * 签名证书链数据访问。链版本在同一固件版本内唯一；有效区间为闭开区间 [valid_from, valid_to)。
 * 轮换与撤销的并发正确性由调用方持有 firmware_version 行锁保证。
 */
@Repository
public class CertChainRepository {

    private static final RowMapper<CertChain> MAPPER = (rs, n) -> new CertChain(
            rs.getLong("id"), rs.getString("model"), rs.getString("firmware_version"),
            rs.getInt("chain_version"), rs.getString("cert_fingerprint"),
            rs.getString("valid_from_utc"), rs.getString("valid_to_utc"),
            CertChainStatus.valueOf(rs.getString("status")),
            rs.getString("revoked_at_utc"), rs.getString("revoke_reason"),
            rs.getString("created_by"));

    private static final String COLUMNS = "id, model, firmware_version, chain_version, cert_fingerprint,"
            + " valid_from_utc, valid_to_utc, status, revoked_at_utc, revoke_reason, created_by";

    private final JdbcTemplate jdbc;

    public CertChainRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(String model, String firmwareVersion, int chainVersion, String fingerprint,
                       String validFromUtc, String validToUtc, String createdBy) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO firmware_cert_chain (model, firmware_version, chain_version, cert_fingerprint,"
                            + " valid_from_utc, valid_to_utc, status, created_by)"
                            + " VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', ?)",
                    new String[]{"id"});
            ps.setString(1, model);
            ps.setString(2, firmwareVersion);
            ps.setInt(3, chainVersion);
            ps.setString(4, fingerprint);
            ps.setString(5, validFromUtc);
            ps.setString(6, validToUtc);
            ps.setString(7, createdBy);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public List<CertChain> findByFirmware(String model, String firmwareVersion) {
        return jdbc.query("SELECT " + COLUMNS + " FROM firmware_cert_chain"
                + " WHERE model = ? AND firmware_version = ? ORDER BY chain_version",
                MAPPER, model, firmwareVersion);
    }

    public Optional<CertChain> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM firmware_cert_chain WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    public Optional<CertChain> findByChainVersion(String model, String firmwareVersion, int chainVersion) {
        return jdbc.query("SELECT " + COLUMNS + " FROM firmware_cert_chain"
                        + " WHERE model = ? AND firmware_version = ? AND chain_version = ?",
                MAPPER, model, firmwareVersion, chainVersion).stream().findFirst();
    }

    /**
     * 轮换收尾：旧开放链在生效时刻闭合（valid_to 置为新链生效时刻）。
     */
    public int closeChain(long id, String validToUtc) {
        return jdbc.update("UPDATE firmware_cert_chain SET valid_to_utc = ? WHERE id = ? AND valid_to_utc IS NULL",
                validToUtc, id);
    }

    /**
     * 撤销：仅当前 ACTIVE 链可撤销，返回影响行数。
     */
    public int revoke(long id, String revokedAtUtc, String reason) {
        return jdbc.update("UPDATE firmware_cert_chain SET status = 'REVOKED', revoked_at_utc = ?,"
                + " revoke_reason = ? WHERE id = ? AND status = 'ACTIVE'", revokedAtUtc, reason, id);
    }
}
