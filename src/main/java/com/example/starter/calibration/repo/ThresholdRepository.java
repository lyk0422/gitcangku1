package com.example.starter.calibration.repo;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.UncertaintyThreshold;

/**
 * 仪器不确定度阈值版本持久化。版本只追加不改写；某时刻生效版本为
 * valid_from &lt;= 时刻的最新未停用版本（左闭右开）。
 */
@Repository
public class ThresholdRepository {

    private static final RowMapper<UncertaintyThreshold> MAPPER = (rs, rowNum) -> new UncertaintyThreshold(
            rs.getLong("id"),
            rs.getString("instrument_id"),
            rs.getInt("version"),
            rs.getBigDecimal("threshold_value"),
            JdbcTimes.fromDb(rs.getObject("valid_from", LocalDateTime.class)),
            rs.getBoolean("revoked"),
            JdbcTimes.fromDb(rs.getObject("revoked_at", LocalDateTime.class)),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public ThresholdRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加阈值版本并返回行 ID；(instrument_id, version) 冲突时抛 DuplicateKeyException。
     */
    public long insert(String instrumentId, int version, java.math.BigDecimal thresholdValue,
                       Instant validFrom, Instant createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO uncertainty_threshold "
                            + "(instrument_id, version, threshold_value, valid_from, revoked, created_at) "
                            + "VALUES (?, ?, ?, ?, FALSE, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, instrumentId);
            ps.setInt(2, version);
            ps.setBigDecimal(3, thresholdValue);
            ps.setObject(4, JdbcTimes.toDb(validFrom));
            ps.setObject(5, JdbcTimes.toDb(createdAt));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 某仪器当前最大阈值版本号；无记录返回 0。
     */
    public int maxVersion(String instrumentId) {
        Integer version = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version), 0) FROM uncertainty_threshold WHERE instrument_id = ?",
                Integer.class, instrumentId);
        return version == null ? 0 : version;
    }

    /**
     * 查询某仪器最新版本的 valid_from（不带锁），用于校验新版本生效起点严格晚于旧版本。
     */
    public Optional<Instant> findLatestValidFrom(String instrumentId) {
        return jdbc.queryForList(
                        "SELECT valid_from FROM uncertainty_threshold WHERE instrument_id = ? "
                                + "ORDER BY version DESC LIMIT 1",
                        LocalDateTime.class, instrumentId)
                .stream().findFirst()
                .map(v -> JdbcTimes.fromDb(v));
    }

    /**
     * 查询某仪器最新版本并加行锁（须在事务内调用），串行化阈值修订。
     */
    public Optional<UncertaintyThreshold> findLatestForUpdate(String instrumentId) {
        return jdbc.query("SELECT * FROM uncertainty_threshold WHERE instrument_id = ? "
                        + "ORDER BY version DESC LIMIT 1 FOR UPDATE", MAPPER, instrumentId)
                .stream().findFirst();
    }

    /**
     * 匹配某时刻生效的阈值版本：valid_from &lt;= at 的最新未停用版本（左闭右开）。
     * 不存在任何未停用版本时返回空（阈值缺失）。
     */
    public Optional<UncertaintyThreshold> findEffectiveAt(String instrumentId, Instant at) {
        return jdbc.query(
                "SELECT * FROM uncertainty_threshold "
                        + "WHERE instrument_id = ? AND revoked = FALSE AND valid_from <= ? "
                        + "ORDER BY version DESC LIMIT 1",
                MAPPER, instrumentId, JdbcTimes.toDb(at))
                .stream().findFirst();
    }

    /**
     * 按行 ID 查询（不加锁）。
     */
    public Optional<UncertaintyThreshold> findById(long id) {
        return jdbc.query("SELECT * FROM uncertainty_threshold WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 列出某仪器全部阈值版本（版本号升序），用于版本历史查询。
     */
    public List<UncertaintyThreshold> findByInstrument(String instrumentId) {
        return jdbc.query(
                "SELECT * FROM uncertainty_threshold WHERE instrument_id = ? ORDER BY version",
                MAPPER, instrumentId);
    }
}
