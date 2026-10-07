package com.example.starter.water;

import com.example.starter.water.WaterRepository.AllocationRow;
import com.example.starter.water.WaterRepository.CommandRow;
import com.example.starter.water.WaterRepository.ContributionRow;
import com.example.starter.water.WaterRepository.CurtailmentRow;
import com.example.starter.water.WaterRepository.GraphEdgeRow;
import com.example.starter.water.WaterRepository.GraphRevisionRow;
import com.example.starter.water.WaterRepository.SegmentRow;
import com.example.starter.water.WaterRepository.TransferRow;
import com.example.starter.water.WaterRepository.WindowRow;
import com.example.starter.water.dto.Dtos.AllocationResponse;
import com.example.starter.water.dto.Dtos.CapacityResponse;
import com.example.starter.water.dto.Dtos.CurtailmentResponse;
import com.example.starter.water.dto.Dtos.EdgeSpec;
import com.example.starter.water.dto.Dtos.GraphResponse;
import com.example.starter.water.dto.Dtos.GraphSpec;
import com.example.starter.water.dto.Dtos.HistoryResponse;
import com.example.starter.water.dto.Dtos.TransferListResponse;
import com.example.starter.water.dto.Dtos.TransferResponse;
import com.example.starter.water.dto.Dtos.WindowResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 灌区配水业务服务。
 *
 * <p>幂等：每个写命令携带幂等键（图修订为 requestId），事务内先占位插入命令行再执行业务并写回响应；
 * 同键同参（结构化比较操作、资源、全参数及期望版本；十进制按值、文本原样）重放返回首次结果，
 * 同键改参返回 409，失败不占键，业务与去重原子提交。</p>
 *
 * <p>并发：批准、取消、转让、限供与图修订在同一事务内先对窗口行 SELECT ... FOR UPDATE，
 * 按事务提交顺序串行核验最终集合，保证已批准总量与各渠段已批准流量之和永不超限。</p>
 *
 * <p>渠网门禁：渠网窗口的申请必须携带 1 至 8 条首尾相接的有序渠段与正整数流量；
 * 批准时要求原额度足够、每段已批准流量之和不超容量、所有已批准路径的边并集无有向环；
 * 图修订携带 expectedGraphVersion，不得破坏当前已批准集合；取消释放该申请贡献。
 * 普通窗口不适用此门禁。</p>
 */
@Service
public class WaterService {

    static final String STATUS_REQUESTED = "REQUESTED";
    static final String STATUS_APPROVED = "APPROVED";
    static final String STATUS_CANCELLED = "CANCELLED";

    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final Pattern AMOUNT_PATTERN = Pattern.compile("\\d{1,16}(\\.\\d{1,3})?");
    private static final Pattern KEY_PATTERN = Pattern.compile("[\\w.\\-:]{1,128}");
    private static final Pattern NODE_PATTERN = Pattern.compile("[\\w.\\-:]{1,64}");
    private static final int MAX_GRAPH_NODES = 10;
    private static final int MAX_GRAPH_EDGES = 20;
    private static final int MAX_PATH_SEGMENTS = 8;

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

    /** 创建供水窗口；graph 非空时登记渠网（最多 10 节点、20 条带容量有向渠段），窗口成为渠网门禁窗口。 */
    public WindowResponse createWindow(String commandKey, String windowKey, String channelId,
                                       String startUtc, String endUtc, String plannedVolume, GraphSpec graph) {
        requireKey("commandKey", commandKey);
        requireKey("windowKey", windowKey);
        requireKey("channelId", channelId);
        long startNanos = parseInstant("startUtc", startUtc);
        long endNanos = parseInstant("endUtc", endUtc);
        if (startNanos >= endNanos) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "startUtc 必须早于 endUtc");
        }
        BigDecimal planned = parseAmount("plannedVolume", plannedVolume);
        GraphSpec normalized = graph == null ? null : validateGraphSpec(graph);
        String params = "WINDOW_CREATE|" + windowKey + "|" + channelId + "|" + startNanos + "|" + endNanos
                + "|" + fmt(planned) + "|" + graphParams(normalized);
        return runCommand("WINDOW_CREATE", commandKey, params, WindowResponse.class, () -> {
            if (repository.existsOverlappingWindow(channelId, startNanos, endNanos)) {
                throw ApiException.conflict("WINDOW_OVERLAP", "同一渠道存在时间重叠的供水窗口");
            }
            long now = nowNanos();
            long id = repository.insertWindow(windowKey, channelId, startNanos, endNanos, planned,
                    normalized == null ? null : 1, now);
            if (normalized != null) {
                repository.insertGraphRevision(id, 1, nodesJson(normalized.nodes()), null, now);
                for (EdgeSpec edge : normalized.edges()) {
                    repository.insertGraphEdge(id, edge.from(), edge.to(), edge.capacity(), 1);
                }
            }
            WindowRow row = repository.findWindowById(id);
            return toWindowResponse(row, null);
        });
    }

    /** 提交配水申请，申请人为 actor；渠网窗口必须携带有序路径与正整数流量，普通窗口不得携带。 */
    public AllocationResponse submitAllocation(String commandKey, String allocationKey, Long windowId,
                                               String userId, String amount, List<String> path, Long flow,
                                               String actor) {
        requireKey("commandKey", commandKey);
        requireKey("allocationKey", allocationKey);
        requireKey("userId", userId);
        requireKey("X-Actor-Id", actor);
        if (windowId == null) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "windowId 不能为空");
        }
        BigDecimal qty = parseAmount("amount", amount);
        String params = "ALLOCATION_SUBMIT|" + allocationKey + "|" + windowId + "|" + userId + "|"
                + fmt(qty) + "|" + actor + "|" + flow + "|" + (path == null ? "-" : String.join(">", path));
        return runCommand("ALLOCATION_SUBMIT", commandKey, params, AllocationResponse.class, () -> {
            WindowRow window = repository.lockWindowById(windowId);
            if (window == null) {
                throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
            }
            boolean graphWindow = window.graphVersion() != null;
            if (!graphWindow && (path != null || flow != null)) {
                throw ApiException.badRequest("INVALID_ARGUMENT", "普通窗口的申请不得携带渠网路径与流量");
            }
            List<String> nodes = null;
            if (graphWindow) {
                nodes = validatePath(window.id(), path, flow);
            }
            long id = repository.insertAllocation(allocationKey, windowId, userId, qty, flow, actor, nowNanos());
            if (nodes != null) {
                for (int i = 0; i + 1 < nodes.size(); i++) {
                    repository.insertSegment(id, i, nodes.get(i), nodes.get(i + 1));
                }
            }
            return toAllocationResponse(repository.findAllocationByKey(allocationKey));
        });
    }

    /**
     * 批准申请：加入本申请后已批准总量不得超过当前可用总量（422）；
     * 渠网窗口还要求每段已批准流量之和不超容量（422）、所有已批准路径的边并集无有向环（409），
     * 失败不占任何新额度与流量。
     */
    public AllocationResponse approveAllocation(String commandKey, String allocationKey) {
        requireKey("commandKey", commandKey);
        requireKey("allocationKey", allocationKey);
        String params = "ALLOCATION_APPROVE|" + allocationKey;
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
            if (window.graphVersion() != null) {
                assertGraphApprovable(window.id(), allocation);
            }
            repository.updateAllocationStatus(allocation.id(), STATUS_APPROVED, nowNanos());
            return toAllocationResponse(repository.findAllocationByKey(allocationKey));
        });
    }

    /** 取消申请：仅申请人本人可取消 REQUESTED/APPROVED，取消不可恢复并立即释放水量与流量贡献。 */
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
     * 渠网窗口额外要求双方路径与流量完全一致（不改路径；涉及新路径须取消后重新申请），
     * 并在事务内复核各渠段已批准流量之和仍不超容量。
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
            // 先锁窗口行，与批准、取消、限供、图修订按事务提交顺序串行裁决
            WindowRow window = lockWindowOf(source);
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
            if (window.graphVersion() != null) {
                assertSamePath(lockedSource, lockedTarget);
            }
            BigDecimal amount = lockedTarget.amount();
            if (lockedSource.heldAmount().compareTo(amount) < 0) {
                throw ApiException.quotaExceeded("源申请当前持有额度 " + fmt(lockedSource.heldAmount())
                        + " 不足，无法转让 " + fmt(amount));
            }
            long now = nowNanos();
            repository.decrementHeldAmount(lockedSource.id(), amount, now);
            repository.updateAllocationStatus(lockedTarget.id(), STATUS_APPROVED, now);
            if (window.graphVersion() != null) {
                // 部分转让会让源与目标同时贡献流量，复核各渠段容量，超限则整体回滚
                assertEdgeCapacities(window.id());
            }
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
        String params = "CURTAILMENT_CREATE|" + windowId + "|" + fmt(qty);
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

    /** 查询窗口历史明细：窗口 + 全部申请（含冻结路径）+ 全部限供；渠网窗口附带冻结的当前边全集与图版本。 */
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
        GraphResponse graph = window.graphVersion() == null ? null
                : toGraphResponse(window.id(), window.graphVersion());
        return new HistoryResponse(toWindowResponse(window, active), allocations, curtailments, graph);
    }

    /** 查询窗口当前渠网图（版本、节点全集、当前有效渠段全集）。 */
    public GraphResponse getGraph(long windowId) {
        WindowRow window = repository.findWindowById(windowId);
        if (window == null) {
            throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
        }
        if (window.graphVersion() == null) {
            throw ApiException.notFound("GRAPH_NOT_FOUND", "普通窗口不适用渠网门禁: " + windowId);
        }
        return toGraphResponse(window.id(), window.graphVersion());
    }

    /**
     * 修订渠网图（容量或边全集）：携带 expectedGraphVersion，不等于当前版本返回 409；
     * 仍有已批准流量贡献的渠段不得删去、容量不得降至已批准流量之和以下，否则 409；
     * 与批准、取消、转让在同一窗口行锁下串行核验最终集合。修订历史逐版冻结，不因取消改变。
     */
    public GraphResponse reviseGraph(String requestId, long windowId, Long expectedGraphVersion,
                                     GraphSpec graph, String actor) {
        requireKey("requestId", requestId);
        requireKey("X-Actor-Id", actor);
        if (expectedGraphVersion == null) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "expectedGraphVersion 不能为空");
        }
        if (graph == null) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "graph 不能为空");
        }
        GraphSpec normalized = validateGraphSpec(graph);
        String params = "GRAPH_REVISE|" + windowId + "|" + expectedGraphVersion + "|" + actor + "|"
                + graphParams(normalized);
        return runCommand("GRAPH_REVISE", requestId, params, GraphResponse.class, () -> {
            WindowRow window = repository.lockWindowById(windowId);
            if (window == null) {
                throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
            }
            if (window.graphVersion() == null) {
                throw ApiException.conflict("GRAPH_NOT_APPLICABLE", "普通窗口不适用渠网门禁: " + windowId);
            }
            if (expectedGraphVersion != window.graphVersion().longValue()) {
                throw ApiException.conflict("GRAPH_VERSION_CONFLICT",
                        "期望图版本 " + expectedGraphVersion + " 与当前版本 " + window.graphVersion() + " 不一致");
            }
            List<GraphEdgeRow> current = repository.listCurrentEdges(windowId);
            Map<String, Long> sums = contributionSums(windowId);
            Map<String, EdgeSpec> newEdges = new HashMap<>();
            for (EdgeSpec edge : normalized.edges()) {
                newEdges.put(edgeKey(edge.from(), edge.to()), edge);
            }
            for (GraphEdgeRow edge : current) {
                long sum = sums.getOrDefault(edgeKey(edge.fromNode(), edge.toNode()), 0L);
                EdgeSpec replacement = newEdges.get(edgeKey(edge.fromNode(), edge.toNode()));
                if (sum > 0 && replacement == null) {
                    throw ApiException.conflict("GRAPH_REVISION_BREAKS_APPROVED",
                            "渠段 " + edge.fromNode() + "->" + edge.toNode()
                                    + " 仍有已批准流量贡献 " + sum + "，不能从图中删去");
                }
                if (replacement != null && replacement.capacity() < sum) {
                    throw ApiException.conflict("GRAPH_REVISION_BREAKS_APPROVED",
                            "渠段 " + edge.fromNode() + "->" + edge.toNode() + " 新容量 "
                                    + replacement.capacity() + " 低于当前已批准流量之和 " + sum);
                }
            }
            int newVersion = window.graphVersion() + 1;
            Map<String, GraphEdgeRow> currentByKey = new HashMap<>();
            for (GraphEdgeRow edge : current) {
                currentByKey.put(edgeKey(edge.fromNode(), edge.toNode()), edge);
            }
            for (GraphEdgeRow edge : current) {
                EdgeSpec replacement = newEdges.get(edgeKey(edge.fromNode(), edge.toNode()));
                if (replacement == null || replacement.capacity() != edge.capacity()) {
                    repository.removeGraphEdge(edge.id(), newVersion);
                }
            }
            for (EdgeSpec edge : normalized.edges()) {
                GraphEdgeRow existing = currentByKey.get(edgeKey(edge.from(), edge.to()));
                if (existing == null || existing.capacity() != edge.capacity()) {
                    repository.insertGraphEdge(windowId, edge.from(), edge.to(), edge.capacity(), newVersion);
                }
            }
            repository.insertGraphRevision(windowId, newVersion, nodesJson(normalized.nodes()), actor,
                    nowNanos());
            repository.updateWindowGraphVersion(windowId, newVersion);
            return toGraphResponse(windowId, newVersion);
        });
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
                throw ApiException.conflict("COMMAND_CONFLICT", "相同幂等键的命令正在处理，请重试");
            }
            return replay(committed, operation, params, type);
        }
    }

    private <T> T replay(CommandRow existing, String operation, String params, Class<T> type) {
        if (!existing.operation().equals(operation) || !existing.params().equals(params)) {
            throw ApiException.conflict("COMMAND_KEY_REUSED", "相同幂等键但参数不同，拒绝重放");
        }
        return fromJson(existing.response(), type);
    }

    // ------------------------------------------------------------------
    // 渠网门禁
    // ------------------------------------------------------------------

    /** 校验渠网图规格：1 至 10 个节点、1 至 20 条带正整数容量的有向渠段，端点必须已登记，不得自环或重复。 */
    private GraphSpec validateGraphSpec(GraphSpec spec) {
        List<String> nodes = spec.nodes();
        if (nodes == null || nodes.isEmpty() || nodes.size() > MAX_GRAPH_NODES) {
            throw ApiException.badRequest("INVALID_ARGUMENT",
                    "渠网节点数必须为 1 至 " + MAX_GRAPH_NODES);
        }
        Set<String> uniqueNodes = new HashSet<>();
        for (String node : nodes) {
            if (node == null || !NODE_PATTERN.matcher(node).matches()) {
                throw ApiException.badRequest("INVALID_ARGUMENT",
                        "渠网节点名不能为空，且仅允许字母数字及 . - _ :，最长 64 字符");
            }
            if (!uniqueNodes.add(node)) {
                throw ApiException.badRequest("INVALID_ARGUMENT", "渠网节点不得重复: " + node);
            }
        }
        List<EdgeSpec> edges = spec.edges();
        if (edges == null || edges.isEmpty() || edges.size() > MAX_GRAPH_EDGES) {
            throw ApiException.badRequest("INVALID_ARGUMENT",
                    "渠网渠段数必须为 1 至 " + MAX_GRAPH_EDGES);
        }
        Set<String> seen = new HashSet<>();
        for (EdgeSpec edge : edges) {
            if (edge == null || edge.from() == null || edge.to() == null) {
                throw ApiException.badRequest("INVALID_ARGUMENT", "渠段端点不能为空");
            }
            if (!uniqueNodes.contains(edge.from()) || !uniqueNodes.contains(edge.to())) {
                throw ApiException.badRequest("INVALID_ARGUMENT",
                        "渠段端点必须是已登记节点: " + edge.from() + "->" + edge.to());
            }
            if (edge.from().equals(edge.to())) {
                throw ApiException.badRequest("INVALID_ARGUMENT", "渠段不得自环: " + edge.from());
            }
            if (edge.capacity() == null || edge.capacity() < 1) {
                throw ApiException.badRequest("INVALID_ARGUMENT",
                        "渠段容量必须为正整数（立方米每秒）: " + edge.from() + "->" + edge.to());
            }
            if (!seen.add(edgeKey(edge.from(), edge.to()))) {
                throw ApiException.badRequest("INVALID_ARGUMENT",
                        "渠段不得重复: " + edge.from() + "->" + edge.to());
            }
        }
        return spec;
    }

    /**
     * 校验申请路径：1 至 8 条首尾相接的有序渠段（2 至 9 个节点），节点不得重复（400），
     * 相邻节点间必须存在当前有效渠段，路径断开返回 400。流量必须为正整数。
     */
    private List<String> validatePath(long windowId, List<String> path, Long flow) {
        if (flow == null || flow < 1) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "flow 必须为正整数（立方米每秒）");
        }
        if (path == null || path.size() < 2 || path.size() > MAX_PATH_SEGMENTS + 1) {
            throw ApiException.badRequest("INVALID_ARGUMENT",
                    "path 必须为 1 至 " + MAX_PATH_SEGMENTS + " 条首尾相接的有序渠段（2 至 "
                            + (MAX_PATH_SEGMENTS + 1) + " 个节点）");
        }
        Set<String> seen = new HashSet<>();
        for (String node : path) {
            if (node == null || !NODE_PATTERN.matcher(node).matches()) {
                throw ApiException.badRequest("INVALID_ARGUMENT",
                        "路径节点名不能为空，且仅允许字母数字及 . - _ :，最长 64 字符");
            }
            if (!seen.add(node)) {
                throw ApiException.badRequest("PATH_NODE_REPEATED", "路径节点不得重复: " + node);
            }
        }
        Set<String> edges = new HashSet<>();
        for (GraphEdgeRow edge : repository.listCurrentEdges(windowId)) {
            edges.add(edgeKey(edge.fromNode(), edge.toNode()));
        }
        for (int i = 0; i + 1 < path.size(); i++) {
            if (!edges.contains(edgeKey(path.get(i), path.get(i + 1)))) {
                throw ApiException.badRequest("PATH_BROKEN",
                        "路径断开：渠段 " + path.get(i) + "->" + path.get(i + 1) + " 不在当前渠网中");
            }
        }
        return path;
    }

    /**
     * 批准时的渠网门禁核验（窗口行锁内）：路径渠段须仍在当前图中（409），
     * 每段已批准流量之和加本申请流量不超容量（422），
     * 所有已批准路径的边并集加入本路径后无有向环（409）。
     */
    private void assertGraphApprovable(long windowId, AllocationRow allocation) {
        List<SegmentRow> segments = repository.listSegments(allocation.id());
        List<GraphEdgeRow> currentEdges = repository.listCurrentEdges(windowId);
        Map<String, Long> capacities = new HashMap<>();
        for (GraphEdgeRow edge : currentEdges) {
            capacities.put(edgeKey(edge.fromNode(), edge.toNode()), edge.capacity());
        }
        Map<String, Long> sums = contributionSums(windowId);
        long flow = allocation.flowRate();
        List<String[]> union = new ArrayList<>();
        for (ContributionRow c : repository.listContributions(windowId)) {
            union.add(new String[]{c.fromNode(), c.toNode()});
        }
        for (SegmentRow segment : segments) {
            String key = edgeKey(segment.fromNode(), segment.toNode());
            Long capacity = capacities.get(key);
            if (capacity == null) {
                throw ApiException.conflict("GRAPH_EDGE_REMOVED",
                        "路径渠段 " + segment.fromNode() + "->" + segment.toNode()
                                + " 已不在当前渠网中，须取消后重新申请");
            }
            long used = sums.getOrDefault(key, 0L);
            if (used + flow > capacity) {
                throw ApiException.edgeCapacityExceeded(
                        "渠段 " + segment.fromNode() + "->" + segment.toNode() + " 已批准流量之和 "
                                + used + " 加入本申请流量 " + flow + " 后将超过容量 " + capacity);
            }
            union.add(new String[]{segment.fromNode(), segment.toNode()});
        }
        assertAcyclic(union);
    }

    /** 复核窗口各渠段已批准流量之和仍不超容量（转让后调用，超限抛 422 整体回滚）。 */
    private void assertEdgeCapacities(long windowId) {
        Map<String, Long> sums = contributionSums(windowId);
        for (GraphEdgeRow edge : repository.listCurrentEdges(windowId)) {
            long sum = sums.getOrDefault(edgeKey(edge.fromNode(), edge.toNode()), 0L);
            if (sum > edge.capacity()) {
                throw ApiException.edgeCapacityExceeded(
                        "渠段 " + edge.fromNode() + "->" + edge.toNode() + " 已批准流量之和 "
                                + sum + " 将超过容量 " + edge.capacity());
            }
        }
    }

    /** 渠网窗口内转让要求双方路径与流量完全一致（转让不改路径）。 */
    private void assertSamePath(AllocationRow source, AllocationRow target) {
        if (!source.flowRate().equals(target.flowRate())) {
            throw ApiException.conflict("TRANSFER_PATH_MISMATCH",
                    "渠网窗口内转让双方流量必须一致（转让不改路径）");
        }
        List<SegmentRow> sourceSegments = repository.listSegments(source.id());
        List<SegmentRow> targetSegments = repository.listSegments(target.id());
        if (sourceSegments.size() != targetSegments.size()) {
            throw ApiException.conflict("TRANSFER_PATH_MISMATCH",
                    "渠网窗口内转让双方路径必须一致（转让不改路径）");
        }
        for (int i = 0; i < sourceSegments.size(); i++) {
            SegmentRow s = sourceSegments.get(i);
            SegmentRow t = targetSegments.get(i);
            if (!s.fromNode().equals(t.fromNode()) || !s.toNode().equals(t.toNode())) {
                throw ApiException.conflict("TRANSFER_PATH_MISMATCH",
                        "渠网窗口内转让双方路径必须一致（转让不改路径）");
            }
        }
    }

    /** 窗口各渠段当前已批准流量之和（APPROVED 且仍持有额度的申请的贡献）。 */
    private Map<String, Long> contributionSums(long windowId) {
        Map<String, Long> sums = new HashMap<>();
        for (ContributionRow c : repository.listContributions(windowId)) {
            sums.merge(edgeKey(c.fromNode(), c.toNode()), c.flowRate(), Long::sum);
        }
        return sums;
    }

    /** Kahn 拓扑排序校验有向边并集无环，有环抛 409。 */
    private void assertAcyclic(List<String[]> edges) {
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> adjacency = new HashMap<>();
        for (String[] edge : edges) {
            adjacency.computeIfAbsent(edge[0], k -> new ArrayList<>()).add(edge[1]);
            indegree.merge(edge[1], 1, Integer::sum);
            indegree.putIfAbsent(edge[0], 0);
        }
        Deque<String> queue = new ArrayDeque<>();
        for (Map.Entry<String, Integer> entry : indegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.add(entry.getKey());
            }
        }
        int visited = 0;
        while (!queue.isEmpty()) {
            String node = queue.poll();
            visited++;
            for (String next : adjacency.getOrDefault(node, List.of())) {
                if (indegree.merge(next, -1, Integer::sum) == 0) {
                    queue.add(next);
                }
            }
        }
        if (visited != indegree.size()) {
            throw ApiException.conflict("PATH_CYCLE",
                    "加入该路径后，所有已批准路径的边并集将形成有向环");
        }
    }

    private static String edgeKey(String from, String to) {
        return from + ">" + to;
    }

    /** 图规格的规范化参数串：节点与渠段按给定顺序原样拼接（路径/规格顺序不可规范化为无序集合）。 */
    private static String graphParams(GraphSpec spec) {
        if (spec == null) {
            return "-";
        }
        StringBuilder sb = new StringBuilder("nodes=").append(String.join(",", spec.nodes())).append(";edges=");
        for (int i = 0; i < spec.edges().size(); i++) {
            EdgeSpec edge = spec.edges().get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append(edge.from()).append('>').append(edge.to()).append('=').append(edge.capacity());
        }
        return sb.toString();
    }

    private String nodesJson(List<String> nodes) {
        try {
            return objectMapper.writeValueAsString(nodes);
        } catch (Exception e) {
            throw new IllegalStateException("节点序列化失败", e);
        }
    }

    private GraphResponse toGraphResponse(long windowId, int version) {
        GraphRevisionRow revision = repository.findGraphRevision(windowId, version);
        List<String> nodes;
        try {
            nodes = objectMapper.readValue(revision.nodesJson(), new TypeReference<>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("节点反序列化失败", e);
        }
        List<EdgeSpec> edges = repository.listCurrentEdges(windowId).stream()
                .map(edge -> new EdgeSpec(edge.fromNode(), edge.toNode(), edge.capacity()))
                .toList();
        return new GraphResponse(windowId, version, nodes, edges);
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
        return new WindowResponse(row.id(), row.windowKey(), row.channelId(), toIso(row.startNanos()),
                toIso(row.endNanos()), fmt(row.plannedVolume()),
                active != null ? fmt(active.volume()) : null, fmt(available), row.graphVersion(),
                toIso(row.createdNanos()));
    }

    private AllocationResponse toAllocationResponse(AllocationRow row) {
        List<String> path = null;
        if (row.flowRate() != null) {
            List<SegmentRow> segments = repository.listSegments(row.id());
            path = new ArrayList<>();
            if (!segments.isEmpty()) {
                path.add(segments.get(0).fromNode());
                for (SegmentRow segment : segments) {
                    path.add(segment.toNode());
                }
            }
        }
        return new AllocationResponse(row.allocationKey(), row.windowId(), row.userId(), fmt(row.amount()),
                fmt(row.heldAmount()), row.requester(), row.status(), row.flowRate(), path,
                toIso(row.createdNanos()), toIso(row.updatedNanos()));
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

    /** 水量格式化为十进制字符串（去掉多余尾零），幂等参数中十进制按值比较。 */
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
}
