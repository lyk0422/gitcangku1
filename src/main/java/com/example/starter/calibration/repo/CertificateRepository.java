package com.example.starter.calibration.repo;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.Certificate;

/**
 * 校准证书持久化。证书创建后不可修改，仅可撤销；
 * 同一仪器的创建通过 instrument_lock 行锁串行化，保证并发下重叠区间最多一张成功。
 */
@Repository
public class CertificateRepository {

    private static final RowMapper<Certificate> MAPPER = (rs, rowNum) -> new Certificate(
            rs.getLong("id"),
            rs.getString("instrument_id"),
            JdbcTimes.fromDb(rs.getObject("valid_from", LocalDateTime.class)),
            JdbcTimes.fromDb(rs.getObject("valid_to", LocalDateTime.class)),
            rs.getBigDecimal("coeff_a"),
            rs.getBigDecimal("offset_b"),
            rs.getBoolean("revoked"),
            JdbcTimes.fromDb(rs.getObject("revoked_at", LocalDateTime.class)),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public CertificateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 获取仪器级互斥锁（须在事务内调用）：不存在锁行则插入，随后对该行 FOR UPDATE。
     */
    public void lockInstrument(String instrumentId) {
        try {
            jdbc.update("INSERT INTO instrument_lock (instrument_id, created_at) VALUES (?, ?)",
                    instrumentId, JdbcTimes.toDb(Instant.now()));
        } catch (DuplicateKeyException ignored) {
            // 锁行已存在，直接进入行锁等待
        }
        jdbc.queryForObject("SELECT instrument_id FROM instrument_lock WHERE instrument_id = ? FOR UPDATE",
                String.class, instrumentId);
    }

    /**
     * 插入证书并返回生成的证书 ID。
     */
    public long insert(String instrumentId, Instant validFrom, Instant validTo,
                       java.math.BigDecimal a, java.math.BigDecimal b, Instant createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO calibration_certificate "
                            + "(instrument_id, valid_from, valid_to, coeff_a, offset_b, revoked, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, FALSE, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, instrumentId);
            ps.setObject(2, JdbcTimes.toDb(validFrom));
            ps.setObject(3, JdbcTimes.toDb(validTo));
            ps.setBigDecimal(4, a);
            ps.setBigDecimal(5, b);
            ps.setObject(6, JdbcTimes.toDb(createdAt));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 按 ID 查询（不加锁）。
     */
    public Optional<Certificate> findById(long id) {
        return jdbc.query("SELECT * FROM calibration_certificate WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 按 ID 查询并加行锁（须在事务内调用），用于撤销与放行的并发互斥。
     */
    public Optional<Certificate> findByIdForUpdate(long id) {
        return jdbc.query("SELECT * FROM calibration_certificate WHERE id = ? FOR UPDATE", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 统计同一仪器下与 [from, to) 重叠的未撤销证书数量；相邻区间（端点相接）不算重叠。
     */
    public int countActiveOverlap(String instrumentId, Instant from, Instant to) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM calibration_certificate "
                        + "WHERE instrument_id = ? AND revoked = FALSE AND valid_from < ? AND valid_to > ?",
                Integer.class, instrumentId, JdbcTimes.toDb(to), JdbcTimes.toDb(from));
        return count == null ? 0 : count;
    }

    /**
     * 按测量时刻匹配唯一有效证书：未撤销且 valid_from &lt;= measuredAt &lt; valid_to。
     */
    public Optional<Certificate> findMatching(String instrumentId, Instant measuredAt) {
        return jdbc.query(
                "SELECT * FROM calibration_certificate "
                        + "WHERE instrument_id = ? AND revoked = FALSE AND valid_from <= ? AND valid_to > ?",
                MAPPER, instrumentId, JdbcTimes.toDb(measuredAt), JdbcTimes.toDb(measuredAt))
                .stream().findFirst();
    }

    /**
     * 撤销证书（须在持有行锁的事务内调用）。
     */
    public void markRevoked(long id, Instant revokedAt) {
        jdbc.update("UPDATE calibration_certificate SET revoked = TRUE, revoked_at = ? WHERE id = ?",
                JdbcTimes.toDb(revokedAt), id);
    }
}
