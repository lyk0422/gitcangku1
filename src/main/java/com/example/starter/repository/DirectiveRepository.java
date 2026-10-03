package com.example.starter.repository;

import com.example.starter.repository.Rows.DifferenceRow;
import com.example.starter.repository.Rows.DirectiveRow;
import com.example.starter.repository.Rows.FrozenSampleRow;
import com.example.starter.repository.Rows.ReconRow;
import com.example.starter.repository.Rows.SampleRow;
import java.math.BigDecimal;
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
 * 限发领域数据访问；所有写路径先对指令行 SELECT ... FOR UPDATE 加锁，
 * 保证确认/撤销竞争、采样更正与对账并发时读取到一致的快照。
 */
@Repository
public class DirectiveRepository {

    private static final RowMapper<DirectiveRow> DIRECTIVE_MAPPER = (rs, n) -> new DirectiveRow(
            rs.getLong("id"),
            rs.getString("unit_id"),
            rs.getTimestamp("start_utc").toInstant(),
            rs.getTimestamp("end_utc").toInstant(),
            rs.getInt("max_power_kw"),
            rs.getString("status"),
            rs.getInt("directive_version"),
            rs.getString("confirm_request_id"),
            rs.getString("confirmed_by"),
            rs.getTimestamp("confirmed_utc") == null ? null : rs.getTimestamp("confirmed_utc").toInstant());

    private static final RowMapper<SampleRow> SAMPLE_MAPPER = (rs, n) -> new SampleRow(
            rs.getLong("id"),
            rs.getLong("directive_id"),
            rs.getTimestamp("tick_utc").toInstant(),
            rs.getInt("sample_version"),
            rs.getInt("avg_power_kw"),
            rs.getTimestamp("reported_utc").toInstant());

    private static final RowMapper<ReconRow> RECON_MAPPER = (rs, n) -> new ReconRow(
            rs.getLong("id"),
            rs.getLong("directive_id"),
            rs.getInt("recon_version"),
            rs.getInt("directive_version"),
            rs.getString("request_id"),
            rs.getString("auditor"),
            rs.getBigDecimal("total_energy_kwh"),
            rs.getTimestamp("created_utc").toInstant());

    private static final RowMapper<FrozenSampleRow> FROZEN_MAPPER = (rs, n) -> new FrozenSampleRow(
            rs.getTimestamp("tick_utc").toInstant(),
            rs.getInt("sample_version"),
            rs.getInt("avg_power_kw"),
            rs.getBigDecimal("energy_kwh"));

    private static final RowMapper<DifferenceRow> DIFF_MAPPER = (rs, n) -> new DifferenceRow(
            rs.getLong("id"),
            rs.getLong("reconciliation_id"),
            rs.getLong("previous_reconciliation_id"),
            rs.getBigDecimal("delta_kwh"),
            rs.getTimestamp("created_utc").toInstant());

    private final JdbcTemplate jdbc;

    public DirectiveRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 锁定机组行（不存在返回 false），用于串行化同机组指令创建。 */
    public boolean lockUnit(String unitId) {
        Integer found = jdbc.query("SELECT 1 FROM power_unit WHERE unit_id = ? FOR UPDATE",
                rs -> rs.next() ? 1 : null, unitId);
        return found != null;
    }

    /** 统计同机组未撤销且与 [startUtc, endUtc) 重叠的指令数。 */
    public int countOverlapping(String unitId, Instant startUtc, Instant endUtc) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM curtailment_directive "
                        + "WHERE unit_id = ? AND status <> 'CANCELLED' AND start_utc < ? AND end_utc > ?",
                Integer.class, unitId, Timestamp.from(endUtc), Timestamp.from(startUtc));
        return count == null ? 0 : count;
    }

    /** 插入指令，返回自增主键。 */
    public long insertDirective(String unitId, Instant startUtc, Instant endUtc, int maxPowerKw, Instant createdUtc) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO curtailment_directive "
                            + "(unit_id, start_utc, end_utc, max_power_kw, status, directive_version, created_utc) "
                            + "VALUES (?, ?, ?, ?, 'ISSUED', 1, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, unitId);
            ps.setTimestamp(2, Timestamp.from(startUtc));
            ps.setTimestamp(3, Timestamp.from(endUtc));
            ps.setInt(4, maxPowerKw);
            ps.setTimestamp(5, Timestamp.from(createdUtc));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /** 按主键锁定指令行（FOR UPDATE），不存在返回空。 */
    public Optional<DirectiveRow> lockDirective(long id) {
        return jdbc.query("SELECT * FROM curtailment_directive WHERE id = ? FOR UPDATE",
                DIRECTIVE_MAPPER, id).stream().findFirst();
    }

    /** 按主键读取指令行（不加锁），不存在返回空。 */
    public Optional<DirectiveRow> findDirective(long id) {
        return jdbc.query("SELECT * FROM curtailment_directive WHERE id = ?",
                DIRECTIVE_MAPPER, id).stream().findFirst();
    }

    /** 将 ISSUED 指令原子置为 CONFIRMED；返回受影响行数（0 表示竞争失败）。 */
    public int confirmDirective(long id, String requestId, String confirmedBy, Instant confirmedUtc) {
        return jdbc.update(
                "UPDATE curtailment_directive SET status = 'CONFIRMED', confirm_request_id = ?, "
                        + "confirmed_by = ?, confirmed_utc = ? WHERE id = ? AND status = 'ISSUED'",
                requestId, confirmedBy, Timestamp.from(confirmedUtc), id);
    }

    /** 将 ISSUED 指令原子置为 CANCELLED；返回受影响行数（0 表示竞争失败）。 */
    public int cancelDirective(long id) {
        return jdbc.update(
                "UPDATE curtailment_directive SET status = 'CANCELLED' WHERE id = ? AND status = 'ISSUED'", id);
    }

    /** 将 CONFIRMED 指令置为 ENDED；ENDED 不因后续更正回退。 */
    public int markEnded(long id) {
        return jdbc.update(
                "UPDATE curtailment_directive SET status = 'ENDED' WHERE id = ? AND status = 'CONFIRMED'", id);
    }

    /** 查询某刻度当前最大采样版本；无采样返回空。 */
    public Optional<Integer> latestSampleVersion(long directiveId, Instant tickUtc) {
        return jdbc.query("SELECT MAX(sample_version) AS v FROM directive_sample "
                        + "WHERE directive_id = ? AND tick_utc = ?",
                rs -> rs.next() && rs.getObject("v") != null ? Optional.of(rs.getInt("v")) : Optional.empty(),
                directiveId, Timestamp.from(tickUtc));
    }

    /** 插入采样新版本。 */
    public void insertSample(long directiveId, Instant tickUtc, int sampleVersion, int avgPowerKw, Instant reportedUtc) {
        jdbc.update("INSERT INTO directive_sample "
                        + "(directive_id, tick_utc, sample_version, avg_power_kw, reported_utc) VALUES (?, ?, ?, ?, ?)",
                directiveId, Timestamp.from(tickUtc), sampleVersion, avgPowerKw, Timestamp.from(reportedUtc));
    }

    /** 读取指令下每个刻度的最新版本采样。 */
    public List<SampleRow> latestSamples(long directiveId) {
        return jdbc.query("SELECT s.* FROM directive_sample s JOIN ("
                        + "SELECT tick_utc, MAX(sample_version) AS v FROM directive_sample "
                        + "WHERE directive_id = ? GROUP BY tick_utc) latest "
                        + "ON s.tick_utc = latest.tick_utc AND s.sample_version = latest.v "
                        + "WHERE s.directive_id = ? ORDER BY s.tick_utc",
                SAMPLE_MAPPER, directiveId, directiveId);
    }

    /** 按幂等 requestId 查询对账单。 */
    public Optional<ReconRow> findReconByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM reconciliation WHERE request_id = ?",
                RECON_MAPPER, requestId).stream().findFirst();
    }

    /** 查询指令最新版本的对账单。 */
    public Optional<ReconRow> latestRecon(long directiveId) {
        return jdbc.query("SELECT * FROM reconciliation WHERE directive_id = ? "
                        + "ORDER BY recon_version DESC LIMIT 1",
                RECON_MAPPER, directiveId).stream().findFirst();
    }

    /** 插入对账单，返回自增主键。 */
    public long insertRecon(long directiveId, int reconVersion, int directiveVersion,
                            String requestId, String auditor, BigDecimal totalEnergyKwh, Instant createdUtc) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO reconciliation "
                            + "(directive_id, recon_version, directive_version, request_id, auditor, "
                            + "total_energy_kwh, created_utc) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, directiveId);
            ps.setInt(2, reconVersion);
            ps.setInt(3, directiveVersion);
            ps.setString(4, requestId);
            ps.setString(5, auditor);
            ps.setBigDecimal(6, totalEnergyKwh);
            ps.setTimestamp(7, Timestamp.from(createdUtc));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /** 插入对账固化的采样快照行。 */
    public void insertReconSample(long reconId, long directiveId, Instant tickUtc,
                                  int sampleVersion, int avgPowerKw, BigDecimal energyKwh) {
        jdbc.update("INSERT INTO reconciliation_sample "
                        + "(reconciliation_id, directive_id, tick_utc, sample_version, avg_power_kw, energy_kwh) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                reconId, directiveId, Timestamp.from(tickUtc), sampleVersion, avgPowerKw, energyKwh);
    }

    /** 读取对账单固化的全部采样快照。 */
    public List<FrozenSampleRow> frozenSamples(long reconId) {
        return jdbc.query("SELECT * FROM reconciliation_sample WHERE reconciliation_id = ? ORDER BY tick_utc",
                FROZEN_MAPPER, reconId);
    }

    /** 追加差额链记录。 */
    public void insertDifference(long directiveId, long reconId, long previousReconId,
                                 BigDecimal deltaKwh, Instant createdUtc) {
        jdbc.update("INSERT INTO reconciliation_difference "
                        + "(directive_id, reconciliation_id, previous_reconciliation_id, delta_kwh, created_utc) "
                        + "VALUES (?, ?, ?, ?, ?)",
                directiveId, reconId, previousReconId, deltaKwh, Timestamp.from(createdUtc));
    }

    /** 按指令查询全部对账单（版本升序）。 */
    public List<ReconRow> reconsByDirective(long directiveId) {
        return jdbc.query("SELECT * FROM reconciliation WHERE directive_id = ? ORDER BY recon_version",
                RECON_MAPPER, directiveId);
    }

    /** 按指令查询差额链（时间升序）。 */
    public List<DifferenceRow> differencesByDirective(long directiveId) {
        return jdbc.query("SELECT * FROM reconciliation_difference WHERE directive_id = ? ORDER BY id",
                DIFF_MAPPER, directiveId);
    }
}