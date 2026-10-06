package com.example.starter.calibration.repo;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.ResolutionConfig;

/**
 * 仪器分辨率格点配置持久化。每台仪器一行当前配置（instrument_id 唯一）；
 * 创建与更新通过 instrument_lock 行锁串行化，版本从 1 起单调递增。
 */
@Repository
public class ResolutionConfigRepository {

    private static final RowMapper<ResolutionConfig> MAPPER = (rs, rowNum) -> new ResolutionConfig(
            rs.getLong("id"),
            rs.getString("instrument_id"),
            rs.getBigDecimal("step"),
            rs.getBigDecimal("origin"),
            rs.getInt("version"),
            rs.getString("updated_by"),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)),
            JdbcTimes.fromDb(rs.getObject("updated_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public ResolutionConfigRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按仪器查询当前配置（不加锁）；未配置时为空，测量沿用原规则。
     */
    public Optional<ResolutionConfig> findByInstrument(String instrumentId) {
        return jdbc.query("SELECT * FROM resolution_config WHERE instrument_id = ?",
                        MAPPER, instrumentId)
                .stream().findFirst();
    }

    /**
     * 插入首版配置（版本 1）并返回生成的 ID；instrument_id 冲突时抛出 DuplicateKeyException。
     */
    public long insert(ResolutionConfig config) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO resolution_config "
                            + "(instrument_id, step, origin, version, updated_by, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, config.instrumentId());
            ps.setBigDecimal(2, config.step());
            ps.setBigDecimal(3, config.origin());
            ps.setInt(4, config.version());
            ps.setString(5, config.updatedBy());
            ps.setObject(6, JdbcTimes.toDb(config.createdAt()));
            ps.setObject(7, JdbcTimes.toDb(config.updatedAt()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 更新现有配置为新版本（须在持有仪器锁的事务内调用）。
     */
    public void update(ResolutionConfig config) {
        jdbc.update("UPDATE resolution_config SET step = ?, origin = ?, version = ?, updated_by = ?, "
                        + "updated_at = ? WHERE id = ?",
                config.step(), config.origin(), config.version(), config.updatedBy(),
                JdbcTimes.toDb(config.updatedAt()), config.id());
    }
}
