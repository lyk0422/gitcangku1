package com.example.starter.batch;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

/**
 * 产率平衡核算持久化层：核算版本只增不改，处置确认只增不改；所有 SQL 参数化。
 */
@Repository
public class YieldRepository {

    /**
     * yield_reconciliation 表行记录；id 即核算版本号。数量与上限统一三位小数。
     */
    public record YieldRow(long id, String batchKey, String requestId,
                           BigDecimal inputQty, BigDecimal goodQty, BigDecimal lossQty, BigDecimal scrapQty,
                           BigDecimal deviation, BigDecimal deviationLimit, boolean withinLimit,
                           String createdAt) {
    }

    /**
     * yield_disposal 表行记录；seq 为同一版本上的确认序号 1/2。
     */
    public record DisposalRow(long id, String batchKey, long versionId, String commandKey,
                              String disposerId, int seq, String createdAt) {
    }

    private static final RowMapper<YieldRow> YIELD_MAPPER = (rs, n) -> new YieldRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getString("request_id"),
            rs.getBigDecimal("input_qty").setScale(YieldMath.SCALE),
            rs.getBigDecimal("good_qty").setScale(YieldMath.SCALE),
            rs.getBigDecimal("loss_qty").setScale(YieldMath.SCALE),
            rs.getBigDecimal("scrap_qty").setScale(YieldMath.SCALE),
            rs.getBigDecimal("deviation").setScale(YieldMath.SCALE),
            rs.getBigDecimal("deviation_limit").setScale(YieldMath.SCALE),
            rs.getInt("within_limit") == 1, rs.getString("created_at"));

    private static final RowMapper<DisposalRow> DISPOSAL_MAPPER = (rs, n) -> new DisposalRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getLong("version_id"),
            rs.getString("command_key"), rs.getString("disposer_id"),
            rs.getInt("seq"), rs.getString("created_at"));

    private final JdbcTemplate jdbc;

    public YieldRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入核算版本，返回自增版本号。
     */
    public long insertYield(YieldRow row) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO yield_reconciliation (batch_key, request_id, input_qty, good_qty,"
                            + " loss_qty, scrap_qty, deviation, deviation_limit, within_limit, created_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, row.batchKey());
            ps.setString(2, row.requestId());
            ps.setBigDecimal(3, row.inputQty());
            ps.setBigDecimal(4, row.goodQty());
            ps.setBigDecimal(5, row.lossQty());
            ps.setBigDecimal(6, row.scrapQty());
            ps.setBigDecimal(7, row.deviation());
            ps.setBigDecimal(8, row.deviationLimit());
            ps.setInt(9, row.withinLimit() ? 1 : 0);
            ps.setString(10, row.createdAt());
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<YieldRow> findYield(String batchKey, String requestId) {
        return jdbc.query("SELECT * FROM yield_reconciliation WHERE batch_key = ? AND request_id = ?",
                        YIELD_MAPPER, batchKey, requestId)
                .stream().findFirst();
    }

    public Optional<YieldRow> findYieldById(long versionId) {
        return jdbc.query("SELECT * FROM yield_reconciliation WHERE id = ?",
                        YIELD_MAPPER, versionId)
                .stream().findFirst();
    }

    /**
     * 批次最新核算版本（版本号最大）。
     */
    public Optional<YieldRow> findLatestYield(String batchKey) {
        return jdbc.query("SELECT * FROM yield_reconciliation WHERE batch_key = ?"
                        + " ORDER BY id DESC LIMIT 1", YIELD_MAPPER, batchKey)
                .stream().findFirst();
    }

    /**
     * 批次全部核算版本，按版本号升序。
     */
    public List<YieldRow> findYields(String batchKey) {
        return jdbc.query("SELECT * FROM yield_reconciliation WHERE batch_key = ? ORDER BY id",
                YIELD_MAPPER, batchKey);
    }

    /**
     * 某核算版本上的全部处置确认，按确认序号升序。
     */
    public List<DisposalRow> findDisposals(long versionId) {
        return jdbc.query("SELECT * FROM yield_disposal WHERE version_id = ? ORDER BY seq",
                DISPOSAL_MAPPER, versionId);
    }

    public Optional<DisposalRow> findDisposalByCommand(String commandKey) {
        return jdbc.query("SELECT * FROM yield_disposal WHERE command_key = ?",
                        DISPOSAL_MAPPER, commandKey)
                .stream().findFirst();
    }

    public void insertDisposal(DisposalRow row) {
        jdbc.update("INSERT INTO yield_disposal (batch_key, version_id, command_key, disposer_id,"
                        + " seq, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                row.batchKey(), row.versionId(), row.commandKey(), row.disposerId(),
                row.seq(), row.createdAt());
    }

    /**
     * 更新批次产率门禁状态（只反映最新版本）。
     */
    public void updateYieldStatus(String batchKey, String yieldStatus) {
        jdbc.update("UPDATE batch SET yield_status = ? WHERE batch_key = ?", yieldStatus, batchKey);
    }
}
