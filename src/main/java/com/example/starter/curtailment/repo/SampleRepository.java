package com.example.starter.curtailment.repo;

import com.example.starter.curtailment.domain.PowerSample;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 功率采样仓储：采样按（指令、刻度、版本）唯一追加，查询最新版本供对账使用。
 */
@Repository
public class SampleRepository {

    private static final RowMapper<PowerSample> MAPPER = (rs, rowNum) -> new PowerSample(
            rs.getLong("id"),
            rs.getLong("instruction_id"),
            rs.getString("unit_id"),
            JdbcTimes.toInstant(rs, "tick_start"),
            rs.getBigDecimal("avg_power_kw"),
            rs.getInt("sample_version"),
            rs.getString("request_id"),
            JdbcTimes.toInstant(rs, "created_at"));

    private final JdbcTemplate jdbc;

    public SampleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 追加一个采样版本，返回自增主键。 */
    public long insert(long instructionId, String unitId, Instant tickStart, java.math.BigDecimal avgPowerKw,
                       int sampleVersion, String requestId, Instant now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO power_sample "
                            + "(instruction_id, unit_id, tick_start, avg_power_kw, sample_version, request_id, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, instructionId);
            ps.setString(2, unitId);
            ps.setObject(3, JdbcTimes.toSql(tickStart));
            ps.setBigDecimal(4, avgPowerKw);
            ps.setInt(5, sampleVersion);
            ps.setString(6, requestId);
            ps.setObject(7, JdbcTimes.toSql(now));
            return ps;
        }, keyHolder);
        return Objects.requireNonNull(keyHolder.getKey()).longValue();
    }

    /** 查询某刻度当前最大版本号，不存在时返回 0。 */
    public int currentVersion(long instructionId, Instant tickStart) {
        Integer version = jdbc.queryForObject(
                "SELECT COALESCE(MAX(sample_version), 0) FROM power_sample WHERE instruction_id = ? AND tick_start = ?",
                Integer.class, instructionId, JdbcTimes.toSql(tickStart));
        return version == null ? 0 : version;
    }

    /** 查询某刻度最新版本采样。 */
    public Optional<PowerSample> findLatest(long instructionId, Instant tickStart) {
        List<PowerSample> rows = jdbc.query(
                "SELECT * FROM power_sample WHERE instruction_id = ? AND tick_start = ? "
                        + "ORDER BY sample_version DESC LIMIT 1",
                MAPPER, instructionId, JdbcTimes.toSql(tickStart));
        return rows.stream().findFirst();
    }

    /** 查询指令在 [fromInclusive, toExclusive) 内的全部采样版本，按刻度与版本升序。 */
    public List<PowerSample> findInRange(long instructionId, Instant fromInclusive, Instant toExclusive) {
        return jdbc.query(
                "SELECT * FROM power_sample WHERE instruction_id = ? AND tick_start >= ? AND tick_start < ? "
                        + "ORDER BY tick_start, sample_version",
                MAPPER, instructionId, JdbcTimes.toSql(fromInclusive), JdbcTimes.toSql(toExclusive));
    }

    /** 查询指令全部采样版本，按刻度与版本升序。 */
    public List<PowerSample> findByInstruction(long instructionId) {
        return jdbc.query("SELECT * FROM power_sample WHERE instruction_id = ? ORDER BY tick_start, sample_version",
                MAPPER, instructionId);
    }
}