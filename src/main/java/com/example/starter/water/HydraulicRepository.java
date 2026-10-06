package com.example.starter.water;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * 简化水力模型数据访问层（JdbcTemplate，参数化 SQL）。
 * 渠段阻力为正整数并带版本；路径与审批冻结快照一经写入不可变。
 */
@Repository
public class HydraulicRepository {

    /** 渠段行；version 从 1 起，每次成功修改 +1。 */
    public record SegmentRow(long id, String segmentKey, long resistance, int version,
                             long createdNanos, long updatedNanos) {
    }

    /** 路径参数行；流量 × 分钟数 = 原申请水量。 */
    public record PathRow(long allocationId, long flow, long startNanos, long endNanos,
                          long availableHead, long createdNanos) {
    }

    /** 参与水力模型的已批路径（APPROVED 且当前持有额度大于 0）。 */
    public record ActivePathRow(long allocationId, String allocationKey, long flow, long startNanos,
                                long endNanos, long availableHead) {
    }

    /** 路径段行（有序）。 */
    public record PathSegmentRow(long allocationId, int seq, String segmentKey) {
    }

    /** 审批冻结的段参数快照。 */
    public record FreezeSegmentRow(int seq, String segmentKey, long resistance, int version) {
    }

    /** 审批冻结的时片总流量快照。 */
    public record FreezeFlowRow(String segmentKey, long sliceStartNanos, long sliceEndNanos,
                                long totalFlow) {
    }

    private static final RowMapper<SegmentRow> SEGMENT_MAPPER = (rs, n) -> new SegmentRow(
            rs.getLong("id"), rs.getString("segment_key"), rs.getLong("resistance"),
            rs.getInt("version"), rs.getLong("created_nanos"), rs.getLong("updated_nanos"));

    private static final String SEGMENT_SELECT =
            "SELECT id, segment_key, resistance, version, created_nanos, updated_nanos";

    private static final RowMapper<PathRow> PATH_MAPPER = (rs, n) -> new PathRow(
            rs.getLong("allocation_id"), rs.getLong("flow"), rs.getLong("start_nanos"),
            rs.getLong("end_nanos"), rs.getLong("available_head"), rs.getLong("created_nanos"));

    private static final RowMapper<ActivePathRow> ACTIVE_PATH_MAPPER = (rs, n) -> new ActivePathRow(
            rs.getLong("allocation_id"), rs.getString("allocation_key"), rs.getLong("flow"),
            rs.getLong("start_nanos"), rs.getLong("end_nanos"), rs.getLong("available_head"));

    private static final RowMapper<PathSegmentRow> PATH_SEGMENT_MAPPER = (rs, n) -> new PathSegmentRow(
            rs.getLong("allocation_id"), rs.getInt("seq"), rs.getString("segment_key"));

    private static final RowMapper<FreezeSegmentRow> FREEZE_SEGMENT_MAPPER = (rs, n) ->
            new FreezeSegmentRow(rs.getInt("seq"), rs.getString("segment_key"),
                    rs.getLong("resistance"), rs.getInt("version"));

    private static final RowMapper<FreezeFlowRow> FREEZE_FLOW_MAPPER = (rs, n) -> new FreezeFlowRow(
            rs.getString("segment_key"), rs.getLong("slice_start_nanos"),
            rs.getLong("slice_end_nanos"), rs.getLong("total_flow"));

    private final JdbcTemplate jdbc;

    public HydraulicRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 插入渠段（版本从 1 起）。 */
    public void insertSegment(String segmentKey, long resistance, String actor, long nowNanos) {
        jdbc.update("INSERT INTO channel_segment (segment_key, resistance, version, actor,"
                        + " created_nanos, updated_nanos) VALUES (?, ?, 1, ?, ?, ?)",
                segmentKey, resistance, actor, nowNanos, nowNanos);
    }

    /** 按业务键查询渠段，不存在返回 null。 */
    public SegmentRow findSegmentByKey(String segmentKey) {
        try {
            return jdbc.queryForObject(SEGMENT_SELECT + " FROM channel_segment WHERE segment_key = ?",
                    SEGMENT_MAPPER, segmentKey);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 按业务键锁定渠段行（FOR UPDATE），串行化阻力修改与涉及该段的批准，不存在返回 null。 */
    public SegmentRow lockSegmentByKey(String segmentKey) {
        try {
            return jdbc.queryForObject(
                    SEGMENT_SELECT + " FROM channel_segment WHERE segment_key = ? FOR UPDATE",
                    SEGMENT_MAPPER, segmentKey);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 按业务键升序锁定一批渠段行（FOR UPDATE），供批准时固定相关阻力参数。 */
    public void lockSegmentsByKeys(List<String> segmentKeys) {
        if (segmentKeys.isEmpty()) {
            return;
        }
        String placeholders = String.join(", ", Collections.nCopies(segmentKeys.size(), "?"));
        jdbc.query(SEGMENT_SELECT + " FROM channel_segment WHERE segment_key IN ("
                + placeholders + ") ORDER BY segment_key FOR UPDATE", SEGMENT_MAPPER,
                segmentKeys.toArray());
    }

    /** 更新阻力并递增版本（调用方已在事务内校验 expectedVersion 与水力约束）。 */
    public void updateSegmentResistance(String segmentKey, long resistance, int newVersion,
                                        String actor, long updatedNanos) {
        jdbc.update("UPDATE channel_segment SET resistance = ?, version = ?, actor = ?,"
                        + " updated_nanos = ? WHERE segment_key = ?",
                resistance, newVersion, actor, updatedNanos, segmentKey);
    }

    /** 按业务键集合查询渠段。 */
    public List<SegmentRow> listSegmentsByKeys(Collection<String> segmentKeys) {
        if (segmentKeys.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(", ", Collections.nCopies(segmentKeys.size(), "?"));
        return jdbc.query(SEGMENT_SELECT + " FROM channel_segment WHERE segment_key IN ("
                + placeholders + ")", SEGMENT_MAPPER, segmentKeys.toArray());
    }

    /** 插入路径参数。 */
    public void insertPath(long allocationId, long flow, long startNanos, long endNanos,
                           long availableHead, long createdNanos) {
        jdbc.update("INSERT INTO allocation_path (allocation_id, flow, start_nanos, end_nanos,"
                        + " available_head, created_nanos) VALUES (?, ?, ?, ?, ?, ?)",
                allocationId, flow, startNanos, endNanos, availableHead, createdNanos);
    }

    /** 插入路径段（有序）。 */
    public void insertPathSegment(long allocationId, int seq, String segmentKey) {
        jdbc.update("INSERT INTO allocation_path_segment (allocation_id, seq, segment_key)"
                + " VALUES (?, ?, ?)", allocationId, seq, segmentKey);
    }

    /** 按申请主键查询路径参数，无路径返回 null。 */
    public PathRow findPathByAllocationId(long allocationId) {
        try {
            return jdbc.queryForObject(
                    "SELECT allocation_id, flow, start_nanos, end_nanos, available_head, created_nanos"
                            + " FROM allocation_path WHERE allocation_id = ?",
                    PATH_MAPPER, allocationId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 申请路径段业务键，按 seq 升序。 */
    public List<String> listPathSegmentKeys(long allocationId) {
        return jdbc.queryForList(
                "SELECT segment_key FROM allocation_path_segment WHERE allocation_id = ? ORDER BY seq",
                String.class, allocationId);
    }

    /** 当前参与水力模型的全部已批路径（APPROVED 且持有额度大于 0）。 */
    public List<ActivePathRow> listActivePaths() {
        return jdbc.query(
                "SELECT p.allocation_id, a.allocation_key, p.flow, p.start_nanos, p.end_nanos,"
                        + " p.available_head FROM allocation_path p"
                        + " JOIN allocation a ON a.id = p.allocation_id"
                        + " WHERE a.status = 'APPROVED' AND a.held_amount > 0",
                ACTIVE_PATH_MAPPER);
    }

    /** 批量查询一批申请的路径段，按申请、序号升序。 */
    public List<PathSegmentRow> listPathSegments(Collection<Long> allocationIds) {
        if (allocationIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(", ", Collections.nCopies(allocationIds.size(), "?"));
        return jdbc.query("SELECT allocation_id, seq, segment_key FROM allocation_path_segment"
                        + " WHERE allocation_id IN (" + placeholders + ") ORDER BY allocation_id, seq",
                PATH_SEGMENT_MAPPER, allocationIds.toArray());
    }

    /** 写入审批冻结的段参数快照。 */
    public void insertFreezeSegment(long allocationId, int seq, String segmentKey, long resistance,
                                    int version) {
        jdbc.update("INSERT INTO path_freeze_segment (allocation_id, seq, segment_key, resistance,"
                + " version) VALUES (?, ?, ?, ?, ?)", allocationId, seq, segmentKey, resistance, version);
    }

    /** 写入审批冻结的时片总流量快照。 */
    public void insertFreezeFlow(long allocationId, String segmentKey, long sliceStartNanos,
                                 long sliceEndNanos, long totalFlow) {
        jdbc.update("INSERT INTO path_freeze_flow (allocation_id, segment_key, slice_start_nanos,"
                        + " slice_end_nanos, total_flow) VALUES (?, ?, ?, ?, ?)",
                allocationId, segmentKey, sliceStartNanos, sliceEndNanos, totalFlow);
    }

    /** 审批冻结的段参数快照，按 seq 升序。 */
    public List<FreezeSegmentRow> listFreezeSegments(long allocationId) {
        return jdbc.query("SELECT seq, segment_key, resistance, version FROM path_freeze_segment"
                + " WHERE allocation_id = ? ORDER BY seq", FREEZE_SEGMENT_MAPPER, allocationId);
    }

    /** 审批冻结的时片总流量快照，按渠段、时片升序。 */
    public List<FreezeFlowRow> listFreezeFlows(long allocationId) {
        return jdbc.query("SELECT segment_key, slice_start_nanos, slice_end_nanos, total_flow"
                        + " FROM path_freeze_flow WHERE allocation_id = ?"
                        + " ORDER BY segment_key, slice_start_nanos", FREEZE_FLOW_MAPPER, allocationId);
    }
}
