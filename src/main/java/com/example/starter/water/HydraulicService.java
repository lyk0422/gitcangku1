package com.example.starter.water;

import com.example.starter.water.HydraulicRepository.ActivePathRow;
import com.example.starter.water.HydraulicRepository.FreezeFlowRow;
import com.example.starter.water.HydraulicRepository.FreezeSegmentRow;
import com.example.starter.water.HydraulicRepository.PathRow;
import com.example.starter.water.HydraulicRepository.PathSegmentRow;
import com.example.starter.water.HydraulicRepository.SegmentRow;
import com.example.starter.water.WaterRepository.AllocationRow;
import com.example.starter.water.WaterRepository.WindowRow;
import com.example.starter.water.dto.Dtos.AllocationPathResponse;
import com.example.starter.water.dto.Dtos.FrozenSegmentView;
import com.example.starter.water.dto.Dtos.FrozenSliceView;
import com.example.starter.water.dto.Dtos.PathAllocationResponse;
import com.example.starter.water.dto.Dtos.SegmentResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 简化水力模型服务（仅合成参数，不模拟真实泵站）。
 *
 * <p>模型：新类型申请携带 1..5 段有序无重复渠段、恒定正整数流量与 UTC 整分钟左闭右开时段，
 * 流量 × 分钟数 = 原申请水量。渠段阻力 k 为正整数，段损失 = k × 该段同时总流量平方；
 * 任一时片路径损失之和不得超过申请指定正整数可用水头。</p>
 *
 * <p>批准（含转让批准）时将全部已批曲线按起止切片，对每个受影响申请的完整路径重算；
 * 本单合法却使旧单水头不足同样 422 且所有额度不变。阻力修改携带 expectedVersion，
 * 导致现存申请越限返回 409。无路径的旧申请不参与模型。审批冻结路径、各时片总流量与参数版本。
 * 全部运算为精确整数运算，平方与求和做溢出安全比较，不会溢出。</p>
 */
@Service
public class HydraulicService {

    static final long MINUTE_NANOS = 60_000_000_000L;
    /** long 范围内可安全平方的最大值：floor(sqrt(Long.MAX_VALUE))。 */
    private static final long MAX_SQUARE_BASE = 3_037_000_499L;
    private static final int MAX_SEGMENTS = 5;

    /** 路径参数快照（用于转让继承与一致性重算）。 */
    public record PathSpec(long flow, long startNanos, long endNanos, long availableHead,
                           List<String> segmentKeys) {
        /** 路径对应水量：流量 × 分钟数。 */
        public BigDecimal volume() {
            long minutes = (endNanos - startNanos) / MINUTE_NANOS;
            return BigDecimal.valueOf(Math.multiplyExact(flow, minutes));
        }
    }

    /** 参与重算的一条已批路径及其有序段。 */
    private record ActivePath(ActivePathRow row, List<String> segmentKeys) {
    }

    private final HydraulicRepository hydraulicRepository;
    private final WaterRepository waterRepository;
    private final CommandExecutor commands;

    public HydraulicService(HydraulicRepository hydraulicRepository, WaterRepository waterRepository,
                            CommandExecutor commands) {
        this.hydraulicRepository = hydraulicRepository;
        this.waterRepository = waterRepository;
        this.commands = commands;
    }

    // ------------------------------------------------------------------
    // 渠段
    // ------------------------------------------------------------------

    /** 创建渠段，阻力版本从 1 起。 */
    public SegmentResponse createSegment(String requestId, String segmentKey, Long resistance,
                                         String actor) {
        WaterService.requireKey("requestId", requestId);
        WaterService.requireKey("segmentKey", segmentKey);
        WaterService.requireKey("X-Actor-Id", actor);
        long k = requirePositive("resistance", resistance);
        String params = "SEGMENT_CREATE|" + segmentKey + "|" + k + "|" + actor;
        return commands.run("SEGMENT_CREATE", requestId, params, SegmentResponse.class, () -> {
            if (hydraulicRepository.findSegmentByKey(segmentKey) != null) {
                throw ApiException.conflict("SEGMENT_EXISTS", "渠段已存在: " + segmentKey);
            }
            long now = WaterService.nowNanos();
            try {
                hydraulicRepository.insertSegment(segmentKey, k, actor, now);
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("SEGMENT_EXISTS", "渠段已存在: " + segmentKey);
            }
            return toSegmentResponse(hydraulicRepository.findSegmentByKey(segmentKey));
        });
    }

    /** 修改渠段阻力：携带 expectedVersion；导致现存已批申请越限返回 409，版本与阻力不变。 */
    public SegmentResponse updateResistance(String requestId, String segmentKey, Long resistance,
                                            Long expectedVersion, String actor) {
        WaterService.requireKey("requestId", requestId);
        WaterService.requireKey("segmentKey", segmentKey);
        WaterService.requireKey("X-Actor-Id", actor);
        long k = requirePositive("resistance", resistance);
        if (expectedVersion == null || expectedVersion < 1) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "expectedVersion 必须为不小于 1 的整数");
        }
        String params = "SEGMENT_UPDATE_RESISTANCE|" + segmentKey + "|" + k + "|" + expectedVersion
                + "|" + actor;
        return commands.run("SEGMENT_UPDATE_RESISTANCE", requestId, params, SegmentResponse.class, () -> {
            SegmentRow segment = hydraulicRepository.lockSegmentByKey(segmentKey);
            if (segment == null) {
                throw ApiException.notFound("SEGMENT_NOT_FOUND", "渠段不存在: " + segmentKey);
            }
            if (segment.version() != expectedVersion) {
                throw ApiException.conflict("VERSION_CONFLICT",
                        "渠段当前版本 " + segment.version() + " 与 expectedVersion " + expectedVersion + " 不一致");
            }
            verifyWithResistance(segmentKey, k);
            hydraulicRepository.updateSegmentResistance(segmentKey, k, segment.version() + 1, actor,
                    WaterService.nowNanos());
            return toSegmentResponse(hydraulicRepository.findSegmentByKey(segmentKey));
        });
    }

    /** 查询渠段。 */
    public SegmentResponse getSegment(String segmentKey) {
        SegmentRow segment = hydraulicRepository.findSegmentByKey(segmentKey);
        if (segment == null) {
            throw ApiException.notFound("SEGMENT_NOT_FOUND", "渠段不存在: " + segmentKey);
        }
        return toSegmentResponse(segment);
    }

    // ------------------------------------------------------------------
    // 新类型申请
    // ------------------------------------------------------------------

    /** 提交新类型配水申请：派生原申请水量 = 流量 × 分钟数，初始 REQUESTED。 */
    public PathAllocationResponse submitPathAllocation(String requestId, String allocationKey,
                                                       Long windowId, String userId, Long flow,
                                                       String startUtc, String endUtc,
                                                       Long availableHead, List<String> segments,
                                                       String actor) {
        WaterService.requireKey("requestId", requestId);
        WaterService.requireKey("allocationKey", allocationKey);
        WaterService.requireKey("userId", userId);
        WaterService.requireKey("X-Actor-Id", actor);
        if (windowId == null) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "windowId 不能为空");
        }
        long flowValue = requirePositive("flow", flow);
        long head = requirePositive("availableHead", availableHead);
        long startNanos = parseMinute("startUtc", startUtc);
        long endNanos = parseMinute("endUtc", endUtc);
        if (startNanos >= endNanos) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "startUtc 必须早于 endUtc");
        }
        List<String> segmentKeys = validateSegments(segments);
        long minutes = (endNanos - startNanos) / MINUTE_NANOS;
        final BigDecimal amount;
        try {
            amount = BigDecimal.valueOf(Math.multiplyExact(flowValue, minutes));
        } catch (ArithmeticException e) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "流量 × 分钟数超出可表示范围");
        }
        String params = "PATH_ALLOCATION_SUBMIT|" + allocationKey + "|" + windowId + "|" + userId + "|"
                + flowValue + "|" + startNanos + "|" + endNanos + "|" + head + "|"
                + String.join(",", segmentKeys) + "|" + actor;
        return commands.run("PATH_ALLOCATION_SUBMIT", requestId, params, PathAllocationResponse.class,
                () -> {
                    WindowRow window = waterRepository.lockWindowById(windowId);
                    if (window == null) {
                        throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
                    }
                    for (String segmentKey : segmentKeys) {
                        if (hydraulicRepository.findSegmentByKey(segmentKey) == null) {
                            throw ApiException.notFound("SEGMENT_NOT_FOUND", "渠段不存在: " + segmentKey);
                        }
                    }
                    long now = WaterService.nowNanos();
                    long id = waterRepository.insertAllocation(allocationKey, windowId, userId, amount,
                            actor, now);
                    hydraulicRepository.insertPath(id, flowValue, startNanos, endNanos, head, now);
                    for (int i = 0; i < segmentKeys.size(); i++) {
                        hydraulicRepository.insertPathSegment(id, i, segmentKeys.get(i));
                    }
                    AllocationRow row = waterRepository.findAllocationByKey(allocationKey);
                    return new PathAllocationResponse(row.allocationKey(), row.windowId(), row.userId(),
                            WaterService.fmt(row.amount()), row.status(), flowValue,
                            WaterService.toIso(startNanos), WaterService.toIso(endNanos), head,
                            segmentKeys, WaterService.toIso(row.createdNanos()));
                });
    }

    /** 查询申请路径与审批冻结快照；申请无路径返回 404。 */
    public AllocationPathResponse getAllocationPath(String allocationKey) {
        AllocationRow allocation = waterRepository.findAllocationByKey(allocationKey);
        if (allocation == null) {
            throw ApiException.notFound("ALLOCATION_NOT_FOUND", "配水申请不存在: " + allocationKey);
        }
        PathRow path = hydraulicRepository.findPathByAllocationId(allocation.id());
        if (path == null) {
            throw ApiException.notFound("PATH_NOT_FOUND", "申请无供水路径: " + allocationKey);
        }
        List<String> segments = hydraulicRepository.listPathSegmentKeys(allocation.id());
        List<FrozenSegmentView> frozenSegments = hydraulicRepository.listFreezeSegments(allocation.id())
                .stream()
                .map(f -> new FrozenSegmentView(f.segmentKey(), f.seq(), f.resistance(), f.version()))
                .toList();
        List<FrozenSliceView> frozenSlices = hydraulicRepository.listFreezeFlows(allocation.id())
                .stream()
                .map(f -> new FrozenSliceView(f.segmentKey(), WaterService.toIso(f.sliceStartNanos()),
                        WaterService.toIso(f.sliceEndNanos()), f.totalFlow()))
                .toList();
        return new AllocationPathResponse(allocationKey, path.flow(), WaterService.toIso(path.startNanos()),
                WaterService.toIso(path.endNanos()), path.availableHead(), segments,
                !frozenSegments.isEmpty(), frozenSegments, frozenSlices);
    }

    // ------------------------------------------------------------------
    // 供 WaterService 在同事务内调用
    // ------------------------------------------------------------------

    /** 申请的路径参数快照，无路径返回 null。 */
    public PathSpec findPathSpec(long allocationId) {
        PathRow path = hydraulicRepository.findPathByAllocationId(allocationId);
        if (path == null) {
            return null;
        }
        return new PathSpec(path.flow(), path.startNanos(), path.endNanos(), path.availableHead(),
                hydraulicRepository.listPathSegmentKeys(allocationId));
    }

    /** 转让继承：把源路径整体复制给目标申请。 */
    public void inheritPath(long targetAllocationId, PathSpec spec, long nowNanos) {
        hydraulicRepository.insertPath(targetAllocationId, spec.flow(), spec.startNanos(),
                spec.endNanos(), spec.availableHead(), nowNanos);
        for (int i = 0; i < spec.segmentKeys().size(); i++) {
            hydraulicRepository.insertPathSegment(targetAllocationId, i, spec.segmentKeys().get(i));
        }
    }

    /**
     * 批准（含转让批准）后的水力校验与冻结：申请无路径则直接返回。
     * 先按序锁定本申请路径涉及的渠段行，再对全部已批路径按起止切片重算；
     * 任一已批申请任一时片路径损失之和超过其可用水头则抛 422（同事务回滚，所有额度不变）；
     * 通过后冻结本申请的路径、参数版本与各时片总流量。
     */
    public void verifyAndFreezeIfPath(long allocationId) {
        PathRow path = hydraulicRepository.findPathByAllocationId(allocationId);
        if (path == null) {
            return;
        }
        List<String> ownSegments = hydraulicRepository.listPathSegmentKeys(allocationId);
        hydraulicRepository.lockSegmentsByKeys(ownSegments.stream().sorted().toList());
        List<ActivePath> set = loadActiveSet();
        Map<String, SegmentRow> segments = segmentMeta(set);
        verify(set, segments);
        freeze(allocationId, set, segments);
    }

    // ------------------------------------------------------------------
    // 水力重算核心
    // ------------------------------------------------------------------

    /** 阻力修改前预检：以新阻力重算全部已批路径，越限抛 409。 */
    private void verifyWithResistance(String segmentKey, long newResistance) {
        List<ActivePath> set = loadActiveSet();
        if (set.isEmpty()) {
            return;
        }
        Map<String, SegmentRow> segments = segmentMeta(set);
        SegmentRow current = segments.get(segmentKey);
        if (current == null) {
            // 没有已批路径引用该渠段，阻力修改不影响任何现存申请
            return;
        }
        segments.put(segmentKey, new SegmentRow(current.id(), current.segmentKey(), newResistance,
                current.version(), current.createdNanos(), current.updatedNanos()));
        try {
            verify(set, segments);
        } catch (ApiException e) {
            throw ApiException.conflict("RESISTANCE_BREAKS_HEAD",
                    "阻力修改导致现存申请越限: " + e.getMessage());
        }
    }

    private List<ActivePath> loadActiveSet() {
        List<ActivePathRow> rows = hydraulicRepository.listActivePaths();
        Map<Long, List<String>> segmentsByAllocation = new LinkedHashMap<>();
        for (PathSegmentRow segment : hydraulicRepository.listPathSegments(
                rows.stream().map(ActivePathRow::allocationId).toList())) {
            segmentsByAllocation.computeIfAbsent(segment.allocationId(), k -> new ArrayList<>())
                    .add(segment.segmentKey());
        }
        List<ActivePath> set = new ArrayList<>();
        for (ActivePathRow row : rows) {
            set.add(new ActivePath(row,
                    segmentsByAllocation.getOrDefault(row.allocationId(), List.of())));
        }
        return set;
    }

    private Map<String, SegmentRow> segmentMeta(List<ActivePath> set) {
        Set<String> keys = new HashSet<>();
        for (ActivePath path : set) {
            keys.addAll(path.segmentKeys());
        }
        Map<String, SegmentRow> meta = new HashMap<>();
        for (SegmentRow segment : hydraulicRepository.listSegmentsByKeys(keys)) {
            meta.put(segment.segmentKey(), segment);
        }
        return meta;
    }

    /** 按起止切片重算：每个已批申请在每个时片上，路径各段 k × 同时总流量平方之和不得超过可用水头。 */
    private void verify(List<ActivePath> set, Map<String, SegmentRow> segments) {
        List<long[]> slices = slicesOf(set);
        for (ActivePath path : set) {
            ActivePathRow row = path.row();
            for (long[] slice : slices) {
                if (slice[0] < row.startNanos() || slice[1] > row.endNanos()) {
                    continue;
                }
                long sliceLoss = 0;
                for (String segmentKey : path.segmentKeys()) {
                    long totalFlow = totalFlowOn(set, segmentKey, slice[0], slice[1]);
                    long k = segments.get(segmentKey).resistance();
                    if (lossExceedsHead(k, totalFlow, row.availableHead())) {
                        throw headExceeded(row.allocationKey(), segmentKey, slice);
                    }
                    try {
                        sliceLoss = Math.addExact(sliceLoss, k * totalFlow * totalFlow);
                    } catch (ArithmeticException e) {
                        throw headExceeded(row.allocationKey(), segmentKey, slice);
                    }
                }
                if (sliceLoss > row.availableHead()) {
                    throw headExceeded(row.allocationKey(), null, slice);
                }
            }
        }
    }

    /** 全部已批曲线起止点切出的基本时片（左闭右开）。 */
    private List<long[]> slicesOf(List<ActivePath> set) {
        TreeSet<Long> bounds = new TreeSet<>();
        for (ActivePath path : set) {
            bounds.add(path.row().startNanos());
            bounds.add(path.row().endNanos());
        }
        List<long[]> slices = new ArrayList<>();
        Long previous = null;
        for (Long bound : bounds) {
            if (previous != null && previous < bound) {
                slices.add(new long[]{previous, bound});
            }
            previous = bound;
        }
        return slices;
    }

    /** 时片内某渠段的同时总流量：覆盖该时片且路径含该段的全部已批申请流量之和。 */
    private long totalFlowOn(List<ActivePath> set, String segmentKey, long sliceStart, long sliceEnd) {
        long total = 0;
        for (ActivePath path : set) {
            ActivePathRow row = path.row();
            if (row.startNanos() <= sliceStart && sliceEnd <= row.endNanos()
                    && path.segmentKeys().contains(segmentKey)) {
                total = Math.addExact(total, row.flow());
            }
        }
        return total;
    }

    /** 溢出安全判断 k × totalFlow² 是否超过可用水头（totalFlow ≥ 1，k ≥ 1）。 */
    private boolean lossExceedsHead(long k, long totalFlow, long head) {
        if (totalFlow > MAX_SQUARE_BASE) {
            return true;
        }
        long square = totalFlow * totalFlow;
        if (square > Long.MAX_VALUE / k) {
            return true;
        }
        return k * square > head;
    }

    private ApiException headExceeded(String allocationKey, String segmentKey, long[] slice) {
        String where = segmentKey == null ? "路径损失之和" : "渠段 " + segmentKey + " 损失";
        return ApiException.unprocessable("HYDRAULIC_HEAD_EXCEEDED",
                "申请 " + allocationKey + " 在时片 [" + WaterService.toIso(slice[0]) + ", "
                        + WaterService.toIso(slice[1]) + ") " + where + "超过可用水头");
    }

    /** 冻结：批准时刻的路径段参数（阻力与版本）及各时片总流量。 */
    private void freeze(long allocationId, List<ActivePath> set, Map<String, SegmentRow> segments) {
        ActivePath self = set.stream()
                .filter(p -> p.row().allocationId() == allocationId)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("已批申请不在水力集合中: " + allocationId));
        int seq = 0;
        for (String segmentKey : self.segmentKeys()) {
            SegmentRow meta = segments.get(segmentKey);
            hydraulicRepository.insertFreezeSegment(allocationId, seq++, segmentKey, meta.resistance(),
                    meta.version());
        }
        ActivePathRow row = self.row();
        // 时片只按与本申请共用渠段的已批曲线起止切分，无关曲线不影响快照形态
        List<ActivePath> relevant = set.stream()
                .filter(p -> p.row().allocationId() == allocationId
                        || p.segmentKeys().stream().anyMatch(self.segmentKeys()::contains))
                .toList();
        for (long[] slice : slicesOf(relevant)) {
            if (slice[0] < row.startNanos() || slice[1] > row.endNanos()) {
                continue;
            }
            for (String segmentKey : self.segmentKeys()) {
                hydraulicRepository.insertFreezeFlow(allocationId, segmentKey, slice[0], slice[1],
                        totalFlowOn(set, segmentKey, slice[0], slice[1]));
            }
        }
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    private static long requirePositive(String field, Long value) {
        if (value == null || value <= 0) {
            throw ApiException.badRequest("INVALID_ARGUMENT", field + " 必须为正整数");
        }
        return value;
    }

    /** 解析 UTC 整分钟时刻（左闭右开时段的端点必须整分钟对齐）。 */
    private static long parseMinute(String field, String value) {
        long nanos = WaterService.parseInstant(field, value);
        if (nanos % MINUTE_NANOS != 0) {
            throw ApiException.badRequest("INVALID_ARGUMENT", field + " 必须为 UTC 整分钟时刻: " + value);
        }
        return nanos;
    }

    /** 校验路径段：1..5 段、有序、无重复、键格式合法。 */
    private static List<String> validateSegments(List<String> segments) {
        if (segments == null || segments.isEmpty() || segments.size() > MAX_SEGMENTS) {
            throw ApiException.badRequest("INVALID_ARGUMENT",
                    "segments 必须为 1.." + MAX_SEGMENTS + " 段有序渠段");
        }
        List<String> keys = new ArrayList<>(segments.size());
        Set<String> seen = new HashSet<>();
        for (String segment : segments) {
            WaterService.requireKey("segments", segment);
            if (!seen.add(segment)) {
                throw ApiException.badRequest("INVALID_ARGUMENT", "segments 存在重复渠段: " + segment);
            }
            keys.add(segment);
        }
        return keys;
    }

    private SegmentResponse toSegmentResponse(SegmentRow row) {
        return new SegmentResponse(row.segmentKey(), row.resistance(), row.version(),
                WaterService.toIso(row.createdNanos()), WaterService.toIso(row.updatedNanos()));
    }
}
