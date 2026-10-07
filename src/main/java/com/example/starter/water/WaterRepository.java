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
 * 时间一律为 UTC 纳秒时间戳；水量为 DECIMAL(19,3)；流量与渠段容量为正整数（立方米每秒）。
 */
@Repository
public class WaterRepository {

    /** 供水窗口行；graphVersion 为 null 表示普通窗口（不适用渠网门禁）。 */
    public record WindowRow(long id, String windowKey, String channelId, long startNanos, long endNanos,
                            BigDecimal plannedVolume, Integer graphVersion, long createdNanos) {
    }

    /** 配水申请行；amount 为不可改写的原申请水量，heldAmount 为当前持有额度，flowRate 仅渠网窗口非空。 */
    public record AllocationRow(long id, String allocationKey, long windowId, String userId, BigDecimal amount,
                                BigDecimal heldAmount, Long flowRate, String requester, String status,
                                long createdNanos, long updatedNanos) {
    }

    /** 申请路径段行，按 seq 保序。 */
    public record SegmentRow(long allocationId, int seq, String fromNode, String toNode) {
    }

    /** 已批准且仍持有额度的申请对某条有向边的流量贡献行。 */
    public record ContributionRow(long allocationId, String fromNode, String toNode, long flowRate) {
    }

    /** 渠网有向渠段行；removedVersion 为 null 表示当前有效。 */
    public record GraphEdgeRow(long id, String fromNode, String toNode, long capacity,
                               int addedVersion, Integer removedVersion) {
    }

    /** 图修订历史行。 */
    public record GraphRevisionRow(long id, long windowId, int version, String nodesJson, String actor,
                                   long createdNanos) {
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

    private static final String WINDOW_SELECT =
            "SELECT id, window_key, channel_id, start_nanos, end_nanos, planned_volume, graph_version, created_nanos";

    private static final RowMapper<WindowRow> WINDOW_MAPPER = (rs, n) -> new WindowRow(
            rs.getLong("id"), rs.getString("window_key"), rs.getString("channel_id"),
            rs.getLong("start_nanos"), rs.getLong("end_nanos"),
            rs.getBigDecimal("planned_volume"),
            rs.getObject("graph_version") == null ? null : rs.getInt("graph_version"),
            rs.getLong("created_nanos"));

    private static final RowMapper<AllocationRow> ALLOCATION_MAPPER = (rs, n) -> new AllocationRow(
            rs.getLong("id"), rs.getString("allocation_key"), rs.getLong("window_id"),
            rs.getString("user_id"), rs.getBigDecimal("amount"), rs.getBigDecimal("held_amount"),
            rs.getObject("flow_rate") == null ? null : rs.getLong("flow_rate"),
            rs.getString("requester"), rs.getString("status"),
            rs.getLong("created_nanos"), rs.getLong("updated_nanos"));

    private static final String ALLOCATION_SELECT =
            "SELECT id, allocation_key, window_id, user_id, amount, held_amount, flow_rate, requester, status,"
                    + " created_nanos, updated_nanos";

    private static final RowMapper<SegmentRow> SEGMENT_MAPPER = (rs, n) -> new SegmentRow(
            rs.getLong("allocation_id"), rs.getInt("seq"),
            rs.getString("from_node"), rs.getString("to_node"));

    private static final RowMapper<ContributionRow> CONTRIBUTION_MAPPER = (rs, n) -> new ContributionRow(
            rs.getLong("allocation_id"), rs.getString("from_node"), rs.getString("to_node"),
            rs.getLong("flow_rate"));

    private static final RowMapper<GraphEdgeRow> GRAPH_EDGE_MAPPER = (rs, n) -> new GraphEdgeRow(
            rs.getLong("id"), rs.getString("from_node"), rs.getString("to_node"), rs.getLong("capacity"),
            rs.getInt("added_version"),
            rs.getObject("removed_version") == null ? null : rs.getInt("removed_version"));

    private static final RowMapper<GraphRevisionRow> GRAPH_REVISION_MAPPER = (rs, n) -> new GraphRevisionRow(
            rs.getLong("id"), rs.getLong("window_id"), rs.getInt("version"), rs.getString("nodes_json"),
            rs.getString("actor"), rs.getLong("created_nanos"));

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

    /** 插入窗口并返回自增主键；graphVersion 为 null 表示普通窗口。 */
    public long insertWindow(String windowKey, String channelId, long startNanos, long endNanos,
                             BigDecimal plannedVolume, Integer graphVersion, long createdNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO supply_window (window_key, channel_id, start_nanos, end_nanos, planned_volume,"
                            + " graph_version, created_nanos) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, windowKey);
            ps.setString(2, channelId);
            ps.setLong(3, startNanos);
            ps.setLong(4, endNanos);
            ps.setBigDecimal(5, plannedVolume);
            if (graphVersion == null) {
                ps.setNull(6, java.sql.Types.INTEGER);
            } else {
                ps.setInt(6, graphVersion);
            }
            ps.setLong(7, createdNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 按主键查询窗口，不存在返回 null。 */
    public WindowRow findWindowById(long id) {
        try {
            return jdbc.queryForObject(WINDOW_SELECT + " FROM supply_window WHERE id = ?", WINDOW_MAPPER, id);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 按主键锁定窗口行（FOR UPDATE），用于串行化批准、取消、转让、限供与图修订。 */
    public WindowRow lockWindowById(long id) {
        try {
            return jdbc.queryForObject(WINDOW_SELECT + " FROM supply_window WHERE id = ? FOR UPDATE",
                    WINDOW_MAPPER, id);
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

    /** 更新窗口当前图版本（图修订提交时调用）。 */
    public void updateWindowGraphVersion(long windowId, int graphVersion) {
        jdbc.update("UPDATE supply_window SET graph_version = ? WHERE id = ?", graphVersion, windowId);
    }

    /** 插入申请（初始 REQUESTED）并返回主键；flowRate 仅渠网窗口非空。 */
    public long insertAllocation(String allocationKey, long windowId, String userId, BigDecimal amount,
                                 Long flowRate, String requester, long nowNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO allocation (allocation_key, window_id, user_id, amount, held_amount, flow_rate,"
                            + " requester, status, created_nanos, updated_nanos)"
                            + " VALUES (?, ?, ?, ?, 0, ?, ?, 'REQUESTED', ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, allocationKey);
            ps.setLong(2, windowId);
            ps.setString(3, userId);
            ps.setBigDecimal(4, amount);
            if (flowRate == null) {
                ps.setNull(5, java.sql.Types.BIGINT);
            } else {
                ps.setLong(5, flowRate);
            }
            ps.setString(6, requester);
            ps.setLong(7, nowNanos);
            ps.setLong(8, nowNanos);
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

    /** 插入申请的一条有序路径段。 */
    public void insertSegment(long allocationId, int seq, String fromNode, String toNode) {
        jdbc.update("INSERT INTO allocation_segment (allocation_id, seq, from_node, to_node) VALUES (?, ?, ?, ?)",
                allocationId, seq, fromNode, toNode);
    }

    /** 申请的全部路径段，按 seq 升序（路径顺序保序）。 */
    public List<SegmentRow> listSegments(long allocationId) {
        return jdbc.query(
                "SELECT allocation_id, seq, from_node, to_node FROM allocation_segment"
                        + " WHERE allocation_id = ? ORDER BY seq", SEGMENT_MAPPER, allocationId);
    }

    /**
     * 窗口内已批准且仍持有额度（held_amount > 0）的申请对各自路径边的流量贡献，
     * 用于边容量求和与已批准边并集无环校验；取消（CANCELLED）与已全额转出的申请不再贡献。
     */
    public List<ContributionRow> listContributions(long windowId) {
        return jdbc.query(
                "SELECT a.id AS allocation_id, s.from_node, s.to_node, a.flow_rate"
                        + " FROM allocation a JOIN allocation_segment s ON s.allocation_id = a.id"
                        + " WHERE a.window_id = ? AND a.status = 'APPROVED' AND a.held_amount > 0"
                        + " ORDER BY a.id, s.seq", CONTRIBUTION_MAPPER, windowId);
    }

    /** 插入图修订历史行（逐版冻结节点全集）。 */
    public void insertGraphRevision(long windowId, int version, String nodesJson, String actor,
                                    long createdNanos) {
        jdbc.update("INSERT INTO graph_revision (window_id, version, nodes_json, actor, created_nanos)"
                + " VALUES (?, ?, ?, ?, ?)", windowId, version, nodesJson, actor, createdNanos);
    }

    /** 查询窗口指定版本的图修订，不存在返回 null。 */
    public GraphRevisionRow findGraphRevision(long windowId, int version) {
        try {
            return jdbc.queryForObject(
                    "SELECT id, window_id, version, nodes_json, actor, created_nanos FROM graph_revision"
                            + " WHERE window_id = ? AND version = ?", GRAPH_REVISION_MAPPER, windowId, version);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 插入当前有效渠段（added_version 为加入时的图版本）。 */
    public void insertGraphEdge(long windowId, String fromNode, String toNode, long capacity, int addedVersion) {
        jdbc.update("INSERT INTO graph_edge (window_id, from_node, to_node, capacity, added_version,"
                + " removed_version) VALUES (?, ?, ?, ?, ?, NULL)",
                windowId, fromNode, toNode, capacity, addedVersion);
    }

    /** 窗口当前有效渠段（removed_version 为 NULL），按主键升序。 */
    public List<GraphEdgeRow> listCurrentEdges(long windowId) {
        return jdbc.query(
                "SELECT id, from_node, to_node, capacity, added_version, removed_version FROM graph_edge"
                        + " WHERE window_id = ? AND removed_version IS NULL ORDER BY id",
                GRAPH_EDGE_MAPPER, windowId);
    }

    /** 窗口全部渠段（含已移除，逐版冻结的历史），按主键升序。 */
    public List<GraphEdgeRow> listAllEdges(long windowId) {
        return jdbc.query(
                "SELECT id, from_node, to_node, capacity, added_version, removed_version FROM graph_edge"
                        + " WHERE window_id = ? ORDER BY id", GRAPH_EDGE_MAPPER, windowId);
    }

    /** 将当前有效渠段标记为在指定版本移除（容量变更视为移除旧边并加入新边）。 */
    public void removeGraphEdge(long edgeId, int removedVersion) {
        jdbc.update("UPDATE graph_edge SET removed_version = ? WHERE id = ?", removedVersion, edgeId);
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
}
