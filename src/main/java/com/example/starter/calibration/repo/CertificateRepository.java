package com.example.starter.calibration.repo;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.Certificate;

/**
 * 校准标准器证书持久化。证书创建后不可修改，仅可撤销；
 * 同一标准器的创建通过 instrument_lock 行锁串行化，保证并发下重叠区间最多一张成功。
 */
@Repository
public class CertificateRepository {

    private static final String COLUMNS = "id, standard_id, instrument_id, certificate_version, "
            + "valid_from, valid_to, coeff_a, offset_b, uncertainty, uncertainty_version, "
            + "single_batch_only, revoked, revoked_at, created_at";

    static final RowMapper<Certificate> MAPPER = (rs, rowNum) -> new Certificate(
            rs.getLong("id"),
            rs.getString("standard_id"),
            rs.getString("instrument_id"),
            rs.getString("certificate_version"),
            JdbcTimes.fromDb(rs.getObject("valid_from", java.time.LocalDateTime.class)),
            JdbcTimes.fromDb(rs.getObject("valid_to", java.time.LocalDateTime.class)),
            rs.getBigDecimal("coeff_a"),
            rs.getBigDecimal("offset_b"),
            rs.getBigDecimal("uncertainty"),
            rs.getString("uncertainty_version"),
            rs.getBoolean("single_batch_only"),
            rs.getBoolean("revoked"),
            JdbcTimes.fromDb(rs.getObject("revoked_at", java.time.LocalDateTime.class)),
            JdbcTimes.fromDb(rs.getObject("created_at", java.time.LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public CertificateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 获取标准器级互斥锁（须在事务内调用）：不存在锁行则插入，随后对该行 FOR UPDATE。
     */
    public void lockInstrument(String standardId) {
        try {
            jdbc.update("INSERT INTO instrument_lock (instrument_id, created_at) VALUES (?, ?)",
                    standardId, JdbcTimes.toDb(Instant.now()));
        } catch (DuplicateKeyException ignored) {
            // 锁行已存在，直接进入行锁等待
        }
        jdbc.queryForObject("SELECT instrument_id FROM instrument_lock WHERE instrument_id = ? FOR UPDATE",
                String.class, standardId);
    }

    /**
     * 插入证书并返回生成的证书 ID。(standardId, version) 唯一冲突由调用方转换为 409。
     */
    public long insert(String standardId, String instrumentId, String version,
                       Instant validFrom, Instant validTo, BigDecimal a, BigDecimal b,
                       BigDecimal uncertainty, String uncertaintyVersion,
                       boolean singleBatchOnly, Instant createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO calibration_certificate "
                            + "(standard_id, instrument_id, certificate_version, valid_from, valid_to, "
                            + "coeff_a, offset_b, uncertainty, uncertainty_version, single_batch_only, "
                            + "revoked, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, FALSE, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, standardId);
            ps.setString(2, instrumentId);
            ps.setString(3, version);
            ps.setObject(4, JdbcTimes.toDb(validFrom));
            ps.setObject(5, JdbcTimes.toDb(validTo));
            ps.setBigDecimal(6, a);
            ps.setBigDecimal(7, b);
            ps.setBigDecimal(8, uncertainty);
            ps.setString(9, uncertaintyVersion);
            ps.setBoolean(10, singleBatchOnly);
            ps.setObject(11, JdbcTimes.toDb(createdAt));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 按 ID 查询（不加锁）。
     */
    public Optional<Certificate> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM calibration_certificate WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 按 ID 查询并加行锁（须在事务内调用），用于撤销与放行的并发互斥。
     */
    public Optional<Certificate> findByIdForUpdate(long id) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM calibration_certificate WHERE id = ? FOR UPDATE", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 按标准器与证书版本精确查询（显式引用解析，不加锁）。
     * 撤销后允许同版本重建，因此优先返回未撤销证书；若仅有已撤销行则返回最新一行，
     * 由服务层给出“已撤销”等可区分原因。
     */
    public Optional<Certificate> findByStandardAndVersion(String standardId, String version) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM calibration_certificate "
                        + "WHERE standard_id = ? AND certificate_version = ? "
                        + "ORDER BY revoked ASC, id DESC",
                MAPPER, standardId, version)
                .stream().findFirst();
    }

    /**
     * 统计同一标准器下与 [from, to) 重叠的未撤销证书数量；相邻区间（端点相接）不算重叠。
     */
    public int countActiveOverlap(String standardId, Instant from, Instant to) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM calibration_certificate "
                        + "WHERE standard_id = ? AND revoked = FALSE AND valid_from < ? AND valid_to > ?",
                Integer.class, standardId, JdbcTimes.toDb(to), JdbcTimes.toDb(from));
        return count == null ? 0 : count;
    }

    /**
     * 统计同一标准器下占用某版本号的未撤销证书数量；用于在标准器锁内保证版本号唯一。
     */
    public int countActiveVersion(String standardId, String version) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM calibration_certificate "
                        + "WHERE standard_id = ? AND certificate_version = ? AND revoked = FALSE",
                Integer.class, standardId, version);
        return count == null ? 0 : count;
    }

    /**
     * 统计同一标准器下证书总数（含已撤销）；用于在标准器锁内分配不与历史重复的自动版本序号。
     */
    public int countAllForStandard(String standardId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM calibration_certificate WHERE standard_id = ?",
                Integer.class, standardId);
        return count == null ? 0 : count;
    }

    /**
     * 按测量时刻自动匹配全部有效证书：未撤销且 valid_from &lt;= measuredAt &lt; valid_to（左闭右开）。
     * 自动匹配池由“未显式版本”证书的不重叠约束保证至多一张；显式版本重叠时可能返回多张，
     * 由调用方判为歧义并要求显式引用。
     */
    public List<Certificate> findAllMatching(String standardId, Instant measuredAt) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM calibration_certificate "
                        + "WHERE standard_id = ? AND revoked = FALSE AND valid_from <= ? AND valid_to > ? "
                        + "ORDER BY valid_from, id",
                MAPPER, standardId, JdbcTimes.toDb(measuredAt), JdbcTimes.toDb(measuredAt));
    }

    /**
     * 查询某标准器的全部证书（含已撤销），按有效期起点升序，用于证书时间线。
     */
    public List<Certificate> findTimeline(String standardId) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM calibration_certificate WHERE standard_id = ? "
                        + "ORDER BY valid_from, id",
                MAPPER, standardId);
    }

    /**
     * 撤销证书（须在持有行锁的事务内调用）。
     */
    public void markRevoked(long id, Instant revokedAt) {
        jdbc.update("UPDATE calibration_certificate SET revoked = TRUE, revoked_at = ? WHERE id = ?",
                JdbcTimes.toDb(revokedAt), id);
    }
}
