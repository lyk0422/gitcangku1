package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.CertChain;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;

/**
 * 证书链数据访问。同一固件版本的链集合按 (firmware_version, chain_version) 唯一；
 * 区间不重叠与轮换顺序由服务层在串行化临界区内校验。
 */
@Repository
public class CertChainRepository {

    private static final RowMapper<CertChain> MAPPER = (rs, rowNum) -> new CertChain(
            rs.getLong("id"), rs.getString("firmware_version"), rs.getString("chain_version"),
            rs.getString("valid_from_utc"), rs.getString("valid_to_utc"),
            rs.getString("operator"), rs.getString("revoked_at_utc"));

    private static final String COLUMNS = "id, firmware_version, chain_version, valid_from_utc,"
            + " valid_to_utc, operator, revoked_at_utc";

    private final JdbcTemplate jdbc;

    public CertChainRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(String firmwareVersion, String chainVersion, String validFromUtc,
                       String validToUtc, String operator) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO cert_chain (firmware_version, chain_version, valid_from_utc,"
                            + " valid_to_utc, operator) VALUES (?, ?, ?, ?, ?)",
                    new String[]{"id"});
            ps.setString(1, firmwareVersion);
            ps.setString(2, chainVersion);
            ps.setString(3, validFromUtc);
            ps.setString(4, validToUtc);
            ps.setString(5, operator);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<CertChain> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM cert_chain WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    public Optional<CertChain> findByIdForUpdate(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM cert_chain WHERE id = ? FOR UPDATE", MAPPER, id)
                .stream().findFirst();
    }

    public Optional<CertChain> findByVersionAndChainVersion(String firmwareVersion, String chainVersion) {
        return jdbc.query("SELECT " + COLUMNS + " FROM cert_chain"
                        + " WHERE firmware_version = ? AND chain_version = ?",
                MAPPER, firmwareVersion, chainVersion).stream().findFirst();
    }

    /**
     * 某固件版本已登记的全部证书链，按生效时刻升序。
     */
    public List<CertChain> findByFirmwareVersion(String firmwareVersion) {
        return jdbc.query("SELECT " + COLUMNS + " FROM cert_chain WHERE firmware_version = ?"
                + " ORDER BY valid_from_utc, id", MAPPER, firmwareVersion);
    }

    /**
     * 撤销：仅当未撤销时写入撤销时刻，返回影响行数。
     */
    public int revoke(long id, String revokedAtUtc) {
        return jdbc.update("UPDATE cert_chain SET revoked_at_utc = ? WHERE id = ? AND revoked_at_utc IS NULL",
                revokedAtUtc, id);
    }
}
