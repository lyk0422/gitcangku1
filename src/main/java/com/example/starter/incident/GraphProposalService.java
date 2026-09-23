package com.example.starter.incident;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import com.example.starter.incident.DependencyGraphRepository.GraphEdge;
import com.example.starter.incident.dto.Requests.GraphActivateRequest;
import com.example.starter.incident.dto.Requests.GraphProposalCreateRequest;
import com.example.starter.incident.dto.Requests.GraphProposalEdgeRequest;
import com.example.starter.incident.dto.Requests.GraphVoteRequest;
import com.example.starter.incident.dto.Responses.GraphEdgeView;
import com.example.starter.incident.dto.Responses.GraphSnapshotView;
import com.example.starter.incident.dto.Responses.GraphView;
import com.example.starter.incident.dto.Responses.ProposalEdgeView;
import com.example.starter.incident.dto.Responses.ProposalView;
import com.example.starter.incident.dto.Responses.RosterSeatView;
import com.example.starter.incident.dto.Responses.VoteView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 依赖图变更提案服务：提案创建（冻结名册）、法定人数票决与原子激活。
 * 并发约定：投票/激活先 SELECT ... FOR UPDATE 锁定提案行；激活另持有 task_graph_lock
 * 全局图锁（与任务创建共用），在同一事务内重读完整依赖图、校验版本与后态、应用边集
 * 变更并生成唯一新图版本；投票、交接、任务完成与其他图变更按事务提交顺序收敛。
 * 幂等约定：requestId 全局唯一，同键同参（边换序视为同参）重放首次响应，异参 409，
 * 失败不占键；proposalKey 全局唯一。
 */
@Service
public class GraphProposalService {

    private static final String SEP = "";

    /** 每提案边数上限（结构化去重后）。 */
    private static final int MAX_EDGES_PER_PROPOSAL = 50;

    /** 视为阻塞已解除（前置依赖已满足）的目标事件状态。 */
    private static final Set<IncidentStatus> UNBLOCKING_STATUSES = EnumSet.of(
            IncidentStatus.CONTAINED, IncidentStatus.RESOLVED, IncidentStatus.CLOSED);

    private final GraphProposalRepository proposals;
    private final DependencyGraphRepository graph;
    private final IncidentRepository incidents;
    private final IncidentTaskRepository tasks;
    private final CommandKeyRepository commandKeys;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public GraphProposalService(GraphProposalRepository proposals, DependencyGraphRepository graph,
                                IncidentRepository incidents, IncidentTaskRepository tasks,
                                CommandKeyRepository commandKeys, ObjectMapper objectMapper,
                                Clock clock) {
        this.proposals = proposals;
        this.graph = graph;
        this.incidents = incidents;
        this.tasks = tasks;
        this.commandKeys = commandKeys;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    private Instant now() {
        return clock.instant();
    }

    /** 结构化去重后的提案边（事件业务键形式）。 */
    private record NormalizedEdge(EdgeOperation operation, String fromKey, String toKey) {
    }

    /**
     * 创建提案：校验边集（1~50 条、结构化去重、换序等价），冻结受影响事件、
     * 各事件当时指挥官与安全审核员为不可变名册；图状态相关校验（版本匹配、
     * 成环、关闭事件、已完成任务前置、强制边）在激活时以后态为准。
     */
    @Transactional
    public ProposalView createProposal(String actor, GraphProposalCreateRequest req) {
        String requestId = requireText(req.requestId(), "requestId");
        String proposalKey = requireText(req.proposalKey(), "proposalKey");
        String rationale = requireText(req.rationale(), "rationale");
        String safetyReviewer = requireText(req.safetyReviewer(), "safetyReviewer");
        String proposer = requireText(actor, "X-Actor-Id");
        if (req.expectedGraphVersion() == null || req.expectedGraphVersion() < 0) {
            throw ApiException.badRequest("expectedGraphVersion 不能为空且必须为非负整数");
        }
        long expectedVersion = req.expectedGraphVersion();
        List<NormalizedEdge> edges = normalizeEdges(req.edges());
        return runIdempotent(requestId, "graph_propose",
                hash(proposer, proposalKey, String.valueOf(expectedVersion), rationale,
                        safetyReviewer, edgesHash(edges)),
                ProposalView.class,
                () -> doCreate(proposalKey, rationale, proposer, safetyReviewer,
                        expectedVersion, edges));
    }

    private ProposalView doCreate(String proposalKey, String rationale, String proposer,
                                  String safetyReviewer, long expectedVersion,
                                  List<NormalizedEdge> edges) {
        if (proposals.findByKey(proposalKey).isPresent()) {
            throw ApiException.conflict("proposalKey 已存在: " + proposalKey);
        }
        // 解析全部受影响事件（边两端），不存在即 404
        Map<String, Incident> byKey = new HashMap<>();
        for (NormalizedEdge edge : edges) {
            resolveIncident(byKey, edge.fromKey());
            resolveIncident(byKey, edge.toKey());
        }
        long currentVersion = graph.currentVersion();
        if (currentVersion != expectedVersion) {
            throw ApiException.conflict("expectedGraphVersion 与当前图版本不匹配: 期望 "
                    + expectedVersion + "，当前 " + currentVersion);
        }
        // 冻结受影响事件（按事件键稳定排序），要求当时均有指挥官以形成名册
        List<Incident> affected = byKey.values().stream()
                .sorted(Comparator.comparing(Incident::incidentKey))
                .toList();
        for (Incident incident : affected) {
            if (incident.commander() == null) {
                throw ApiException.conflict("受影响事件 " + incident.incidentKey()
                        + " 尚无指挥官，无法冻结投票名册");
            }
        }
        Instant now = now();
        long proposalId;
        try {
            proposalId = proposals.insertProposal(new GraphProposal(0L, proposalKey,
                    GraphProposalStatus.PENDING, rationale, proposer, safetyReviewer,
                    expectedVersion, null, now, now));
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("proposalKey 已存在: " + proposalKey);
        }
        for (NormalizedEdge edge : edges) {
            proposals.insertEdge(new GraphProposalEdge(proposalId, edge.operation(),
                    byKey.get(edge.fromKey()).id(), byKey.get(edge.toKey()).id()));
        }
        for (Incident incident : affected) {
            proposals.insertRosterSeat(new GraphRosterSeat(proposalId, RosterRole.COMMANDER,
                    incident.id(), incident.commander()));
        }
        proposals.insertRosterSeat(new GraphRosterSeat(proposalId, RosterRole.SAFETY_REVIEWER,
                GraphRosterSeat.REVIEWER_INCIDENT_ID, safetyReviewer));
        return toView(proposals.findByKey(proposalKey).orElseThrow());
    }

    /**
     * 投票：仅名册成员可投，每人仅首票有效；任一反对即整案 REJECTED；
     * 全部席位赞成后进入 APPROVED。兼任多席位的人员只投一票，同时满足其全部席位。
     */
    @Transactional
    public ProposalView vote(String proposalKey, String actor, GraphVoteRequest req) {
        String requestId = requireText(req.requestId(), "requestId");
        String voter = requireText(actor, "X-Actor-Id");
        VoteDecision decision = parseDecision(req.decision());
        return runIdempotent(requestId, "graph_vote", hash(proposalKey, voter, decision.name()),
                ProposalView.class, () -> {
                    GraphProposal proposal = lockProposal(proposalKey);
                    if (proposal.status() != GraphProposalStatus.PENDING) {
                        throw ApiException.conflict(
                                "提案状态为 " + proposal.status() + "，不能投票");
                    }
                    List<GraphRosterSeat> roster = proposals.listRoster(proposal.id());
                    boolean inRoster = roster.stream().anyMatch(s -> s.person().equals(voter));
                    if (!inRoster) {
                        throw ApiException.conflict("投票人 " + voter + " 不在提案名册中，不能代替投票");
                    }
                    boolean alreadyVoted = proposals.listVotes(proposal.id()).stream()
                            .anyMatch(v -> v.person().equals(voter));
                    if (alreadyVoted) {
                        throw ApiException.conflict("投票人 " + voter + " 已投过票，仅首票有效");
                    }
                    Instant now = now();
                    try {
                        proposals.insertVote(new GraphVote(proposal.id(), voter, decision, now));
                    } catch (DuplicateKeyException e) {
                        throw ApiException.conflict("投票人 " + voter + " 已投过票，仅首票有效");
                    }
                    if (decision == VoteDecision.REJECT) {
                        proposals.updateStatus(proposal.id(), GraphProposalStatus.REJECTED, now);
                    } else if (proposals.listUnsatisfiedSeats(proposal.id()).isEmpty()) {
                        proposals.updateStatus(proposal.id(), GraphProposalStatus.APPROVED, now);
                    }
                    return toView(proposals.findByKey(proposalKey).orElseThrow());
                });
    }

    /**
     * 激活：仅 APPROVED 提案可激活，且操作人须为名册成员。同一事务内持有全局图锁，
     * 重读完整依赖图并要求 graphVersion 仍匹配；将全部增删边作为一个后态验证：
     * 不得成环、不得引用关闭事件、不得让已完成任务新增未满足前置依赖、
     * 不得删除仍被进行中任务声明为强制的边。任一失败整案 409/422 且不改图；
     * 成功只生成一个新 graphVersion 并保存前后边集快照。
     */
    @Transactional
    public ProposalView activate(String proposalKey, String actor, GraphActivateRequest req) {
        String requestId = requireText(req.requestId(), "requestId");
        String operator = requireText(actor, "X-Actor-Id");
        return runIdempotent(requestId, "graph_activate", hash(proposalKey, operator),
                ProposalView.class, () -> {
                    GraphProposal proposal = lockProposal(proposalKey);
                    boolean inRoster = proposals.listRoster(proposal.id()).stream()
                            .anyMatch(s -> s.person().equals(operator));
                    if (!inRoster) {
                        throw ApiException.conflict("操作人 " + operator + " 不在提案名册中，不能激活");
                    }
                    if (proposal.status() != GraphProposalStatus.APPROVED) {
                        throw ApiException.conflict("提案状态为 " + proposal.status()
                                + "，仅 APPROVED 可激活");
                    }
                    return doActivate(proposal);
                });
    }

    private ProposalView doActivate(GraphProposal proposal) {
        Instant now = now();
        // 与任务创建共用全局图锁：串行化图读取、校验与边集写入
        tasks.lockGraph();
        graph.ensureVersionRow(now);
        long currentVersion = graph.currentVersion();
        if (currentVersion != proposal.expectedGraphVersion()) {
            throw ApiException.conflict("graphVersion 已变更: 提案基于 "
                    + proposal.expectedGraphVersion() + "，当前 " + currentVersion);
        }
        List<GraphEdge> currentEdges = graph.listEdges();
        Set<GraphEdge> current = new LinkedHashSet<>(currentEdges);
        List<GraphProposalEdge> changes = proposals.listEdges(proposal.id());
        List<GraphProposalEdge> removals = changes.stream()
                .filter(e -> e.operation() == EdgeOperation.REMOVE).toList();
        List<GraphProposalEdge> additions = changes.stream()
                .filter(e -> e.operation() == EdgeOperation.ADD).toList();

        Map<Long, Incident> byId = new HashMap<>();
        for (GraphProposalEdge edge : changes) {
            resolveIncidentById(byId, edge.fromIncidentId());
            resolveIncidentById(byId, edge.toIncidentId());
        }
        // 删除边校验：必须现存，且不得仍被进行中任务声明为强制
        for (GraphProposalEdge edge : removals) {
            GraphEdge key = new GraphEdge(edge.fromIncidentId(), edge.toIncidentId());
            if (!current.contains(key)) {
                throw ApiException.conflict("要删除的边不存在于当前依赖图: "
                        + edgeLabel(byId, edge));
            }
            if (tasks.hasOpenTaskDeclaring(edge.fromIncidentId(), edge.toIncidentId())) {
                throw ApiException.illegalTransition("边 " + edgeLabel(byId, edge)
                        + " 仍被进行中任务声明为强制依赖，不能删除");
            }
        }
        // 新增边校验：不得已存在、不得引用关闭事件、不得让已完成任务新增未满足前置依赖
        for (GraphProposalEdge edge : additions) {
            GraphEdge key = new GraphEdge(edge.fromIncidentId(), edge.toIncidentId());
            if (current.contains(key)) {
                throw ApiException.conflict("要新增的边已存在于当前依赖图: "
                        + edgeLabel(byId, edge));
            }
            Incident from = byId.get(edge.fromIncidentId());
            Incident to = byId.get(edge.toIncidentId());
            if (from.status() == IncidentStatus.CLOSED || to.status() == IncidentStatus.CLOSED) {
                throw ApiException.illegalTransition("边 " + edgeLabel(byId, edge)
                        + " 引用了已关闭事件，不能新增");
            }
            if (tasks.hasDoneTask(edge.fromIncidentId())
                    && !UNBLOCKING_STATUSES.contains(to.status())) {
                throw ApiException.illegalTransition("边 " + edgeLabel(byId, edge)
                        + " 会让已完成任务新增未满足前置依赖，不能新增");
            }
        }
        // 后态：应用全部增删边后的完整图，不得成环
        Set<GraphEdge> postState = new LinkedHashSet<>(current);
        for (GraphProposalEdge edge : removals) {
            postState.remove(new GraphEdge(edge.fromIncidentId(), edge.toIncidentId()));
        }
        for (GraphProposalEdge edge : additions) {
            postState.add(new GraphEdge(edge.fromIncidentId(), edge.toIncidentId()));
        }
        List<GraphEdge> postEdges = sortedEdges(postState);
        if (DependencyGraphRepository.hasCycle(postEdges)) {
            throw ApiException.illegalTransition("应用全部增删边后依赖图成环，整案拒绝");
        }
        // 原子应用：整案边集变更 + 唯一新图版本 + 前后快照
        for (GraphProposalEdge edge : removals) {
            graph.deleteEdge(edge.fromIncidentId(), edge.toIncidentId());
        }
        for (GraphProposalEdge edge : additions) {
            graph.insertEdgeIfAbsent(edge.fromIncidentId(), edge.toIncidentId(), now);
        }
        graph.bumpVersion(now);
        long newVersion = currentVersion + 1;
        proposals.insertSnapshot(proposal.id(), "BEFORE", currentVersion, currentEdges);
        proposals.insertSnapshot(proposal.id(), "AFTER", newVersion, postEdges);
        proposals.markActivated(proposal.id(), newVersion, now);
        return toView(proposals.findByKey(proposal.proposalKey()).orElseThrow());
    }

    /**
     * 查询当前依赖图：版本号与按事件键稳定排序的完整边集。只读，不隐式写入。
     */
    @Transactional(readOnly = true)
    public GraphView getGraph() {
        Map<Long, String> keys = incidentKeys(graph.listEdges());
        List<GraphEdgeView> edges = graph.listEdges().stream()
                .map(e -> new GraphEdgeView(keys.get(e.fromIncidentId()), keys.get(e.toIncidentId())))
                .sorted(Comparator.comparing(GraphEdgeView::fromIncidentKey)
                        .thenComparing(GraphEdgeView::toIncidentKey))
                .toList();
        return new GraphView(graph.currentVersion(), edges);
    }

    /**
     * 查询提案详情（含名册、票决与激活前后快照）。只读，不隐式写入。
     */
    @Transactional(readOnly = true)
    public ProposalView getProposal(String proposalKey) {
        GraphProposal proposal = proposals.findByKey(proposalKey)
                .orElseThrow(() -> ApiException.notFound("提案不存在: " + proposalKey));
        return toView(proposal);
    }

    /**
     * 按 graphVersion 还原提案证据：返回在该版本激活的提案（含前后边集快照），
     * 按提案键稳定排序。只读，不隐式写入。
     */
    @Transactional(readOnly = true)
    public List<ProposalView> listByGraphVersion(long graphVersion) {
        return proposals.listByAppliedVersion(graphVersion).stream()
                .map(this::toView)
                .toList();
    }

    private List<NormalizedEdge> normalizeEdges(List<GraphProposalEdgeRequest> raw) {
        if (raw == null || raw.isEmpty()) {
            throw ApiException.badRequest("edges 不能为空，提案须包含 1~" + MAX_EDGES_PER_PROPOSAL
                    + " 条边");
        }
        Set<NormalizedEdge> deduped = new LinkedHashSet<>();
        for (GraphProposalEdgeRequest edge : raw) {
            if (edge == null) {
                throw ApiException.badRequest("edges 不能包含空条目");
            }
            EdgeOperation operation = parseOperation(edge.operation());
            String fromKey = requireText(edge.fromIncidentKey(), "fromIncidentKey");
            String toKey = requireText(edge.toIncidentKey(), "toIncidentKey");
            if (fromKey.equals(toKey)) {
                throw ApiException.badRequest("依赖边两端不能是同一事件: " + fromKey);
            }
            deduped.add(new NormalizedEdge(operation, fromKey, toKey));
        }
        if (deduped.size() > MAX_EDGES_PER_PROPOSAL) {
            throw ApiException.badRequest("提案边数去重后最多 " + MAX_EDGES_PER_PROPOSAL
                    + " 条，当前 " + deduped.size() + " 条");
        }
        // 同一对事件不能同时新增与删除
        Set<String> added = new LinkedHashSet<>();
        Set<String> removed = new LinkedHashSet<>();
        for (NormalizedEdge edge : deduped) {
            String pair = edge.fromKey() + SEP + edge.toKey();
            (edge.operation() == EdgeOperation.ADD ? added : removed).add(pair);
        }
        for (String pair : added) {
            if (removed.contains(pair)) {
                throw ApiException.badRequest("同一对事件不能同时新增与删除依赖边: " + pair);
            }
        }
        return deduped.stream()
                .sorted(Comparator.comparing(NormalizedEdge::operation)
                        .thenComparing(NormalizedEdge::fromKey)
                        .thenComparing(NormalizedEdge::toKey))
                .toList();
    }

    private static String edgesHash(List<NormalizedEdge> edges) {
        List<String> parts = new ArrayList<>();
        for (NormalizedEdge edge : edges) {
            parts.add(edge.operation().name() + "|" + edge.fromKey() + "|" + edge.toKey());
        }
        return String.join(",", parts);
    }

    private static EdgeOperation parseOperation(String operation) {
        String text = requireText(operation, "operation");
        try {
            return EdgeOperation.valueOf(text);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("未知边操作: " + text + "，仅支持 ADD/REMOVE");
        }
    }

    private static VoteDecision parseDecision(String decision) {
        String text = requireText(decision, "decision");
        try {
            return VoteDecision.valueOf(text);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("未知表决方向: " + text + "，仅支持 APPROVE/REJECT");
        }
    }

    private void resolveIncident(Map<String, Incident> byKey, String incidentKey) {
        if (!byKey.containsKey(incidentKey)) {
            byKey.put(incidentKey, incidents.findByKey(incidentKey)
                    .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey)));
        }
    }

    private void resolveIncidentById(Map<Long, Incident> byId, long incidentId) {
        if (!byId.containsKey(incidentId)) {
            byId.put(incidentId, incidents.findById(incidentId)
                    .orElseThrow(() -> ApiException.notFound("事件不存在: id=" + incidentId)));
        }
    }

    private GraphProposal lockProposal(String proposalKey) {
        requireText(proposalKey, "proposalKey");
        return proposals.lockByKey(proposalKey)
                .orElseThrow(() -> ApiException.notFound("提案不存在: " + proposalKey));
    }

    private static String edgeLabel(Map<Long, Incident> byId, GraphProposalEdge edge) {
        return byId.get(edge.fromIncidentId()).incidentKey() + " -> "
                + byId.get(edge.toIncidentId()).incidentKey();
    }

    private static List<GraphEdge> sortedEdges(Set<GraphEdge> edges) {
        return edges.stream()
                .sorted(Comparator.comparing(GraphEdge::fromIncidentId)
                        .thenComparing(GraphEdge::toIncidentId))
                .toList();
    }

    /**
     * 将边集涉及的事件 id 映射为业务键（事件不会被删除，必然存在）。
     */
    private Map<Long, String> incidentKeys(List<GraphEdge> edges) {
        Map<Long, String> keys = new HashMap<>();
        for (GraphEdge edge : edges) {
            keys.computeIfAbsent(edge.fromIncidentId(),
                    id -> incidents.findById(id).orElseThrow().incidentKey());
            keys.computeIfAbsent(edge.toIncidentId(),
                    id -> incidents.findById(id).orElseThrow().incidentKey());
        }
        return keys;
    }

    /**
     * 组装提案视图：边集、名册、票决均稳定排序；前后快照仅 ACTIVATED 有值。
     */
    private ProposalView toView(GraphProposal proposal) {
        List<GraphProposalEdge> edges = proposals.listEdges(proposal.id());
        Map<Long, String> keys = new HashMap<>();
        for (GraphProposalEdge edge : edges) {
            keys.computeIfAbsent(edge.fromIncidentId(),
                    id -> incidents.findById(id).orElseThrow().incidentKey());
            keys.computeIfAbsent(edge.toIncidentId(),
                    id -> incidents.findById(id).orElseThrow().incidentKey());
        }
        List<ProposalEdgeView> edgeViews = edges.stream()
                .map(e -> new ProposalEdgeView(e.operation().name(), keys.get(e.fromIncidentId()),
                        keys.get(e.toIncidentId())))
                .sorted(Comparator.comparing(ProposalEdgeView::operation)
                        .thenComparing(ProposalEdgeView::fromIncidentKey)
                        .thenComparing(ProposalEdgeView::toIncidentKey))
                .toList();
        List<RosterSeatView> roster = proposals.listRoster(proposal.id()).stream()
                .map(s -> new RosterSeatView(s.role().name(),
                        s.incidentId() == GraphRosterSeat.REVIEWER_INCIDENT_ID
                                ? null
                                : keys.computeIfAbsent(s.incidentId(),
                                        id -> incidents.findById(id).orElseThrow().incidentKey()),
                        s.person()))
                .sorted(Comparator.comparing(RosterSeatView::role)
                        .thenComparing(s -> s.incidentKey() == null ? "" : s.incidentKey()))
                .toList();
        List<VoteView> votes = proposals.listVotes(proposal.id()).stream()
                .map(v -> new VoteView(v.person(), v.decision().name(), v.votedAt()))
                .toList();
        GraphSnapshotView before = null;
        GraphSnapshotView after = null;
        if (proposal.status() == GraphProposalStatus.ACTIVATED) {
            before = toSnapshotView(proposal.id(), "BEFORE", proposal.expectedGraphVersion());
            after = toSnapshotView(proposal.id(), "AFTER", proposal.appliedGraphVersion());
        }
        return new ProposalView(proposal.proposalKey(), proposal.status().name(),
                proposal.rationale(), proposal.proposer(), proposal.safetyReviewer(),
                proposal.expectedGraphVersion(), proposal.appliedGraphVersion(), edgeViews,
                roster, votes, before, after, proposal.createdAt(), proposal.updatedAt());
    }

    private GraphSnapshotView toSnapshotView(long proposalId, String phase, long graphVersion) {
        List<GraphEdge> edges = proposals.listSnapshot(proposalId, phase);
        Map<Long, String> keys = incidentKeys(edges);
        List<GraphEdgeView> views = edges.stream()
                .map(e -> new GraphEdgeView(keys.get(e.fromIncidentId()), keys.get(e.toIncidentId())))
                .sorted(Comparator.comparing(GraphEdgeView::fromIncidentKey)
                        .thenComparing(GraphEdgeView::toIncidentKey))
                .toList();
        return new GraphSnapshotView(graphVersion, views);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest(field + " 不能为空");
        }
        return value.strip();
    }

    /**
     * 幂等执行：同键同参重放首次响应，同键改参 409；并发同键由唯一约束串行化。
     */
    private <T> T runIdempotent(String requestId, String operation, String requestHash,
                                Class<T> type, Supplier<T> business) {
        var existing = commandKeys.find(requestId);
        if (existing.isPresent()) {
            return replay(existing.get(), operation, requestHash, type);
        }
        try {
            commandKeys.insertPlaceholder(requestId, operation, requestHash, now());
        } catch (DuplicateKeyException e) {
            var committed = commandKeys.findForUpdate(requestId)
                    .orElseThrow(() -> ApiException.conflict("requestId 处理冲突: " + requestId));
            return replay(committed, operation, requestHash, type);
        }
        T result = business.get();
        commandKeys.fillResponse(requestId, 200, toJson(result));
        return result;
    }

    private <T> T replay(CommandKeyRecord record, String operation, String requestHash,
                         Class<T> type) {
        if (!record.operation().equals(operation) || !record.requestHash().equals(requestHash)) {
            throw ApiException.conflict("requestId 已被不同参数的请求使用: " + record.commandKey());
        }
        if (record.responseBody() == null) {
            throw ApiException.conflict("requestId 正在处理中: " + record.commandKey());
        }
        try {
            return objectMapper.readValue(record.responseBody(), type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("幂等响应反序列化失败", e);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    private static String hash(String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] out = digest.digest(String.join(SEP, parts).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(out);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
