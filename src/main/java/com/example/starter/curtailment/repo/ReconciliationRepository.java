package com.example.starter.curtailment.repo;

import com.example.starter.curtailment.domain.Adjustment;
import com.example.starter.curtailment.domain.Reconciliation;
import com.example.starter.curtailment.domain.ReconciliationTick;
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
 * 对账仓储：对账单、对账刻度快照与差额记录的 SQL 访问。
 */
@Repository
public class ReconciliationRepository {

    private static final RowMapper<Reconciliation> RECON_MAPPER = (rs, rowNum) -> new Reconciliation(
            rs.getLong("id"),
            rs.getLong("instruction_id"),
            JdbcTimes.toInstant(rs, "window_start"),
            rs.getInt("instruction_version"),
            rs.getBigDecimal("total_energy_kwh"),
            rs.getString("request_id"),
            JdbcTimes.toInstant(rs, "created_at"));

    private static final RowMapper<ReconciliationTick> TICK_MAPPER = (rs, rowNum) -> new ReconciliationTick(
            rs.getLong("id"),
            rs.getLong("reconciliation_id"),
            JdbcTimes.toInstant(rs, "tick_start"),
            rs.getBigDecimal("avg_power_kw"),
            rs.getInt("sample_version"),
            rs.getBigDecimal("energy_kwh"));

    private static final RowMapper<Adjustment> ADJ_MAPPER = (rs, rowNum) -> new Adjustment(
            rs.getLong("id"),
            rs.getLong("reconciliation_id"),
            rs.getLong("instruction_id"),
            JdbcTimes.toInstant(rs, "tick_start"),
            rs.getBigDecimal("old_avg_power_kw"),
            rs.getBigDecimal("new_avg_power_kw"),
            rs.getBigDecimal("delta_energy_kwh"),
            rs.getInt("sample_version"),
            JdbcTimes.toInstant(rs, "created_at"));

    private final JdbcTemplate jdbc;

    public ReconciliationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 新增对账单，返回自增主键。 */
    public long insertReconciliation(long instructionId, Instant windowStart, int instructionVersion,
                                     java.math.BigDecimal totalEnergyKwh, String requestId, Instant now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO reconciliation "
                            + "(instruction_id, window_start, instruction_version, total_energy_kwh, request_id, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, instructionId);
            ps.setObject(2, JdbcTimes.toSql(windowStart));
            ps.setInt(3, instructionVersion);
            ps.setBigDecimal(4, totalEnergyKwh);
            ps.setString(5, requestId);
            ps.setObject(6, JdbcTimes.toSql(now));
            return ps;
        }, keyHolder);
        return Objects.requireNonNull(keyHolder.getKey()).longValue();
    }

    /** 新增对账刻度快照。 */
    public void insertTick(long reconciliationId, Instant tickStart, java.math.BigDecimal avgPowerKw,
                           int sampleVersion, java.math.BigDecimal energyKwh) {
        jdbc.update("INSERT INTO reconciliation_tick "
                        + "(reconciliation_id, tick_start, avg_power_kw, sample_version, energy_kwh) VALUES (?, ?, ?, ?, ?)",
                reconciliationId, JdbcTimes.toSql(tickStart), avgPowerKw, sampleVersion, energyKwh);
    }

    /** 按指令与窗口起点查询对账单（窗口唯一约束保证不重复累计）。 */
    public Optional<Reconciliation> findByWindow(long instructionId, Instant windowStart) {
        List<Reconciliation> rows = jdbc.query(
                "SELECT * FROM reconciliation WHERE instruction_id = ? AND window_start = ?",
                RECON_MAPPER, instructionId, JdbcTimes.toSql(windowStart));
        return rows.stream().findFirst();
    }

    /**
     * 查询覆盖某刻度的对账单：窗口为 [window_start, window_start + 1 小时)，
     * 等价于 window_start &lt;= tick 且 window_start &gt; tick - 1 小时。
     */
    public Optional<Reconciliation> findCoveringTick(long instructionId, Instant tickStart) {
        List<Reconciliation> rows = jdbc.query(
                "SELECT * FROM reconciliation WHERE instruction_id = ? AND window_start <= ? AND window_start > ? "
                        + "ORDER BY window_start DESC LIMIT 1",
                RECON_MAPPER, instructionId, JdbcTimes.toSql(tickStart),
                JdbcTimes.toSql(tickStart.minusSeconds(3600)));
        return rows.stream().findFirst();
    }

    /** 查询指令全部对账单，按窗口起点升序。 */
    public List<Reconciliation> findByInstruction(long instructionId) {
        return jdbc.query("SELECT * FROM reconciliation WHERE instruction_id = ? ORDER BY window_start",
                RECON_MAPPER, instructionId);
    }

    /** 查询对账单的全部刻度快照，按刻度起点升序。 */
    public List<ReconciliationTick> findTicks(long reconciliationId) {
        return jdbc.query("SELECT * FROM reconciliation_tick WHERE reconciliation_id = ? ORDER BY tick_start",
                TICK_MAPPER, reconciliationId);
    }

    /** 追加差额记录。 */
    public void insertAdjustment(long reconciliationId, long instructionId, Instant tickStart,
                                 java.math.BigDecimal oldAvgPowerKw, java.math.BigDecimal newAvgPowerKw,
                                 java.math.BigDecimal deltaEnergyKwh, int sampleVersion, Instant now) {
        jdbc.update("INSERT INTO reconciliation_adjustment "
                        + "(reconciliation_id, instruction_id, tick_start, old_avg_power_kw, new_avg_power_kw, "
                        + "delta_energy_kwh, sample_version, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                reconciliationId, instructionId, JdbcTimes.toSql(tickStart), oldAvgPowerKw, newAvgPowerKw,
                deltaEnergyKwh, sampleVersion, JdbcTimes.toSql(now));
    }

    /** 查询指令的差额链，按记录 id 升序（即时间顺序）。 */
    public List<Adjustment> findAdjustmentsByInstruction(long instructionId) {
        return jdbc.query("SELECT * FROM reconciliation_adjustment WHERE instruction_id = ? ORDER BY id",
                ADJ_MAPPER, instructionId);
    }
}