package com.example.starter.calibration.repository.jdbc;

import com.example.starter.calibration.domain.Certificate;
import com.example.starter.calibration.error.ApiException;
import com.example.starter.calibration.repository.CertificateRepository;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 基于 JdbcTemplate 的证书仓库实现（MySQL）。
 *
 * <p>时间列以 DATETIME(6) 存储，写入/读取均按会话时区（+08:00）对称转换，
 * 往返保持一致；业务语义为 UTC 时刻。
 */
@Repository
public class JdbcCertificateRepository implements CertificateRepository {

    private static final RowMapper<Certificate> ROW_MAPPER = (rs, rowNum) -> new Certificate(
            rs.getLong("id"),
            rs.getString("instrument_id"),
            rs.getTimestamp("valid_from").toInstant(),
            rs.getTimestamp("valid_to").toInstant(),
            rs.getBigDecimal("coefficient_a"),
            rs.getBigDecimal("offset_b"),
            rs.getBoolean("revoked"),
            rs.getTimestamp("revoked_at") == null ? null : rs.getTimestamp("revoked_at").toInstant(),
            rs.getTimestamp("created_at").toInstant());

    private static final String SELECT_COLUMNS =
            "id, instrument_id, valid_from, valid_to, coefficient_a, offset_b, revoked, revoked_at, created_at";

    private final JdbcTemplate jdbc;

    public JdbcCertificateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Certificate insertIfNoOverlap(Certificate certificate) {
        // 锁定该仪器全部未撤销证书行（InnoDB 间隙锁阻止并发插入同仪器证书），
        // 在锁内做区间重叠检查，保证并发创建重叠证书最多一张成功。
        List<Certificate> active = jdbc.query(
                "SELECT " + SELECT_COLUMNS + " FROM calibration_certificate"
                        + " WHERE instrument_id = ? AND revoked = 0 FOR UPDATE",
                ROW_MAPPER,
                certificate.instrumentId());
        for (Certificate existing : active) {
            if (existing.overlaps(certificate.validFrom(), certificate.validTo())) {
                throw ApiException.conflict(
                        "CERTIFICATE_INTERVAL_OVERLAP",
                        "与已存在证书 " + existing.id() + " 的有效区间重叠");
            }
        }
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO calibration_certificate"
                            + " (instrument_id, valid_from, valid_to, coefficient_a, offset_b, revoked, revoked_at, created_at)"
                            + " VALUES (?, ?, ?, ?, ?, 0, NULL, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, certificate.instrumentId());
            ps.setTimestamp(2, Timestamp.from(certificate.validFrom()));
            ps.setTimestamp(3, Timestamp.from(certificate.validTo()));
            ps.setBigDecimal(4, certificate.coefficientA());
            ps.setBigDecimal(5, certificate.offsetB());
            ps.setTimestamp(6, Timestamp.from(certificate.createdAt()));
            return ps;
        }, keyHolder);
        long id = keyHolder.getKey().longValue();
        return new Certificate(
                id,
                certificate.instrumentId(),
                certificate.validFrom(),
                certificate.validTo(),
                certificate.coefficientA(),
                certificate.offsetB(),
                false,
                null,
                certificate.createdAt());
    }

    @Override
    public Optional<Certificate> findById(long id) {
        return jdbc.query(
                        "SELECT " + SELECT_COLUMNS + " FROM calibration_certificate WHERE id = ?",
                        ROW_MAPPER,
                        id)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<Certificate> findByIdForUpdate(long id) {
        return jdbc.query(
                        "SELECT " + SELECT_COLUMNS + " FROM calibration_certificate WHERE id = ? FOR UPDATE",
                        ROW_MAPPER,
                        id)
                .stream()
                .findFirst();
    }

    @Override
    public boolean revoke(long id, Instant revokedAt) {
        int updated = jdbc.update(
                "UPDATE calibration_certificate SET revoked = 1, revoked_at = ? WHERE id = ? AND revoked = 0",
                Timestamp.from(revokedAt),
                id);
        return updated > 0;
    }

    @Override
    public Optional<Certificate> findActiveCovering(String instrumentId, Instant instant) {
        return jdbc.query(
                        "SELECT " + SELECT_COLUMNS + " FROM calibration_certificate"
                                + " WHERE instrument_id = ? AND revoked = 0 AND valid_from <= ? AND valid_to > ?",
                        ROW_MAPPER,
                        instrumentId,
                        Timestamp.from(instant),
                        Timestamp.from(instant))
                .stream()
                .findFirst();
    }

    @Override
    public List<Certificate> findByInstrument(String instrumentId) {
        return jdbc.query(
                "SELECT " + SELECT_COLUMNS + " FROM calibration_certificate WHERE instrument_id = ?"
                        + " ORDER BY valid_from ASC, id ASC",
                ROW_MAPPER,
                instrumentId);
    }
}
