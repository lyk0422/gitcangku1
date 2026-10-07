package com.example.starter.water;

import com.example.starter.water.WaterRepository.AllocationRow;
import com.example.starter.water.WaterRepository.CanalEdgeRow;
import com.example.starter.water.WaterRepository.CanalGraphRow;
import com.example.starter.water.WaterRepository.CommandRow;
import com.example.starter.water.WaterRepository.CurtailmentRow;
import com.example.starter.water.WaterRepository.EdgeRef;
import com.example.starter.water.WaterRepository.PathSegmentRow;
import com.example.starter.water.WaterRepository.TransferRow;
import com.example.starter.water.WaterRepository.WindowRow;
import com.example.starter.water.dto.Dtos.AllocationResponse;
import com.example.starter.water.dto.Dtos.CanalEdgeRef;
import com.example.starter.water.dto.Dtos.CanalEdgeSpec;
import com.example.starter.water.dto.Dtos.CanalEdgeView;
import com.example.starter.water.dto.Dtos.CanalGraphRequest;
import com.example.starter.water.dto.Dtos.CanalGraphResponse;
import com.example.starter.water.dto.Dtos.CapacityResponse;
import com.example.starter.water.dto.Dtos.CurtailmentResponse;
import com.example.starter.water.dto.Dtos.HistoryResponse;
import com.example.starter.water.dto.Dtos.PathSegment;
import com.example.starter.water.dto.Dtos.TransferListResponse;
import com.example.starter.water.dto.Dtos.TransferResponse;
import com.example.starter.water.dto.Dtos.WindowResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 灌区配水业务服务。
 *
 * <p>幂等：每个写命令携带 commandKey，事务内先占位插入命令行再执行业务并写回响应；
 * 同键同参重放返回首次结果，同键改参返回 409。</p>
 *
 * <p>并发：批准申请与创建/取消限供在同一事务内先对窗口行 SELECT ... FOR UPDATE，
 * 按事务提交顺序生效，保证已批准总量永不超过最终可用总量。</p>
 */
@Service
public class WaterService {

    static final String STATUS_REQUESTED = "REQUESTED";
    static final String STATUS_APPROVED = "APPROVED";
    static final String STATUS_CANCELLED = "CANCELLED";

    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final Pattern AMOUNT_PATTERN = Pattern.compile("\\d{1,16}(\\.\\d{1,3})?");
    private static final Pattern FLOW_PATTERN = Pattern.compile("\\d{1,9}");
    private static final Pattern KEY_PATTERN = Pattern.compile("[\\w.\\-:]{1,128}");
    private static final int MAX_CANAL_NODES = 10;
    private static final int MAX_CANAL_EDGES = 20;

    private final WaterRepository repository;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper;

    public WaterService(WaterRepository repository, PlatformTransactionManager transactionManager,
                        ObjectMapper objectMapper) {
        this.repository = repository;
        this.tx = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------
    // 命令入口
    // ------------------------------------------------------------------

    /** 创建供水窗口。 */
    public WindowResponse createWindow(String commandKey, String windowKey, String channelId,
                                       String startUtc, String endUtc, String plannedVolume) {
        return createWindow(commandKey, windowKey, channelId, startUtc, endUtc, plannedVolume, null);
    }

    /** 创建供水窗口；canalGraph 非空时登记渠网（节点 1 至 10、渠段至多 20），成为渠网窗口。 */
    public WindowResponse createWindow(String commandKey, String windowKey, String channelId,
                                       String startUtc, String endUtc, String plannedVolume,
                                       CanalGraphRequest canalGraph) {
        requireKey("commandKey", commandKey);
        requireKey("windowKey", windowKey);
        requireKey("channelId", channelId);
        long startNanos = parseInstant("startUtc", startUtc);
        long endNanos = parseInstant("endUtc", endUtc);
        if (startNanos >= endNanos) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "startUtc 必须早于 endUtc");
        }
        BigDecimal planned = parseAmount("plannedVolume", plannedVolume);
        ParsedGraph graph = canalGraph == null ? null : validateGraph(canalGraph);
        String params = "WINDOW_CREATE|" + windowKey + "|" + channelId + "|" + startNanos + "|" + endNanos
                + "|" + planned.toPlainString() + (graph == null ? "" : "|G:" + graph.canonical());
        return runCommand("WINDOW_CREATE", commandKey, params, WindowResponse.class, () -> {
            if (repository.existsOverlappingWindow(channelId, startNanos, endNanos)) {
                throw ApiException.conflict("WINDOW_OVERLAP", "同一渠道存在时间重叠的供水窗口");
            }
            long id = repository.insertWindow(windowKey, channelId, startNanos, endNanos, planned, nowNanos());
            if (graph != null) {
                long now = nowNanos();
                repository.insertCanalGraph(id, graph.nodes().size(), now);
                for (String node : graph.nodes()) {
                    repository.insertCanalNode(id, node);
                }
                for (ParsedEdge edge : graph.edges()) {
                    repository.insertCanalEdge(id, edge.from(), edge.to(), edge.capacity());
                }
            }
            WindowRow row = repository.findWindowById(id);
            return toWindowResponse(row, null);
        });
    }

    /** 提交配水申请，申请人为 actor。 */
    public AllocationResponse submitAllocation(String commandKey, String allocationKey, Long windowId,
                                               String userId, String amount, String actor) {
        return submitAllocation(commandKey, allocationKey, windowId, userId, amount, null, null, actor);
    }

    /**
     * 提交配水申请；渠网窗口必须携带 path（1 至 8 条首尾相接渠段）与 flowRate（整数，立方米每秒），
     * 普通窗口携带路径参数将被拒绝（不适用渠网门禁）。路径断开或节点重复返回 400。
     */
    public AllocationResponse submitAllocation(String commandKey, String allocationKey, Long windowId,
                                               String userId, String amount, List<PathSegment> path,
                                               String flowRate, String actor) {
        requireKey("commandKey", commandKey);
        requireKey("allocationKey", allocationKey);
        requireKey("userId", userId);
        requireKey("X-Actor-Id", actor);
        if (windowId == null) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "windowId 不能为空");
        }
        BigDecimal qty = parseAmount("amount", amount);
        boolean canalRequested = path != null || flowRate != null;
        Long flow = null;
        String pathParams = "";
        if (canalRequested) {
            if (path == null || flowRate == null) {
                throw ApiException.badRequest("INVALID_ARGUMENT", "渠网申请必须同时携带 path 与 flowRate");
            }
            flow = parseFlow("flowRate", flowRate);
            CanalSupport.validatePath(path);
            StringBuilder canonical = new StringBuilder();
            for (PathSegment segment : path) {
                if (canonical.length() > 0) {
                    canonical.append(',');
                }
                canonical.append(segment.from()).append('>').append(segment.to());
            }
            pathParams = "|P:" + canonical + "|F:" + flow;
        }
        String params = "ALLOCATION_SUBMIT|" + allocationKey + "|" + windowId + "|" + userId + "|"
                + fmt(qty) + "|" + actor + pathParams;
        List<PathSegment> pathSnapshot = canalRequested ? List.copyOf(path) : null;
        Long flowValue = flow;
        return runCommand("ALLOCATION_SUBMIT", commandKey, params, AllocationResponse.class, () -> {
            WindowRow window = repository.lockWindowById(windowId);
            if (window == null) {
                throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
            }
            CanalGraphRow graph = repository.findCanalGraph(windowId);
            if (canalRequested) {
                if (graph == null) {
                    throw ApiException.badRequest("CANAL_NOT_SUPPORTED",
                            "普通窗口不适用渠网门禁，不能携带路径");
                }
                for (PathSegment segment : pathSnapshot) {
                    if (repository.findCanalEdge(windowId, segment.from(), segment.to()) == null) {
                        throw ApiException.badRequest("PATH_EDGE_UNKNOWN",
                                "路径渠段不在渠网图中: " + segment.from() + "->" + segment.to());
                    }
                }
            } else if (graph != null) {
                throw ApiException.badRequest("PATH_REQUIRED", "渠网窗口申请必须携带 path 与 flowRate");
            }
            long id = repository.insertAllocation(allocationKey, windowId, userId, qty, flowValue, actor,
                    nowNanos());
            if (pathSnapshot != null) {
                int seq = 1;
                for (PathSegment segment : pathSnapshot) {
                    repository.insertAllocationPath(id, seq++, segment.from(), segment.to());
                }
            }
            return toAllocationResponse(repository.findAllocationByKey(allocationKey));
        });
    }

    /** 批准申请：加入本申请后已批准总量不得超过当前可用总量，否则 422。 */
    public AllocationResponse approveAllocation(String commandKey, String allocationKey) {
        return approveAllocation(commandKey, allocationKey, null);
    }

    /**
     * 批准申请：普通窗口仅校验额度；渠网窗口要求非空操作者，并串行校验每段已批准流量之和不超容量、
     * 与全部已批准路径的边并集无有向环（合环 409 且不占任何新额度、流量）。
     */
    public AllocationResponse approveAllocation(String commandKey, String allocationKey, String actor) {
        requireKey("commandKey", commandKey);
        requireKey("allocationKey", allocationKey);
        String params = actor == null ? "ALLOCATION_APPROVE|" + allocationKey
                : "ALLOCATION_APPROVE|" + allocationKey + "|" + actor;
        return runCommand("ALLOCATION_APPROVE", commandKey, params, AllocationResponse.class, () -> {
            AllocationRow allocation = repository.findAllocationByKey(allocationKey);
            if (allocation == null) {
                throw ApiException.notFound("ALLOCATION_NOT_FOUND", "配水申请不存在: " + allocationKey);
            }
            WindowRow window = lockWindowOf(allocation);
            // 窗口锁内重读申请，避免与转让/取消并发时使用过期状态与持有额度
            allocation = repository.lockAllocationByKey(allocationKey);
            switch (allocation.status()) {
                case STATUS_CANCELLED ->
                        throw ApiException.conflict("ALLOCATION_CANCELLED", "已取消的申请不能批准");
                case STATUS_APPROVED ->
                        throw ApiException.conflict("ALLOCATION_ALREADY_APPROVED", "申请已批准，不能重复批准");
                default -> {
                }
            }
            BigDecimal available = availableTotal(window);
            BigDecimal approved = repository.sumApprovedAmount(window.id());
            if (approved.add(allocation.amount()).compareTo(available) > 0) {
                throw ApiException.quotaExceeded("批准后将超过当前可用总量 " + fmt(available));
            }
            if (repository.findCanalGraph(window.id()) != null) {
                if (actor == null || actor.isBlank()) {
                    throw ApiException.badRequest("ACTOR_REQUIRED", "渠网窗口批准必须携带非空 X-Actor-Id");
                }
                applyCanalGate(window.id(), allocation);
            }
            repository.updateAllocationStatus(allocation.id(), STATUS_APPROVED, nowNanos());
            return toAllocationResponse(repository.findAllocationByKey(allocationKey));
        });
    }

    /** 渠网门禁：渠段须在图中、每段已批准流量加本申请不超容量、与已批准路径边并集无有向环。 */
    private void applyCanalGate(long windowId, AllocationRow allocation) {
        List<PathSegmentRow> path = repository.listAllocationPath(allocation.id());
        if (path.isEmpty() || allocation.flowRate() == null) {
            throw ApiException.conflict("PATH_MISSING", "渠网窗口申请缺少路径快照");
        }
        long flow = allocation.flowRate();
        for (PathSegmentRow segment : path) {
            CanalEdgeRow edge = repository.findCanalEdge(windowId, segment.fromNode(), segment.toNode());
            if (edge == null) {
                throw ApiException.conflict("EDGE_NOT_IN_GRAPH",
                        "路径渠段已被图修订移除: " + segment.fromNode() + "->" + segment.toNode());
            }
            long used = repository.sumApprovedFlowOnEdge(windowId, segment.fromNode(), segment.toNode());
            if (BigDecimal.valueOf(used).add(BigDecimal.valueOf(flow)).compareTo(edge.capacity()) > 0) {
                throw ApiException.flowExceeded("渠段 " + segment.fromNode() + "->" + segment.toNode()
                        + " 已批准流量 " + used + "，加入 " + flow + " 后超过容量 " + fmt(edge.capacity()));
            }
        }
        List<PathSegment> union = new ArrayList<>();
        for (EdgeRef edge : repository.listApprovedPathEdges(windowId)) {
            union.add(new PathSegment(edge.fromNode(), edge.toNode()));
        }
        for (PathSegmentRow segment : path) {
            union.add(new PathSegment(segment.fromNode(), segment.toNode()));
        }
        if (CanalSupport.hasCycle(union)) {
            throw ApiException.conflict("PATH_CYCLE", "与已批准路径合成有向环，拒绝批准");
        }
    }

    /** 取消申请：仅申请人本人可取消 REQUESTED/APPROVED，取消不可恢复并立即释放水量。 */
    public AllocationResponse cancelAllocation(String commandKey, String allocationKey, String actor) {
        requireKey("commandKey", commandKey);
        requireKey("allocationKey", allocationKey);
        requireKey("X-Actor-Id", actor);
        String params = "ALLOCATION_CANCEL|" + allocationKey + "|" + actor;
        return runCommand("ALLOCATION_CANCEL", commandKey, params, AllocationResponse.class, () -> {
            AllocationRow allocation = repository.findAllocationByKey(allocationKey);
            if (allocation == null) {
                throw ApiException.notFound("ALLOCATION_NOT_FOUND", "配水申请不存在: " + allocationKey);
            }
            lockWindowOf(allocation);
            // 窗口锁内重读申请，避免与转让/并发取消使用过期状态
            allocation = repository.lockAllocationByKey(allocationKey);
            if (!allocation.requester().equals(actor)) {
                throw ApiException.conflict("NOT_OWNER", "只有申请人本人可以取消该申请");
            }
            if (STATUS_CANCELLED.equals(allocation.status())) {
                throw ApiException.conflict("ALLOCATION_ALREADY_CANCELLED", "申请已取消，不能重复取消");
            }
            repository.updateAllocationStatus(allocation.id(), STATUS_CANCELLED, nowNanos());
            return toAllocationResponse(repository.findAllocationByKey(allocationKey));
        });
    }

    /**
     * 同窗口额度原子转让：源申请人（actor）把自己 APPROVED 申请的额度转给同窗口、不同用水户的
     * REQUESTED 目标申请，转让额等于目标原申请水量的全部额度。
     * 源扣减、目标批准、不可变流水在同一事务完成，窗口已批准总量不变；失败回滚无任何额度变化。
     */
    public TransferResponse transferAllocation(String commandKey, String transferKey, String sourceAllocationKey,
                                               String targetAllocationKey, String actor) {
        requireKey("commandKey", commandKey);
        requireKey("transferKey", transferKey);
        requireKey("sourceAllocationKey", sourceAllocationKey);
        requireKey("targetAllocationKey", targetAllocationKey);
        requireKey("X-Actor-Id", actor);
        String params = "TRANSFER|" + transferKey + "|" + sourceAllocationKey + "|" + targetAllocationKey + "|"
                + actor;
        return runCommand("TRANSFER", commandKey, params, TransferResponse.class, () -> {
            if (repository.findTransferByKey(transferKey) != null) {
                throw ApiException.conflict("TRANSFER_KEY_REUSED", "transferKey 已被使用: " + transferKey);
            }
            AllocationRow source = repository.findAllocationByKey(sourceAllocationKey);
            if (source == null) {
                throw ApiException.notFound("SOURCE_ALLOCATION_NOT_FOUND",
                        "转出申请不存在: " + sourceAllocationKey);
            }
            AllocationRow target = repository.findAllocationByKey(targetAllocationKey);
            if (target == null) {
                throw ApiException.notFound("TARGET_ALLOCATION_NOT_FOUND",
                        "转入申请不存在: " + targetAllocationKey);
            }
            // 先锁窗口行，与普通批准、取消、限供调整按事务提交顺序串行裁决
            lockWindowOf(source);
            // 窗口锁内再锁定源/目标申请行，得到最新状态与持有额度
            AllocationRow lockedSource = repository.lockAllocationByKey(sourceAllocationKey);
            AllocationRow lockedTarget = repository.lockAllocationByKey(targetAllocationKey);
            if (!lockedSource.requester().equals(actor)) {
                throw ApiException.conflict("NOT_OWNER", "只有转出申请人本人可以发起转让");
            }
            if (!STATUS_APPROVED.equals(lockedSource.status())) {
                throw ApiException.conflict("SOURCE_NOT_APPROVED", "转出申请必须为 APPROVED 状态");
            }
            if (!STATUS_REQUESTED.equals(lockedTarget.status())) {
                throw ApiException.conflict("TARGET_NOT_REQUESTED", "转入申请必须为 REQUESTED 状态");
            }
            if (lockedSource.windowId() != lockedTarget.windowId()) {
                throw ApiException.conflict("DIFFERENT_WINDOW", "转让双方必须属于同一供水窗口");
            }
            if (lockedSource.userId().equals(lockedTarget.userId())) {
                throw ApiException.conflict("SAME_USER", "转让双方必须属于不同用水户");
            }
            BigDecimal amount = lockedTarget.amount();
            if (lockedSource.heldAmount().compareTo(amount) < 0) {
                throw ApiException.quotaExceeded("源申请当前持有额度 " + fmt(lockedSource.heldAmount())
                        + " 不足，无法转让 " + fmt(amount));
            }
            if (repository.findCanalGraph(lockedSource.windowId()) != null) {
                // 渠网窗口：仅允许同窗同路径、同流量且一次性转出全部持有额度；涉及新路径须取消后重新申请
                List<PathSegmentRow> sourcePath = repository.listAllocationPath(lockedSource.id());
                List<PathSegmentRow> targetPath = repository.listAllocationPath(lockedTarget.id());
                if (!samePath(sourcePath, targetPath)) {
                    throw ApiException.conflict("TRANSFER_PATH_MISMATCH",
                            "渠网窗口转让要求源与目标路径一致；涉及新路径须取消后重新申请");
                }
                if (!Objects.equals(lockedSource.flowRate(), lockedTarget.flowRate())) {
                    throw ApiException.conflict("TRANSFER_FLOW_MISMATCH", "渠网窗口转让要求源与目标流量一致");
                }
                if (lockedSource.heldAmount().compareTo(amount) != 0) {
                    throw ApiException.conflict("TRANSFER_PARTIAL_NOT_ALLOWED",
                            "渠网窗口转让须一次性转出全部持有额度");
                }
            }
            long now = nowNanos();
            repository.decrementHeldAmount(lockedSource.id(), amount, now);
            repository.updateAllocationStatus(lockedTarget.id(), STATUS_APPROVED, now);
            try {
                repository.insertTransfer(transferKey, lockedSource.windowId(), sourceAllocationKey,
                        targetAllocationKey, amount, actor, now);
            } catch (DuplicateKeyException e) {
                // 并发复用同一 transferKey（换 commandKey）：事务回滚，额度无变化
                throw ApiException.conflict("TRANSFER_KEY_REUSED", "transferKey 已被使用: " + transferKey);
            }
            return toTransferResponse(repository.findTransferByKey(transferKey));
        });
    }

    /** 查询窗口全部转让流水（不可变），按发生顺序返回。 */
    public TransferListResponse getTransfers(long windowId) {
        WindowRow window = repository.findWindowById(windowId);
        if (window == null) {
            throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
        }
        List<TransferResponse> transfers = repository.listTransfers(windowId).stream()
                .map(this::toTransferResponse).toList();
        return new TransferListResponse(windowId, transfers);
    }

    /** 创建限供：仅当当前已批准总量不超过拟定限供水量时允许。 */
    public CurtailmentResponse createCurtailment(String commandKey, long windowId, String volume) {
        requireKey("commandKey", commandKey);
        BigDecimal qty = parseAmount("volume", volume);
        String params = "CURTAILMENT_CREATE|" + windowId + "|" + qty.toPlainString();
        return runCommand("CURTAILMENT_CREATE", commandKey, params, CurtailmentResponse.class, () -> {
            WindowRow window = repository.lockWindowById(windowId);
            if (window == null) {
                throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
            }
            if (qty.compareTo(window.plannedVolume()) > 0) {
                throw ApiException.badRequest("INVALID_ARGUMENT", "限供水量不能超过计划水量 " + fmt(window.plannedVolume()));
            }
            if (repository.findActiveCurtailment(windowId) != null) {
                throw ApiException.conflict("CURTAILMENT_EXISTS", "窗口已存在生效中的限供");
            }
            BigDecimal approved = repository.sumApprovedAmount(windowId);
            if (approved.compareTo(qty) > 0) {
                throw ApiException.conflict("CURTAILMENT_BELOW_APPROVED",
                        "当前已批准总量 " + fmt(approved) + " 超过拟定限供水量 " + fmt(qty));
            }
            long id = repository.insertCurtailment(windowId, qty, nowNanos());
            return toCurtailmentResponse(repository.findActiveCurtailment(windowId));
        });
    }

    /** 取消当前生效限供，恢复计划水量。 */
    public CurtailmentResponse cancelCurtailment(String commandKey, long windowId) {
        requireKey("commandKey", commandKey);
        String params = "CURTAILMENT_CANCEL|" + windowId;
        return runCommand("CURTAILMENT_CANCEL", commandKey, params, CurtailmentResponse.class, () -> {
            WindowRow window = repository.lockWindowById(windowId);
            if (window == null) {
                throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
            }
            CurtailmentRow active = repository.findActiveCurtailment(windowId);
            if (active == null) {
                throw ApiException.conflict("NO_ACTIVE_CURTAILMENT", "窗口没有生效中的限供");
            }
            repository.cancelCurtailment(active.id(), nowNanos());
            List<CurtailmentRow> all = repository.listCurtailments(windowId);
            return all.stream().filter(c -> c.id() == active.id()).findFirst()
                    .map(this::toCurtailmentResponse).orElseThrow();
        });
    }

    /** 查询窗口当前可用容量。 */
    public CapacityResponse getCapacity(long windowId) {
        WindowRow window = repository.findWindowById(windowId);
        if (window == null) {
            throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
        }
        CurtailmentRow active = repository.findActiveCurtailment(windowId);
        BigDecimal available = active != null ? active.volume() : window.plannedVolume();
        BigDecimal approved = repository.sumApprovedAmount(windowId);
        return new CapacityResponse(window.id(), fmt(window.plannedVolume()),
                active != null ? fmt(active.volume()) : null, fmt(available), fmt(approved),
                fmt(available.subtract(approved)));
    }

    /** 查询窗口历史明细：窗口 + 全部申请 + 全部限供。 */
    public HistoryResponse getHistory(long windowId) {
        WindowRow window = repository.findWindowById(windowId);
        if (window == null) {
            throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
        }
        CurtailmentRow active = repository.findActiveCurtailment(windowId);
        List<AllocationResponse> allocations = repository.listAllocations(windowId).stream()
                .map(this::toAllocationResponse).toList();
        List<CurtailmentResponse> curtailments = repository.listCurtailments(windowId).stream()
                .map(this::toCurtailmentResponse).toList();
        return new HistoryResponse(toWindowResponse(window, active), allocations, curtailments);
    }

    // ------------------------------------------------------------------
    // 幂等命令框架
    // ------------------------------------------------------------------

    /**
     * 在单事务内执行幂等命令：先占位插入命令行，再执行业务并写回响应。
     * 并发同键时占位插入会因唯一约束失败，随后读取已提交的首次结果重放或报 409。
     */
    private <T> T runCommand(String operation, String commandKey, String params, Class<T> type,
                             Supplier<T> business) {
        try {
            return tx.execute(status -> {
                CommandRow existing = repository.findCommand(commandKey);
                if (existing != null) {
                    return replay(existing, operation, params, type);
                }
                repository.insertCommand(commandKey, operation, params, nowNanos());
                T result = business.get();
                repository.updateCommandResponse(commandKey, toJson(result));
                return result;
            });
        } catch (DuplicateKeyException e) {
            CommandRow committed = repository.findCommand(commandKey);
            if (committed == null || committed.response() == null) {
                throw ApiException.conflict("COMMAND_CONFLICT", "相同 commandKey 的命令正在处理，请重试");
            }
            return replay(committed, operation, params, type);
        }
    }

    private <T> T replay(CommandRow existing, String operation, String params, Class<T> type) {
        if (!existing.operation().equals(operation) || !existing.params().equals(params)) {
            throw ApiException.conflict("COMMAND_KEY_REUSED", "相同 commandKey 但参数不同，拒绝重放");
        }
        return fromJson(existing.response(), type);
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    private WindowRow lockWindowOf(AllocationRow allocation) {
        WindowRow window = repository.lockWindowById(allocation.windowId());
        if (window == null) {
            throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + allocation.windowId());
        }
        return window;
    }

    private BigDecimal availableTotal(WindowRow window) {
        CurtailmentRow active = repository.findActiveCurtailment(window.id());
        return active != null ? active.volume() : window.plannedVolume();
    }

    private WindowResponse toWindowResponse(WindowRow row, CurtailmentRow active) {
        BigDecimal available = active != null ? active.volume() : row.plannedVolume();
        CanalGraphRow graph = repository.findCanalGraph(row.id());
        return new WindowResponse(row.id(), row.windowKey(), row.channelId(), toIso(row.startNanos()),
                toIso(row.endNanos()), fmt(row.plannedVolume()),
                active != null ? fmt(active.volume()) : null, fmt(available), toIso(row.createdNanos()),
                graph == null ? null : graph.version());
    }

    private AllocationResponse toAllocationResponse(AllocationRow row) {
        List<PathSegmentRow> pathRows = repository.listAllocationPath(row.id());
        List<PathSegment> path = pathRows.isEmpty() ? null
                : pathRows.stream().map(p -> new PathSegment(p.fromNode(), p.toNode())).toList();
        return new AllocationResponse(row.allocationKey(), row.windowId(), row.userId(), fmt(row.amount()),
                fmt(row.heldAmount()), row.requester(), row.status(),
                toIso(row.createdNanos()), toIso(row.updatedNanos()), path,
                row.flowRate() == null ? null : String.valueOf(row.flowRate()));
    }

    private TransferResponse toTransferResponse(TransferRow row) {
        return new TransferResponse(row.transferKey(), row.windowId(), row.sourceAllocationKey(),
                row.targetAllocationKey(), fmt(row.amount()), row.actor(), toIso(row.createdNanos()));
    }

    private CurtailmentResponse toCurtailmentResponse(CurtailmentRow row) {
        return new CurtailmentResponse(row.id(), row.windowId(), fmt(row.volume()), row.status(),
                toIso(row.createdNanos()), row.cancelledNanos() == null ? null : toIso(row.cancelledNanos()));
    }

    static long nowNanos() {
        return toNanos(Instant.now());
    }

    /** Instant -> UTC 纳秒时间戳。 */
    static long toNanos(Instant instant) {
        return Math.addExact(Math.multiplyExact(instant.getEpochSecond(), NANOS_PER_SECOND), instant.getNano());
    }

    /** UTC 纳秒时间戳 -> Instant。 */
    static Instant toInstant(long nanos) {
        return Instant.ofEpochSecond(Math.floorDiv(nanos, NANOS_PER_SECOND),
                Math.floorMod(nanos, NANOS_PER_SECOND));
    }

    static String toIso(long nanos) {
        return toInstant(nanos).toString();
    }

    static long parseInstant(String field, String value) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest("INVALID_ARGUMENT", field + " 不能为空");
        }
        try {
            return toNanos(Instant.parse(value.trim()));
        } catch (DateTimeParseException | ArithmeticException e) {
            throw ApiException.badRequest("INVALID_ARGUMENT", field + " 不是合法的 UTC 时刻: " + value);
        }
    }

    /** 解析水量：十进制字符串，大于 0，最多 3 位小数。 */
    static BigDecimal parseAmount(String field, String value) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest("INVALID_ARGUMENT", field + " 不能为空");
        }
        String trimmed = value.trim();
        if (!AMOUNT_PATTERN.matcher(trimmed).matches()) {
            throw ApiException.badRequest("INVALID_ARGUMENT",
                    field + " 必须为大于 0 的十进制字符串，最多 3 位小数: " + value);
        }
        BigDecimal amount = new BigDecimal(trimmed);
        if (amount.signum() <= 0) {
            throw ApiException.badRequest("INVALID_ARGUMENT", field + " 必须大于 0");
        }
        return amount;
    }

    static void requireKey(String field, String value) {
        if (value == null || !KEY_PATTERN.matcher(value).matches()) {
            throw ApiException.badRequest("INVALID_ARGUMENT",
                    field + " 不能为空，且仅允许字母数字及 . - _ :，最长 128 字符");
        }
    }

    /** 水量格式化为十进制字符串（去掉多余尾零）。 */
    static String fmt(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        if (stripped.signum() == 0) {
            return "0";
        }
        return stripped.toPlainString();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    private <T> T fromJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("响应反序列化失败", e);
        }
    }

    // ------------------------------------------------------------------
    // 有向渠网门禁：图查询与图修订
    // ------------------------------------------------------------------

    /** 查询渠网图：冻结节点全集、当前边全集（含各段已批准流量）与图版本。 */
    public CanalGraphResponse getCanalGraph(long windowId) {
        if (repository.findWindowById(windowId) == null) {
            throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
        }
        if (repository.findCanalGraph(windowId) == null) {
            throw ApiException.notFound("CANAL_GRAPH_NOT_FOUND", "窗口不是渠网窗口: " + windowId);
        }
        return toCanalGraphResponse(windowId);
    }

    /**
     * 渠网图修订：携带 expectedGraphVersion，版本不符 409；容量调低到已批准流量之下、
     * 删除仍有贡献的共享边均 409（不得破坏当前已批准集合）。与批准/取消/转让在同一窗口行锁内串行核验，
     * 成功后图版本 +1。requestId 幂等：同参重放首次结果，改参 409，失败不占键。
     */
    public CanalGraphResponse reviseCanalGraph(String requestId, long windowId, Long expectedGraphVersion,
                                               List<CanalEdgeSpec> updates, List<CanalEdgeSpec> add,
                                               List<CanalEdgeRef> remove, String actor) {
        requireKey("requestId", requestId);
        requireKey("X-Actor-Id", actor);
        if (expectedGraphVersion == null) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "expectedGraphVersion 不能为空");
        }
        List<ParsedEdge> parsedUpdates = parseRevisionEdges(updates == null ? List.of() : updates, "updates");
        List<ParsedEdge> parsedAdds = parseRevisionEdges(add == null ? List.of() : add, "add");
        List<CanalEdgeRef> removeList = remove == null ? List.of() : remove;
        Set<String> seenRemovals = new HashSet<>();
        for (CanalEdgeRef ref : removeList) {
            if (ref == null) {
                throw ApiException.badRequest("CANAL_EDGE_INVALID", "remove 渠段不能为空");
            }
            requireKey("edge.from", ref.from());
            requireKey("edge.to", ref.to());
            if (!seenRemovals.add(ref.from() + ">" + ref.to())) {
                throw ApiException.badRequest("CANAL_EDGE_DUPLICATED",
                        "remove 内渠段重复: " + ref.from() + "->" + ref.to());
            }
        }
        String params = "CANAL_GRAPH_REVISE|" + windowId + "|v=" + expectedGraphVersion
                + "|U:" + canonicalEdges(parsedUpdates) + "|A:" + canonicalEdges(parsedAdds)
                + "|R:" + canonicalRefs(removeList) + "|" + actor;
        return runCommand("CANAL_GRAPH_REVISE", requestId, params, CanalGraphResponse.class, () -> {
            WindowRow window = repository.lockWindowById(windowId);
            if (window == null) {
                throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
            }
            CanalGraphRow graph = repository.findCanalGraph(windowId);
            if (graph == null) {
                throw ApiException.badRequest("CANAL_NOT_SUPPORTED", "普通窗口不适用渠网门禁");
            }
            if (graph.version() != expectedGraphVersion) {
                throw ApiException.conflict("GRAPH_VERSION_CONFLICT",
                        "期望图版本 " + expectedGraphVersion + " 与当前图版本 " + graph.version() + " 不一致");
            }
            for (ParsedEdge update : parsedUpdates) {
                CanalEdgeRow edge = repository.findCanalEdge(windowId, update.from(), update.to());
                if (edge == null) {
                    throw ApiException.notFound("EDGE_NOT_FOUND",
                            "渠段不存在: " + update.from() + "->" + update.to());
                }
                long used = repository.sumApprovedFlowOnEdge(windowId, update.from(), update.to());
                if (update.capacity().compareTo(BigDecimal.valueOf(used)) < 0) {
                    throw ApiException.conflict("CAPACITY_BELOW_APPROVED",
                            "渠段 " + update.from() + "->" + update.to() + " 新容量 " + fmt(update.capacity())
                                    + " 低于当前已批准流量 " + used);
                }
            }
            Set<String> nodes = new HashSet<>(repository.listCanalNodes(windowId));
            for (ParsedEdge addition : parsedAdds) {
                if (!nodes.contains(addition.from()) || !nodes.contains(addition.to())) {
                    throw ApiException.badRequest("CANAL_EDGE_NODE_UNKNOWN",
                            "渠段端点必须是已登记节点: " + addition.from() + "->" + addition.to());
                }
                if (repository.findCanalEdge(windowId, addition.from(), addition.to()) != null) {
                    throw ApiException.conflict("EDGE_EXISTS",
                            "渠段已存在: " + addition.from() + "->" + addition.to());
                }
            }
            List<CanalEdgeRow> removals = new ArrayList<>();
            for (CanalEdgeRef ref : removeList) {
                CanalEdgeRow edge = repository.findCanalEdge(windowId, ref.from(), ref.to());
                if (edge == null) {
                    throw ApiException.notFound("EDGE_NOT_FOUND", "渠段不存在: " + ref.from() + "->" + ref.to());
                }
                long used = repository.sumApprovedFlowOnEdge(windowId, ref.from(), ref.to());
                if (used > 0) {
                    throw ApiException.conflict("EDGE_IN_USE",
                            "共享边 " + ref.from() + "->" + ref.to() + " 仍有已批准流量贡献 " + used
                                    + "，不能从图中删去");
                }
                removals.add(edge);
            }
            int edgeCount = repository.listCanalEdges(windowId).size() + parsedAdds.size() - removals.size();
            if (edgeCount > MAX_CANAL_EDGES) {
                throw ApiException.badRequest("CANAL_EDGE_LIMIT", "渠网渠段数最多 " + MAX_CANAL_EDGES);
            }
            for (ParsedEdge update : parsedUpdates) {
                CanalEdgeRow edge = repository.findCanalEdge(windowId, update.from(), update.to());
                repository.updateCanalEdgeCapacity(edge.id(), update.capacity());
            }
            for (ParsedEdge addition : parsedAdds) {
                repository.insertCanalEdge(windowId, addition.from(), addition.to(), addition.capacity());
            }
            for (CanalEdgeRow edge : removals) {
                repository.deleteCanalEdge(edge.id());
            }
            repository.updateCanalGraphVersion(windowId, graph.version() + 1, nowNanos());
            return toCanalGraphResponse(windowId);
        });
    }

    private CanalGraphResponse toCanalGraphResponse(long windowId) {
        CanalGraphRow graph = repository.findCanalGraph(windowId);
        List<CanalEdgeView> edges = repository.listCanalEdges(windowId).stream()
                .map(edge -> new CanalEdgeView(edge.fromNode(), edge.toNode(), fmt(edge.capacity()),
                        String.valueOf(
                                repository.sumApprovedFlowOnEdge(windowId, edge.fromNode(), edge.toNode()))))
                .toList();
        return new CanalGraphResponse(windowId, graph.version(), repository.listCanalNodes(windowId), edges);
    }

    // ------------------------------------------------------------------
    // 有向渠网门禁：形状校验与规范化
    // ------------------------------------------------------------------

    private record ParsedEdge(String from, String to, BigDecimal capacity) {
    }

    private record ParsedGraph(List<String> nodes, List<ParsedEdge> edges, String canonical) {
    }

    /** 校验渠网登记形状并生成规范化串（节点与渠段排序、容量按值），用于幂等参数比较。 */
    private ParsedGraph validateGraph(CanalGraphRequest request) {
        List<String> nodes = request.nodes() == null ? List.of() : request.nodes();
        List<CanalEdgeSpec> edgeSpecs = request.edges() == null ? List.of() : request.edges();
        if (nodes.isEmpty() || nodes.size() > MAX_CANAL_NODES) {
            throw ApiException.badRequest("CANAL_NODE_LIMIT", "渠网节点数须为 1 至 " + MAX_CANAL_NODES);
        }
        Set<String> nodeSet = new HashSet<>();
        for (String node : nodes) {
            requireKey("node", node);
            if (!nodeSet.add(node)) {
                throw ApiException.badRequest("CANAL_NODE_DUPLICATED", "渠网节点重复: " + node);
            }
        }
        if (edgeSpecs.size() > MAX_CANAL_EDGES) {
            throw ApiException.badRequest("CANAL_EDGE_LIMIT", "渠网渠段数最多 " + MAX_CANAL_EDGES);
        }
        List<ParsedEdge> edges = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (CanalEdgeSpec spec : edgeSpecs) {
            ParsedEdge edge = parseEdgeSpec(spec, nodeSet);
            if (!seen.add(edge.from() + ">" + edge.to())) {
                throw ApiException.badRequest("CANAL_EDGE_DUPLICATED",
                        "渠段重复: " + edge.from() + "->" + edge.to());
            }
            edges.add(edge);
        }
        List<String> sortedNodes = new ArrayList<>(nodes);
        sortedNodes.sort(null);
        return new ParsedGraph(nodes, edges,
                "N:" + String.join(",", sortedNodes) + ";E:" + canonicalEdges(edges));
    }

    private ParsedEdge parseEdgeSpec(CanalEdgeSpec spec, Set<String> nodeSet) {
        if (spec == null) {
            throw ApiException.badRequest("CANAL_EDGE_INVALID", "渠段不能为空");
        }
        requireKey("edge.from", spec.from());
        requireKey("edge.to", spec.to());
        if (spec.from().equals(spec.to())) {
            throw ApiException.badRequest("CANAL_EDGE_SELF_LOOP", "渠段起终点不得相同: " + spec.from());
        }
        if (!nodeSet.contains(spec.from()) || !nodeSet.contains(spec.to())) {
            throw ApiException.badRequest("CANAL_EDGE_NODE_UNKNOWN",
                    "渠段端点必须是已登记节点: " + spec.from() + "->" + spec.to());
        }
        return new ParsedEdge(spec.from(), spec.to(), parseAmount("capacity", spec.capacity()));
    }

    /** 解析图修订的渠段列表：校验键、自环与列表内重复，容量按十进制解析。 */
    private List<ParsedEdge> parseRevisionEdges(List<CanalEdgeSpec> specs, String field) {
        List<ParsedEdge> parsed = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (CanalEdgeSpec spec : specs) {
            if (spec == null) {
                throw ApiException.badRequest("CANAL_EDGE_INVALID", field + " 渠段不能为空");
            }
            requireKey("edge.from", spec.from());
            requireKey("edge.to", spec.to());
            if (spec.from().equals(spec.to())) {
                throw ApiException.badRequest("CANAL_EDGE_SELF_LOOP", "渠段起终点不得相同: " + spec.from());
            }
            if (!seen.add(spec.from() + ">" + spec.to())) {
                throw ApiException.badRequest("CANAL_EDGE_DUPLICATED",
                        field + " 内渠段重复: " + spec.from() + "->" + spec.to());
            }
            parsed.add(new ParsedEdge(spec.from(), spec.to(), parseAmount("capacity", spec.capacity())));
        }
        return parsed;
    }

    /** 渠段列表规范化串：按 (from,to) 排序，容量按值（十进制去尾零）。 */
    private static String canonicalEdges(List<ParsedEdge> edges) {
        return edges.stream().map(e -> e.from() + ">" + e.to() + ":" + fmt(e.capacity()))
                .sorted().reduce("", (a, b) -> a.isEmpty() ? b : a + "," + b);
    }

    /** 渠段引用列表规范化串：按 (from,to) 排序，文本原样。 */
    private static String canonicalRefs(List<CanalEdgeRef> refs) {
        return refs.stream().map(r -> r.from() + ">" + r.to())
                .sorted().reduce("", (a, b) -> a.isEmpty() ? b : a + "," + b);
    }

    /** 解析流量：正整数字符串，单位立方米每秒。 */
    static long parseFlow(String field, String value) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest("INVALID_ARGUMENT", field + " 不能为空");
        }
        String trimmed = value.trim();
        if (!FLOW_PATTERN.matcher(trimmed).matches()) {
            throw ApiException.badRequest("INVALID_ARGUMENT", field + " 必须为正整数字符串: " + value);
        }
        long flow = Long.parseLong(trimmed);
        if (flow <= 0) {
            throw ApiException.badRequest("INVALID_ARGUMENT", field + " 必须大于 0");
        }
        return flow;
    }

    /** 两条路径是否完全一致（有序逐段比较，不规范化成无序集合）。 */
    private static boolean samePath(List<PathSegmentRow> a, List<PathSegmentRow> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).fromNode().equals(b.get(i).fromNode())
                    || !a.get(i).toNode().equals(b.get(i).toNode())) {
                return false;
            }
        }
        return true;
    }
}
