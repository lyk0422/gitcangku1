package com.example.starter.calibration.repo;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.ThresholdVersion;

/**
 * 仪器不确定度阈值版本持久化。版本只增不改；同一仪器区间不得重叠（相邻合法），
 * 开放区间（valid_to 为 NULL）同一仪器至多一个。创建通过 instrument_lock 行锁串行化。
 */
@Repository
public class ThresholdVersionRepository {

    private static final RowMapper<ThresholdVersion> MAPPER = (rs, rowNum) -> new ThresholdVersion(
            rs.getLong("id"),
            rs.getString("instrument_id"),
            rs.getLong("version"),
            JdbcTimes.fromDb(rs.getObject("valid_from", LocalDateTime.class)),
            JdbcTimes.fromDb(rs.getObject("valid_to", LocalDateTime.class)),
            rs.getBigDecimal("max_uncertainty"),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public ThresholdVersionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入新版本并返回生成的记录 ID。version 由调用方在仪器锁内计算。
     */
    public long insert(String instrumentId, long version, Instant validFrom, Instant validTo,
                       java.math.BigDecimal maxUncertainty, Instant createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO instrument_threshold_version "
                            + "(instrument_id, version, valid_from, valid_to, max_uncertainty, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, instrumentId);
            ps.setLong(2, version);
            ps.setObject(3, JdbcTimes.toDb(validFrom));
            ps.setObject(4, JdbcTimes.toDb(validTo));
            ps.setBigDecimal(5, maxUncertainty);
            ps.setObject(6, JdbcTimes.toDb(createdAt));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 仪器内下一个版本号（须在持有仪器锁的事务内调用）。
     */
    public long nextVersion(String instrumentId) {
        Long max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version), 0) FROM instrument_threshold_version WHERE instrument_id = ?",
                Long.class, instrumentId);
        return (max == null ? 0L : max) + 1;
    }

    /**
     * 统计与 [from, to) 重叠的“有界”版本数量；to 为 null 表示开放区间；相邻端点不算重叠。
     * 不统计开放区间行（开放区间行的衔接由 {@link #findOpenEnded} 与 {@link #closeOpenEnded} 处理）。
     */
    public int countBoundedOverlap(String instrumentId, Instant from, Instant to) {
        if (to == null) {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM instrument_threshold_version "
                            + "WHERE instrument_id = ? AND valid_to IS NOT NULL AND valid_to > ?",
                    Integer.class, instrumentId, JdbcTimes.toDb(from));
            return count == null ? 0 : count;
        }
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM instrument_threshold_version "
                        + "WHERE instrument_id = ? AND valid_to IS NOT NULL "
                        + "AND valid_from < ? AND valid_to > ?",
                Integer.class, instrumentId, JdbcTimes.toDb(to), JdbcTimes.toDb(from));
        return count == null ? 0 : count;
    }

    /**
     * 查询某仪器当前的开放区间版本（至多一个，按版本降序）。
     */
    public java.util.List<ThresholdVersion> findOpenEnded(String instrumentId) {
        return jdbc.query(
                "SELECT * FROM instrument_threshold_version WHERE instrument_id = ? AND valid_to IS NULL "
                        + "ORDER BY version DESC",
                MAPPER, instrumentId);
    }

    /**
     * 关闭开放区间版本：将其终点设为 newValidTo（新版本起点），实现时间轴衔接。
     * 阈值取值与版本 ID 不变，已固化的旧放行快照不受影响。
     */
    public void closeOpenEnded(long id, Instant newValidTo) {
        jdbc.update("UPDATE instrument_threshold_version SET valid_to = ? WHERE id = ? AND valid_to IS NULL",
                JdbcTimes.toDb(newValidTo), id);
    }

    /**
     * 按时刻查询生效阈值版本（不加锁）：valid_from &lt;= at &lt; valid_to；无版本返回空。
     */
    public Optional<ThresholdVersion> findEffectiveAt(String instrumentId, Instant at) {
        return jdbc.query(
                "SELECT * FROM instrument_threshold_version "
                        + "WHERE instrument_id = ? AND valid_from <= ? AND (valid_to IS NULL OR valid_to > ?) "
                        + "ORDER BY version",
                MAPPER, instrumentId, JdbcTimes.toDb(at), JdbcTimes.toDb(at))
                .stream().findFirst();
    }

    /**
     * 按记录 ID 查询并加行锁（须在事务内调用），用于放行时固化阈值快照。
     */
    public Optional<ThresholdVersion> findByIdForUpdate(long id) {
        return jdbc.query("SELECT * FROM instrument_threshold_version WHERE id = ? FOR UPDATE",
                MAPPER, id).stream().findFirst();
    }

    /**
     * 按记录 ID 查询（不加锁）。
     */
    public Optional<ThresholdVersion> findById(long id) {
        return jdbc.query("SELECT * FROM instrument_threshold_version WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }
}
