package com.example.starter.water;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Objects;

/**
 * 灌区配水数据访问层（JdbcTemplate，参数化 SQL）。
 * 时间一律为 UTC 纳秒时间戳；水量为 DECIMAL(19,3)。
 */
@Repository
public class WaterRepository {

    /** 供水窗口行。 */
    public record WindowRow(long id, String windowKey, String channelId, long startNanos, long endNanos,
                            BigDecimal plannedVolume, String status, long createdNanos) {
    }

    /** 配水申请行；amount 为不可改写的原申请水量，heldAmount 为当前持有额度。 */
    public record AllocationRow(long id, String allocationKey, long windowId, String userId, BigDecimal amount,
                                BigDecimal heldAmount, String requester, String status,
                                long createdNanos, long updatedNanos) {
    }

    /** 转让流水行，创建后不可变。 */
    public record TransferRow(long id, String transferKey, long windowId, String sourceAllocationKey,
                              String targetAllocationKey, BigDecimal amount, String actor, long createdNanos) {
    }

    /** 限供行。 */
    public record CurtailmentRow(long id, long windowId, BigDecimal volume, String status, long createdNanos,
                                 Long cancelledNanos) {
    }

    /** 幂等命令行；response 为 null 表示响应尚未写回（同事务内）。 */
    public record CommandRow(String commandKey, String operation, String params, String response,
                             long createdNanos) {
    }

    /** 水源行。 */
    public record SourceRow(long windowId, String sourceId, BigDecimal supplyCap, long createdNanos) {
    }

    /** 区块-水源矩阵单元格行；version 每次重平衡更新逐记录 +1。 */
    public record BlockQuotaRow(long id, long windowId, String blockId, String sourceId, BigDecimal quota,
                                BigDecimal consumed, long version, long createdNanos, long updatedNanos) {
    }

    /** 区块适用水源行。 */
    public record ApplicabilityRow(long windowId, String blockId, String sourceId, long createdNanos) {
    }

    /** 用水核销流水行，创建后不可变。 */
    public record WriteoffRow(long id, String writeoffKey, long windowId, String blockId, String sourceId,
                              BigDecimal volume, String actor, long createdNanos) {
    }

    /** 重平衡单行。 */
    public record RebalanceOrderRow(long id, String rebalanceKey, String requestId, long windowId, String status,
                                    String normalizedParams, int detailCount, long createdNanos) {
    }

    /** 重平衡规范化明细行。 */
    public record RebalanceDetailRow(long id, long orderId, String blockId, String sourceSourceId,
                                     String targetSourceId, BigDecimal volume, int seqNo) {
    }

    /** 重平衡矩阵快照单元格行。 */
    public record MatrixSnapshotRow(long id, long orderId, String phase, String blockId, String sourceId,
                                    BigDecimal quota, BigDecimal consumed, long version, BigDecimal supplyCap,
                                    int seqNo) {
    }

    private static final RowMapper<WindowRow> WINDOW_MAPPER = (rs, n) -> new WindowRow(
            rs.getLong("id"), rs.getString("window_key"), rs.getString("channel_id"),
            rs.getLong("start_nanos"), rs.getLong("end_nanos"),
            rs.getBigDecimal("planned_volume"), rs.getString("status"), rs.getLong("created_nanos"));

    private static final RowMapper<AllocationRow> ALLOCATION_MAPPER = (rs, n) -> new AllocationRow(
            rs.getLong("id"), rs.getString("allocation_key"), rs.getLong("window_id"),
            rs.getString("user_id"), rs.getBigDecimal("amount"), rs.getBigDecimal("held_amount"),
            rs.getString("requester"), rs.getString("status"),
            rs.getLong("created_nanos"), rs.getLong("updated_nanos"));

    private static final String ALLOCATION_SELECT =
            "SELECT id, allocation_key, window_id, user_id, amount, held_amount, requester, status,"
                    + " created_nanos, updated_nanos";

    private static final RowMapper<TransferRow> TRANSFER_MAPPER = (rs, n) -> new TransferRow(
            rs.getLong("id"), rs.getString("transfer_key"), rs.getLong("window_id"),
            rs.getString("source_allocation_key"), rs.getString("target_allocation_key"),
            rs.getBigDecimal("amount"), rs.getString("actor"), rs.getLong("created_nanos"));

    private static final RowMapper<CurtailmentRow> CURTAILMENT_MAPPER = (rs, n) -> new CurtailmentRow(
            rs.getLong("id"), rs.getLong("window_id"), rs.getBigDecimal("volume"), rs.getString("status"),
            rs.getLong("created_nanos"),
            rs.getObject("cancelled_nanos") == null ? null : rs.getLong("cancelled_nanos"));

    private static final RowMapper<CommandRow> COMMAND_MAPPER = (rs, n) -> new CommandRow(
            rs.getString("command_key"), rs.getString("operation"), rs.getString("params"),
            rs.getString("response"), rs.getLong("created_nanos"));

    private final JdbcTemplate jdbc;

    public WaterRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 插入窗口并返回自增主键。 */
    public long insertWindow(String windowKey, String channelId, long startNanos, long endNanos,
                             BigDecimal plannedVolume, long createdNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO supply_window (window_key, channel_id, start_nanos, end_nanos, planned_volume, created_nanos)"
                            + " VALUES (?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, windowKey);
            ps.setString(2, channelId);
            ps.setLong(3, startNanos);
            ps.setLong(4, endNanos);
            ps.setBigDecimal(5, plannedVolume);
            ps.setLong(6, createdNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 按主键查询窗口，不存在返回 null。 */
    public WindowRow findWindowById(long id) {
        try {
            return jdbc.queryForObject(
                    "SELECT id, window_key, channel_id, start_nanos, end_nanos, planned_volume, status, created_nanos"
                            + " FROM supply_window WHERE id = ?", WINDOW_MAPPER, id);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 按主键锁定窗口行（FOR UPDATE），用于串行化批准、限供与重平衡。 */
    public WindowRow lockWindowById(long id) {
        try {
            return jdbc.queryForObject(
                    "SELECT id, window_key, channel_id, start_nanos, end_nanos, planned_volume, status, created_nanos"
                            + " FROM supply_window WHERE id = ? FOR UPDATE", WINDOW_MAPPER, id);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 判断同渠道是否存在与 [startNanos, endNanos) 重叠的窗口（相邻合法）。 */
    public boolean existsOverlappingWindow(String channelId, long startNanos, long endNanos) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM supply_window WHERE channel_id = ? AND start_nanos < ? AND ? < end_nanos",
                Integer.class, channelId, endNanos, startNanos);
        return count != null && count > 0;
    }

    /** 插入申请（初始 REQUESTED）并返回主键。 */
    public long insertAllocation(String allocationKey, long windowId, String userId, BigDecimal amount,
                                 String requester, long nowNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO allocation (allocation_key, window_id, user_id, amount, held_amount, requester, status, created_nanos, updated_nanos)"
                            + " VALUES (?, ?, ?, ?, 0, ?, 'REQUESTED', ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, allocationKey);
            ps.setLong(2, windowId);
            ps.setString(3, userId);
            ps.setBigDecimal(4, amount);
            ps.setString(5, requester);
            ps.setLong(6, nowNanos);
            ps.setLong(7, nowNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 按业务键查询申请，不存在返回 null。 */
    public AllocationRow findAllocationByKey(String allocationKey) {
        try {
            return jdbc.queryForObject(ALLOCATION_SELECT + " FROM allocation WHERE allocation_key = ?",
                    ALLOCATION_MAPPER, allocationKey);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 按业务键锁定申请行（FOR UPDATE），用于串行化转让的源/目标争用，不存在返回 null。 */
    public AllocationRow lockAllocationByKey(String allocationKey) {
        try {
            return jdbc.queryForObject(
                    ALLOCATION_SELECT + " FROM allocation WHERE allocation_key = ? FOR UPDATE",
                    ALLOCATION_MAPPER, allocationKey);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /**
     * 更新申请状态与变更时间，并同步持有额度：批准时持有额度等于原申请水量，取消时归零，
     * REQUESTED 保持当前持有额度不变。
     */
    public void updateAllocationStatus(long id, String status, long updatedNanos) {
        jdbc.update("UPDATE allocation SET status = ?, updated_nanos = ?,"
                + " held_amount = CASE WHEN ? = 'APPROVED' THEN amount WHEN ? = 'CANCELLED' THEN 0"
                + " ELSE held_amount END WHERE id = ?",
                status, updatedNanos, status, status, id);
    }

    /** 转让扣减源持有额度（不得为负由事务内校验保证）并记录变更时间。 */
    public void decrementHeldAmount(long id, BigDecimal delta, long updatedNanos) {
        jdbc.update("UPDATE allocation SET held_amount = held_amount - ?, updated_nanos = ? WHERE id = ?",
                delta, updatedNanos, id);
    }

    /** 窗口当前所有 APPROVED 申请的当前持有额度之和（BigDecimal 精确求和），无则 0。 */
    public BigDecimal sumApprovedAmount(long windowId) {
        BigDecimal sum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(held_amount), 0) FROM allocation WHERE window_id = ? AND status = 'APPROVED'",
                BigDecimal.class, windowId);
        return sum == null ? BigDecimal.ZERO : sum;
    }

    /** 插入限供（ACTIVE）并返回主键。 */
    public long insertCurtailment(long windowId, BigDecimal volume, long createdNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO curtailment (window_id, volume, status, created_nanos, cancelled_nanos)"
                            + " VALUES (?, ?, 'ACTIVE', ?, NULL)", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, windowId);
            ps.setBigDecimal(2, volume);
            ps.setLong(3, createdNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 查询窗口当前生效限供，无则 null。 */
    public CurtailmentRow findActiveCurtailment(long windowId) {
        try {
            return jdbc.queryForObject(
                    "SELECT id, window_id, volume, status, created_nanos, cancelled_nanos"
                            + " FROM curtailment WHERE window_id = ? AND status = 'ACTIVE'", CURTAILMENT_MAPPER, windowId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 取消限供：置为 CANCELLED 并记录取消时间。 */
    public void cancelCurtailment(long id, long cancelledNanos) {
        jdbc.update("UPDATE curtailment SET status = 'CANCELLED', cancelled_nanos = ? WHERE id = ?",
                cancelledNanos, id);
    }

    /** 窗口全部申请，按主键升序。 */
    public List<AllocationRow> listAllocations(long windowId) {
        return jdbc.query(
                ALLOCATION_SELECT + " FROM allocation WHERE window_id = ? ORDER BY id",
                ALLOCATION_MAPPER, windowId);
    }

    /** 插入不可变转让流水并返回主键。 */
    public long insertTransfer(String transferKey, long windowId, String sourceAllocationKey,
                               String targetAllocationKey, BigDecimal amount, String actor, long createdNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO transfer (transfer_key, window_id, source_allocation_key,"
                            + " target_allocation_key, amount, actor, created_nanos)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, transferKey);
            ps.setLong(2, windowId);
            ps.setString(3, sourceAllocationKey);
            ps.setString(4, targetAllocationKey);
            ps.setBigDecimal(5, amount);
            ps.setString(6, actor);
            ps.setLong(7, createdNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 按业务键查询转让流水，不存在返回 null。 */
    public TransferRow findTransferByKey(String transferKey) {
        try {
            return jdbc.queryForObject(
                    "SELECT id, transfer_key, window_id, source_allocation_key, target_allocation_key,"
                            + " amount, actor, created_nanos FROM transfer WHERE transfer_key = ?",
                    TRANSFER_MAPPER, transferKey);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 窗口全部转让流水（不可变），按主键升序。 */
    public List<TransferRow> listTransfers(long windowId) {
        return jdbc.query(
                "SELECT id, transfer_key, window_id, source_allocation_key, target_allocation_key,"
                        + " amount, actor, created_nanos FROM transfer WHERE window_id = ? ORDER BY id",
                TRANSFER_MAPPER, windowId);
    }

    /** 窗口全部限供记录（含已取消），按主键升序。 */
    public List<CurtailmentRow> listCurtailments(long windowId) {
        return jdbc.query(
                "SELECT id, window_id, volume, status, created_nanos, cancelled_nanos"
                        + " FROM curtailment WHERE window_id = ? ORDER BY id", CURTAILMENT_MAPPER, windowId);
    }

    /** 按幂等键查询命令，不存在返回 null。 */
    public CommandRow findCommand(String commandKey) {
        try {
            return jdbc.queryForObject(
                    "SELECT command_key, operation, params, response, created_nanos FROM command_log WHERE command_key = ?",
                    COMMAND_MAPPER, commandKey);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 先占位插入命令（响应为空），同事务内业务成功后写回响应。 */
    public void insertCommand(String commandKey, String operation, String params, long createdNanos) {
        jdbc.update("INSERT INTO command_log (command_key, operation, params, response, created_nanos)"
                + " VALUES (?, ?, ?, NULL, ?)", commandKey, operation, params, createdNanos);
    }

    /** 写回命令首次成功响应。 */
    public void updateCommandResponse(String commandKey, String response) {
        jdbc.update("UPDATE command_log SET response = ? WHERE command_key = ?", response, commandKey);
    }

    // ------------------------------------------------------------------
    // 多水源配额矩阵
    // ------------------------------------------------------------------

    private static final RowMapper<SourceRow> SOURCE_MAPPER = (rs, n) -> new SourceRow(
            rs.getLong("window_id"), rs.getString("source_id"), rs.getBigDecimal("supply_cap"),
            rs.getLong("created_nanos"));

    private static final RowMapper<BlockQuotaRow> BLOCK_QUOTA_MAPPER = (rs, n) -> new BlockQuotaRow(
            rs.getLong("id"), rs.getLong("window_id"), rs.getString("block_id"), rs.getString("source_id"),
            rs.getBigDecimal("quota"), rs.getBigDecimal("consumed"), rs.getLong("version"),
            rs.getLong("created_nanos"), rs.getLong("updated_nanos"));

    private static final String BLOCK_QUOTA_SELECT =
            "SELECT id, window_id, block_id, source_id, quota, consumed, version, created_nanos, updated_nanos"
                    + " FROM block_quota";

    private static final RowMapper<ApplicabilityRow> APPLICABILITY_MAPPER = (rs, n) -> new ApplicabilityRow(
            rs.getLong("window_id"), rs.getString("block_id"), rs.getString("source_id"),
            rs.getLong("created_nanos"));

    private static final RowMapper<WriteoffRow> WRITEOFF_MAPPER = (rs, n) -> new WriteoffRow(
            rs.getLong("id"), rs.getString("writeoff_key"), rs.getLong("window_id"),
            rs.getString("block_id"), rs.getString("source_id"), rs.getBigDecimal("volume"),
            rs.getString("actor"), rs.getLong("created_nanos"));

    private static final RowMapper<RebalanceOrderRow> REBALANCE_ORDER_MAPPER = (rs, n) -> new RebalanceOrderRow(
            rs.getLong("id"), rs.getString("rebalance_key"), rs.getString("request_id"),
            rs.getLong("window_id"), rs.getString("status"), rs.getString("normalized_params"),
            rs.getInt("detail_count"), rs.getLong("created_nanos"));

    private static final RowMapper<RebalanceDetailRow> REBALANCE_DETAIL_MAPPER = (rs, n) ->
            new RebalanceDetailRow(rs.getLong("id"), rs.getLong("order_id"), rs.getString("block_id"),
                    rs.getString("source_source_id"), rs.getString("target_source_id"),
                    rs.getBigDecimal("volume"), rs.getInt("seq_no"));

    private static final RowMapper<MatrixSnapshotRow> MATRIX_SNAPSHOT_MAPPER = (rs, n) ->
            new MatrixSnapshotRow(rs.getLong("id"), rs.getLong("order_id"), rs.getString("phase"),
                    rs.getString("block_id"), rs.getString("source_id"), rs.getBigDecimal("quota"),
                    rs.getBigDecimal("consumed"), rs.getLong("version"), rs.getBigDecimal("supply_cap"),
                    rs.getInt("seq_no"));

    /** 批量插入窗口水源。 */
    public void insertSources(long windowId, java.util.List<SourceConfig> sources, long createdNanos) {
        jdbc.batchUpdate("INSERT INTO water_source (window_id, source_id, supply_cap, created_nanos)"
                + " VALUES (?, ?, ?, ?)", sources, sources.size(), (ps, source) -> {
            ps.setLong(1, windowId);
            ps.setString(2, source.sourceId());
            ps.setBigDecimal(3, source.supplyCap());
            ps.setLong(4, createdNanos);
        });
    }

    /** 水源配置入参。 */
    public record SourceConfig(String sourceId, BigDecimal supplyCap) {
    }

    /** 窗口全部水源，按 source_id 升序。 */
    public java.util.List<SourceRow> listSources(long windowId) {
        return jdbc.query(
                "SELECT window_id, source_id, supply_cap, created_nanos FROM water_source"
                        + " WHERE window_id = ? ORDER BY source_id", SOURCE_MAPPER, windowId);
    }

    /** 窗口水源数量。 */
    public int countSources(long windowId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM water_source WHERE window_id = ?", Integer.class, windowId);
        return count == null ? 0 : count;
    }

    /** 插入区块适用水源白名单。 */
    public void insertApplicability(long windowId, String blockId, java.util.List<String> sourceIds,
                                    long createdNanos) {
        jdbc.batchUpdate("INSERT INTO block_source_applicability (window_id, block_id, source_id, created_nanos)"
                + " VALUES (?, ?, ?, ?)", sourceIds, sourceIds.size(), (ps, sourceId) -> {
            ps.setLong(1, windowId);
            ps.setString(2, blockId);
            ps.setString(3, sourceId);
            ps.setLong(4, createdNanos);
        });
    }

    /** 区块是否已登记。 */
    public boolean existsBlock(long windowId, String blockId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM block_quota WHERE window_id = ? AND block_id = ?",
                Integer.class, windowId, blockId);
        return count != null && count > 0;
    }

    /** 区块适用水源，按 source_id 升序；区块不存在返回空表。 */
    public java.util.List<String> listApplicableSources(long windowId, String blockId) {
        return jdbc.queryForList(
                "SELECT source_id FROM block_source_applicability WHERE window_id = ? AND block_id = ?"
                        + " ORDER BY source_id", String.class, windowId, blockId);
    }

    /** 批量插入区块矩阵单元格（初始 consumed 为 0，version 为 0）。 */
    public void insertBlockQuotas(long windowId, String blockId, java.util.List<CellInit> cells,
                                  long createdNanos) {
        jdbc.batchUpdate("INSERT INTO block_quota"
                + " (window_id, block_id, source_id, quota, consumed, version, created_nanos, updated_nanos)"
                + " VALUES (?, ?, ?, ?, 0, 0, ?, ?)", cells, cells.size(), (ps, cell) -> {
            ps.setLong(1, windowId);
            ps.setString(2, blockId);
            ps.setString(3, cell.sourceId());
            ps.setBigDecimal(4, cell.quota());
            ps.setLong(5, createdNanos);
            ps.setLong(6, createdNanos);
        });
    }

    /** 矩阵单元格初始额度入参。 */
    public record CellInit(String sourceId, BigDecimal quota) {
    }

    /** 窗口全部矩阵单元格（普通读，用于预览/查询证据），按 source_id、block_id 稳定排序。 */
    public java.util.List<BlockQuotaRow> listBlockQuotas(long windowId) {
        return jdbc.query(BLOCK_QUOTA_SELECT + " WHERE window_id = ? ORDER BY source_id, block_id",
                BLOCK_QUOTA_MAPPER, windowId);
    }

    /** 窗口全部区块适用水源行。 */
    public java.util.List<ApplicabilityRow> listApplicabilityRows(long windowId) {
        return jdbc.query(
                "SELECT window_id, block_id, source_id, created_nanos FROM block_source_applicability"
                        + " WHERE window_id = ? ORDER BY block_id, source_id", APPLICABILITY_MAPPER, windowId);
    }

    /** 锁定窗口全部矩阵单元格（FOR UPDATE），按 source_id、block_id 稳定排序，供激活时重读。 */
    public java.util.List<BlockQuotaRow> lockBlockQuotas(long windowId) {
        return jdbc.query(BLOCK_QUOTA_SELECT + " WHERE window_id = ? ORDER BY source_id, block_id FOR UPDATE",
                BLOCK_QUOTA_MAPPER, windowId);
    }

    /** 区块全部单元格，按 source_id 升序。 */
    public java.util.List<BlockQuotaRow> listBlockQuotasOfBlock(long windowId, String blockId) {
        return jdbc.query(BLOCK_QUOTA_SELECT + " WHERE window_id = ? AND block_id = ? ORDER BY source_id",
                BLOCK_QUOTA_MAPPER, windowId, blockId);
    }

    /** 重平衡写回单元格后态额度、累加核销量不变，version +1。 */
    public int updateCellQuota(long id, BigDecimal quota, long version, long updatedNanos) {
        return jdbc.update("UPDATE block_quota SET quota = ?, version = ?, updated_nanos = ?"
                + " WHERE id = ? AND version = ?", quota, version + 1, updatedNanos, id, version);
    }

    /** 核销：累加已核销量与版本；consumed 不超过 quota 由事务内校验 + CHECK 约束双重保证。 */
    public int addConsumed(long id, BigDecimal delta, long version, long updatedNanos) {
        return jdbc.update("UPDATE block_quota SET consumed = consumed + ?, version = ?, updated_nanos = ?"
                + " WHERE id = ? AND version = ?", delta, version + 1, updatedNanos, id, version);
    }

    /** 关闭窗口。 */
    public int closeWindow(long id, long updatedNanos) {
        return jdbc.update("UPDATE supply_window SET status = 'CLOSED' WHERE id = ? AND status = 'OPEN'", id);
    }

    /** 插入不可变核销流水并返回主键。 */
    public long insertWriteoff(String writeoffKey, long windowId, String blockId, String sourceId,
                               BigDecimal volume, String actor, long createdNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO writeoff (writeoff_key, window_id, block_id, source_id, volume, actor, created_nanos)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, writeoffKey);
            ps.setLong(2, windowId);
            ps.setString(3, blockId);
            ps.setString(4, sourceId);
            ps.setBigDecimal(5, volume);
            ps.setString(6, actor);
            ps.setLong(7, createdNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 按业务键查询核销流水，不存在返回 null。 */
    public WriteoffRow findWriteoffByKey(String writeoffKey) {
        try {
            return jdbc.queryForObject(
                    "SELECT id, writeoff_key, window_id, block_id, source_id, volume, actor, created_nanos"
                            + " FROM writeoff WHERE writeoff_key = ?", WRITEOFF_MAPPER, writeoffKey);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 插入重平衡单（ACTIVE）并返回主键。 */
    public long insertRebalanceOrder(String rebalanceKey, String requestId, long windowId,
                                     String normalizedParams, int detailCount, long createdNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rebalance_order"
                            + " (rebalance_key, request_id, window_id, status, normalized_params, detail_count, created_nanos)"
                            + " VALUES (?, ?, ?, 'ACTIVE', ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, rebalanceKey);
            ps.setString(2, requestId);
            ps.setLong(3, windowId);
            ps.setString(4, normalizedParams);
            ps.setInt(5, detailCount);
            ps.setLong(6, createdNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 按 requestId 查询重平衡单，不存在返回 null。 */
    public RebalanceOrderRow findRebalanceByRequestId(String requestId) {
        try {
            return jdbc.queryForObject(
                    "SELECT id, rebalance_key, request_id, window_id, status, normalized_params, detail_count, created_nanos"
                            + " FROM rebalance_order WHERE request_id = ?", REBALANCE_ORDER_MAPPER, requestId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 按 rebalanceKey 查询重平衡单，不存在返回 null。 */
    public RebalanceOrderRow findRebalanceByKey(String rebalanceKey) {
        try {
            return jdbc.queryForObject(
                    "SELECT id, rebalance_key, request_id, window_id, status, normalized_params, detail_count, created_nanos"
                            + " FROM rebalance_order WHERE rebalance_key = ?", REBALANCE_ORDER_MAPPER,
                    rebalanceKey);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 按主键查询重平衡单，不存在返回 null。 */
    public RebalanceOrderRow findRebalanceById(long id) {
        try {
            return jdbc.queryForObject(
                    "SELECT id, rebalance_key, request_id, window_id, status, normalized_params, detail_count, created_nanos"
                            + " FROM rebalance_order WHERE id = ?", REBALANCE_ORDER_MAPPER, id);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 批量插入重平衡规范化明细。 */
    public void insertRebalanceDetails(long orderId, java.util.List<DetailInit> details) {
        jdbc.batchUpdate("INSERT INTO rebalance_detail"
                + " (order_id, block_id, source_source_id, target_source_id, volume, seq_no)"
                + " VALUES (?, ?, ?, ?, ?, ?)", details, details.size(), (ps, detail) -> {
            ps.setLong(1, orderId);
            ps.setString(2, detail.blockId());
            ps.setString(3, detail.sourceSourceId());
            ps.setString(4, detail.targetSourceId());
            ps.setBigDecimal(5, detail.volume());
            ps.setInt(6, detail.seqNo());
        });
    }

    /** 规范化明细写入入参。 */
    public record DetailInit(String blockId, String sourceSourceId, String targetSourceId,
                             BigDecimal volume, int seqNo) {
    }

    /** 重平衡单的规范化明细，按 seq_no 升序。 */
    public java.util.List<RebalanceDetailRow> listRebalanceDetails(long orderId) {
        return jdbc.query(
                "SELECT id, order_id, block_id, source_source_id, target_source_id, volume, seq_no"
                        + " FROM rebalance_detail WHERE order_id = ? ORDER BY seq_no",
                REBALANCE_DETAIL_MAPPER, orderId);
    }

    /** 批量写入矩阵快照单元格。 */
    public void insertMatrixSnapshots(long orderId, java.util.List<SnapshotInit> snapshots) {
        jdbc.batchUpdate("INSERT INTO rebalance_matrix_snapshot"
                + " (order_id, phase, block_id, source_id, quota, consumed, version, supply_cap, seq_no)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", snapshots, snapshots.size(), (ps, snap) -> {
            ps.setLong(1, orderId);
            ps.setString(2, snap.phase());
            ps.setString(3, snap.blockId());
            ps.setString(4, snap.sourceId());
            ps.setBigDecimal(5, snap.quota());
            ps.setBigDecimal(6, snap.consumed());
            ps.setLong(7, snap.version());
            ps.setBigDecimal(8, snap.supplyCap());
            ps.setInt(9, snap.seqNo());
        });
    }

    /** 矩阵快照写入入参。 */
    public record SnapshotInit(String phase, String blockId, String sourceId, BigDecimal quota,
                               BigDecimal consumed, long version, BigDecimal supplyCap, int seqNo) {
    }

    /** 重平衡单某阶段快照，按 seq_no 升序（即按水源、区块稳定排序）。 */
    public java.util.List<MatrixSnapshotRow> listMatrixSnapshots(long orderId, String phase) {
        return jdbc.query(
                "SELECT id, order_id, phase, block_id, source_id, quota, consumed, version, supply_cap, seq_no"
                        + " FROM rebalance_matrix_snapshot WHERE order_id = ? AND phase = ? ORDER BY seq_no",
                MATRIX_SNAPSHOT_MAPPER, orderId, phase);
    }
}
