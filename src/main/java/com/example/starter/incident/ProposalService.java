package com.example.starter.incident;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import com.example.starter.incident.dto.Requests.EdgeChangeRequest;
import com.example.starter.incident.dto.Requests.ProposalCreateRequest;
import com.example.starter.incident.dto.Requests.ProposalVoteRequest;
import com.example.starter.incident.dto.Responses.DependencyEdgeView;
import com.example.starter.incident.dto.Responses.EdgeChangeView;
import com.example.starter.incident.dto.Responses.GraphEvidenceView;
import com.example.starter.incident.dto.Responses.ProposalView;
import com.example.starter.incident.dto.Responses.RosterEntryView;
import com.example.starter.incident.dto.Responses.VoteView;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 跨事件依赖图变更提案核心服务。
 *
 * <p>创建：规范化（结构化去重、(op,from,to) 排序、换序等价）1~50 条边变更，
 * 校验 expectedGraphVersion 匹配当前图版本，锁定全部受影响事件行并冻结
 * 各事件现任指挥官与一名安全审核员，形成不可变名册；后续指挥交接不改写名册。
 *
 * <p>投票：按人员只计一票（兼任多席位时一票覆盖全部席位）；非名册成员不可投；
 * 任一反对立即使提案 REJECTED；全体名册人员赞成后在同一事务内尝试原子激活。
 *
 * <p>激活：先持有依赖图全局锁并锁定版本元数据行，重读当前完整依赖图，要求版本仍匹配，
 * 以集合语义把全部增删边作为一个后态验证（不成环、新增边不引用关闭事件、
 * 不让已完成任务新增未满足前置、不删除仍被进行中任务声明为强制的边）；
 * 任一失败整体回滚（不改图、不推进版本、触发票不保留），成功只生成一个新版本号，
 * 并保存提案、名册、票决与前后边集快照。
 *
 * <p>幂等：requestId 全局唯一，同参（边换序视为同参）重放首次响应，异参 409，
 * 业务失败随事务回滚不占键；proposalKey 全局唯一。
 */
@Service
public class ProposalService {

    /** 单次提案变更边条数上下限。 */
    private static final int MIN_CHANGES = 1;
    private static final int MAX_CHANGES = 50;

    /** 阻塞视为已解除（前置已满足）的事件状态。 */
    private static final Set<IncidentStatus> UNBLOCKING_STATUSES = EnumSet.of(
            IncidentStatus.CONTAINED, IncidentStatus.RESOLVED, IncidentStatus.CLOSED);

    private final IncidentRepository incidents;
    private final IncidentTaskRepository tasks;
    private final DependencyEdgeRepository edges;
    private final ProposalRepository proposals;
    private final IdempotencySupport idempotency;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ProposalService(IncidentRepository incidents, IncidentTaskRepository tasks,
                           DependencyEdgeRepository edges, ProposalRepository proposals,
                           IdempotencySupport idempotency, ObjectMapper objectMapper, Clock clock) {
        this.incidents = incidents;
        this.tasks = tasks;
        this.edges = edges;
        this.proposals = proposals;
        this.idempotency = idempotency;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    private Instant now() {
        return clock.instant();
    }

    /**
     * 创建提案。
     */
    @Transactional
    public ProposalView create(String actor, ProposalCreateRequest req) {
        String requestId = requireText(req.requestId(), "requestId");
        String proposalKey = requireText(req.proposalKey(), "proposalKey");
        String businessNote = requireText(req.businessNote(), "businessNote");
        String safetyReviewer = requireText(req.safetyReviewer(), "safetyReviewer");
        requireText(actor, "X-Actor-Id");
        List<EdgeChange> changes = normalize(req.changes());

        String requestHash = IdempotencySupport.hash("proposal_create", actor, proposalKey,
                Long.toString(req.expectedGraphVersion()), businessNote, safetyReviewer,
                changes.stream().map(c -> c.op() + ":" + c.fromIncidentKey() + "->" + c.toIncidentKey())
                        .collect(Collectors.joining(",")));

        return idempotency.run(requestId, "proposal_create", requestHash, ProposalView.class, () -> {
            if (proposals.findByKey(proposalKey).isPresent()) {
                throw ApiException.conflict("proposalKey 已存在: " + proposalKey);
            }
            Instant now = now();
            // 先锁定全部受影响事件行（按 id 排序，与任务创建保持“事件行→图锁”加锁顺序），
            // 再持有依赖图全局锁读取基线版本，与并发指挥交接/图变更按提交顺序收敛。
            List<Incident> affected = lockAffectedIncidents(changes);
            tasks.lockGraph();
            edges.ensureMeta(now);
            long currentVersion = edges.lockMetaForVersion();
            if (currentVersion != req.expectedGraphVersion()) {
                throw ApiException.conflict("expectedGraphVersion 不匹配当前图版本: 期望 "
                        + req.expectedGraphVersion() + "，当前 " + currentVersion);
            }
            DependencyProposal proposal = new DependencyProposal(0L, proposalKey,
                    req.expectedGraphVersion(), businessNote, safetyReviewer, actor,
                    toJson(changes), ProposalStatus.PENDING, null, null, null, now, null);
            long proposalId;
            try {
                proposalId = proposals.insertProposal(proposal);
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("proposalKey 已存在: " + proposalKey);
            }
            // 冻结名册：每个受影响事件现任指挥官一个 COMMANDER 席位 + 一个安全审核员席位
            for (Incident incident : affected) {
                proposals.insertRosterEntry(new ProposalRosterEntry(0L, proposalId, incident.id(),
                        incident.commander(), RosterRole.COMMANDER, now));
            }
            proposals.insertRosterEntry(new ProposalRosterEntry(0L, proposalId, null,
                    safetyReviewer, RosterRole.SAFETY_REVIEWER, now));
            return toView(proposals.lockById(proposalId).orElseThrow());
        });
    }

    /**
     * 名册人员首次投票。反对即 REJECTED；全员赞成时同事务原子激活。
     */
    @Transactional
    public ProposalView vote(String proposalKey, String actor, ProposalVoteRequest req) {
        String requestId = requireText(req.requestId(), "requestId");
        requireText(actor, "X-Actor-Id");
        VoteChoice choice;
        try {
            choice = VoteChoice.valueOf(requireText(req.choice(), "choice"));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("choice 只能为 YES 或 NO");
        }
        String requestHash = IdempotencySupport.hash("proposal_vote", proposalKey, actor, choice.name());
        return idempotency.run(requestId, "proposal_vote", requestHash, ProposalView.class, () -> {
            DependencyProposal proposal = proposals.lockByKey(proposalKey)
                    .orElseThrow(() -> ApiException.notFound("提案不存在: " + proposalKey));
            if (proposal.status() != ProposalStatus.PENDING) {
                throw ApiException.conflict("提案已处于终态 " + proposal.status() + "，不能再投票");
            }
            List<ProposalRosterEntry> roster = proposals.listRoster(proposal.id());
            Set<String> rosterPersons = roster.stream()
                    .map(ProposalRosterEntry::personId).collect(Collectors.toSet());
            if (!rosterPersons.contains(actor)) {
                throw ApiException.conflict("操作人不在提案投票名册中，不能代为投票: " + actor);
            }
            if (proposals.findVote(proposal.id(), actor).isPresent()) {
                throw ApiException.conflict("该人员已投过票，只能首次投票: " + actor);
            }
            Instant now = now();
            proposals.insertVote(new ProposalVote(0L, proposal.id(), actor, choice, now, now));
            if (choice == VoteChoice.NO) {
                int rejected = proposals.markRejected(proposal.id(), now);
                if (rejected == 0) {
                    throw ApiException.conflict("提案状态已被并发改变，投票失败");
                }
                return toView(proposals.lockById(proposal.id()).orElseThrow());
            }
            // YES：若名册全员均已赞成，则在本事务内原子激活；否则保持 PENDING
            List<ProposalVote> votes = proposals.listVotes(proposal.id());
            boolean allYes = votes.size() == rosterPersons.size()
                    && votes.stream().allMatch(v -> v.choice() == VoteChoice.YES);
            if (allYes) {
                activate(proposal, roster, now);
            }
            return toView(proposals.lockById(proposal.id()).orElseThrow());
        });
    }

    /**
     * 查询提案完整证据（变更、名册、票决、前后边集快照），只读、稳定排序。
     */
    @Transactional(readOnly = true)
    public ProposalView get(String proposalKey) {
        return toView(proposals.findByKey(proposalKey)
                .orElseThrow(() -> ApiException.notFound("提案不存在: " + proposalKey)));
    }

    /**
     * 按图版本还原证据：版本对应提案的后态边集快照；当前版本无提案时读实时全图；
     * 初始版本 1 可由首个激活提案（版本 2）的前态快照还原。只读、稳定排序。
     */
    @Transactional(readOnly = true)
    public GraphEvidenceView evidenceAtVersion(long graphVersion) {
        if (graphVersion < 1) {
            throw ApiException.badRequest("graphVersion 必须从 1 开始");
        }
        DependencyProposal activated =
                proposals.findByActivatedGraphVersion(graphVersion).orElse(null);
        if (activated != null) {
            return new GraphEvidenceView(graphVersion, parseEdgeViews(activated.afterEdgesJson()),
                    activated.proposalKey());
        }
        // 只读路径不初始化元数据：版本行尚未建立时 currentVersion() 视为初始版本 1
        long current = edges.currentVersion();
        if (graphVersion == current) {
            return new GraphEvidenceView(current, snapshotEdges(edges.listAllEdges()), null);
        }
        if (graphVersion == 1L) {
            DependencyProposal first = proposals.findByActivatedGraphVersion(2L).orElse(null);
            if (first != null) {
                return new GraphEvidenceView(1L, parseEdgeViews(first.beforeEdgesJson()), null);
            }
        }
        throw ApiException.notFound("无法还原图版本证据: " + graphVersion);
    }

    /**
     * 原子激活：持全局锁与版本行锁，重读全图，版本匹配后做整体后态校验与改图。
     * 任一校验失败抛出异常，整个投票事务回滚。
     */
    private void activate(DependencyProposal proposal, List<ProposalRosterEntry> roster, Instant now) {
        tasks.lockGraph();
        long currentVersion = edges.lockMetaForVersion();
        if (currentVersion != proposal.expectedGraphVersion()) {
            throw ApiException.conflict("当前图版本已变化，提案不能激活: 期望基线 "
                    + proposal.expectedGraphVersion() + "，当前 " + currentVersion);
        }

        List<EdgeChange> changes = parseChanges(proposal.changesJson());
        // 重新解析受影响事件（状态/指挥可能已变化），后态校验以重读数据为准
        Map<String, Incident> incidentByKey = new HashMap<>();
        for (EdgeChange change : changes) {
            incidentByKey.putIfAbsent(change.fromIncidentKey(),
                    incidents.findByKey(change.fromIncidentKey())
                            .orElseThrow(() -> ApiException.notFound(
                                    "事件不存在: " + change.fromIncidentKey())));
            incidentByKey.putIfAbsent(change.toIncidentKey(),
                    incidents.findByKey(change.toIncidentKey())
                            .orElseThrow(() -> ApiException.notFound(
                                    "事件不存在: " + change.toIncidentKey())));
        }

        List<DependencyEdgeRepository.EdgeRow> currentRows = edges.listAllEdges();
        Map<EdgePair, DependencyEdgeRepository.EdgeRow> currentEdges = new LinkedHashMap<>();
        for (DependencyEdgeRepository.EdgeRow row : currentRows) {
            currentEdges.put(new EdgePair(row.fromIncidentId(), row.toIncidentId()), row);
        }
        Set<EdgePair> addPairs = new HashSet<>();
        Set<EdgePair> removedPairs = new HashSet<>();
        for (EdgeChange change : changes) {
            EdgePair pair = new EdgePair(
                    incidentByKey.get(change.fromIncidentKey()).id(),
                    incidentByKey.get(change.toIncidentKey()).id());
            if (change.op() == EdgeOp.ADD) {
                addPairs.add(pair);
            } else {
                removedPairs.add(pair);
            }
        }
        // 集合语义后态：(当前边集 − 删除集) ∪ 新增集，与变更提交顺序无关
        Set<EdgePair> postPairs = new HashSet<>(currentEdges.keySet());
        postPairs.removeAll(removedPairs);
        postPairs.addAll(addPairs);
        Set<EdgePair> newlyAdded = new HashSet<>(addPairs);
        newlyAdded.removeAll(currentEdges.keySet());
        Set<EdgePair> effectiveRemovals = new HashSet<>(removedPairs);
        effectiveRemovals.retainAll(currentEdges.keySet());
        effectiveRemovals.removeAll(addPairs);

        validatePostState(postPairs, newlyAdded, effectiveRemovals, incidentByKey, changes);

        String beforeJson = toJson(snapshotEdges(currentRows));
        for (EdgePair pair : effectiveRemovals) {
            edges.deleteEdge(pair.fromIncidentId(), pair.toIncidentId());
            // 终态任务不再受完成门禁约束，同步清理其残留强制声明，保持两表一致
            edges.deleteTerminalTaskDeclarations(pair.fromIncidentId(), pair.toIncidentId());
        }
        for (EdgePair pair : addPairs) {
            edges.insertEdgeIfAbsent(pair.fromIncidentId(), pair.toIncidentId(),
                    EdgeOp.SOURCE_PROPOSAL, proposal.id(), now);
        }
        long newVersion = currentVersion + 1;
        edges.bumpVersion(newVersion, now);
        String afterJson = toJson(snapshotEdges(edges.listAllEdges()));
        int activated = proposals.markActivated(proposal.id(), newVersion, beforeJson, afterJson, now);
        if (activated == 0) {
            throw ApiException.conflict("提案状态已被并发改变，激活失败");
        }
    }

    /**
     * 后态整体校验：环、关闭事件引用、已完成任务新增未满足前置、进行中任务强制边删除保护。
     */
    private void validatePostState(Set<EdgePair> postPairs, Set<EdgePair> newlyAdded,
                                   Set<EdgePair> effectiveRemovals,
                                   Map<String, Incident> incidentByKey, List<EdgeChange> changes) {
        // 1) 整图后态不得成环
        if (hasCycle(postPairs)) {
            throw ApiException.conflict("变更后的依赖图存在环，提案被拒绝");
        }
        // 2)/3) 仅对本次真正新增的边校验：不引用关闭事件；不让已完成任务新增未满足前置
        for (EdgePair pair : newlyAdded) {
            Incident from = incidentById(incidentByKey, pair.fromIncidentId(), changes);
            Incident to = incidentById(incidentByKey, pair.toIncidentId(), changes);
            if (from.status() == IncidentStatus.CLOSED || to.status() == IncidentStatus.CLOSED) {
                throw ApiException.illegalTransition(
                        "新增依赖边引用了已关闭事件: " + from.incidentKey() + "->" + to.incidentKey());
            }
            if (edges.existsDoneTask(from.id()) && !UNBLOCKING_STATUSES.contains(to.status())) {
                throw ApiException.illegalTransition("事件 " + from.incidentKey()
                        + " 已有已完成任务，不能新增未满足的前置依赖: " + to.incidentKey()
                        + " 当前状态 " + to.status());
            }
        }
        // 4) 不得删除仍被进行中（OPEN）任务声明为强制的边
        for (EdgePair pair : effectiveRemovals) {
            if (edges.openTaskDeclaresEdge(pair.fromIncidentId(), pair.toIncidentId())) {
                Incident from = incidentById(incidentByKey, pair.fromIncidentId(), changes);
                Incident to = incidentById(incidentByKey, pair.toIncidentId(), changes);
                throw ApiException.conflict("边 " + from.incidentKey() + "->" + to.incidentKey()
                        + " 仍被进行中任务声明为强制依赖，不能删除");
            }
        }
    }

    private static Incident incidentById(Map<String, Incident> incidentByKey, long id,
                                         List<EdgeChange> changes) {
        return incidentByKey.values().stream().filter(i -> i.id() == id).findFirst()
                .orElseThrow(() -> new IllegalStateException("后态校验缺少事件数据: " + id));
    }

    /**
     * 有向图环检测：对每个未访问节点做 DFS 三色标记，发现回边即有环。
     */
    private static boolean hasCycle(Set<EdgePair> pairs) {
        Map<Long, List<Long>> adjacency = new HashMap<>();
        for (EdgePair pair : pairs) {
            adjacency.computeIfAbsent(pair.fromIncidentId(), k -> new ArrayList<>())
                    .add(pair.toIncidentId());
        }
        Map<Long, Integer> mark = new HashMap<>();
        for (Long node : adjacency.keySet()) {
            if (mark.getOrDefault(node, 0) == 0 && visitsCycle(node, adjacency, mark)) {
                return true;
            }
        }
        return false;
    }

    private static boolean visitsCycle(Long node, Map<Long, List<Long>> adjacency,
                                       Map<Long, Integer> mark) {
        mark.put(node, 1);
        for (Long next : adjacency.getOrDefault(node, List.of())) {
            int nextMark = mark.getOrDefault(next, 0);
            if (nextMark == 1) {
                return true;
            }
            if (nextMark == 0 && visitsCycle(next, adjacency, mark)) {
                return true;
            }
        }
        mark.put(node, 2);
        return false;
    }

    /**
     * 解析并规范化请求边集：非空 1~50 条，op 合法、端点非空且不可自引用，
     * 按 (op,from,to) 结构化去重并稳定排序（换序等价）。
     */
    private static List<EdgeChange> normalize(List<EdgeChangeRequest> raw) {
        if (raw == null || raw.isEmpty()) {
            throw ApiException.badRequest("changes 至少包含 " + MIN_CHANGES + " 条边变更");
        }
        if (raw.size() > MAX_CHANGES) {
            throw ApiException.badRequest("changes 最多包含 " + MAX_CHANGES + " 条边变更");
        }
        Set<EdgeChange> dedup = new TreeSet<>();
        for (EdgeChangeRequest change : raw) {
            if (change == null) {
                throw ApiException.badRequest("changes 不能包含空条目");
            }
            EdgeOp op;
            try {
                op = EdgeOp.valueOf(requireText(change.op(), "op"));
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("op 只能为 ADD 或 DELETE");
            }
            String from = requireText(change.fromIncidentKey(), "fromIncidentKey");
            String to = requireText(change.toIncidentKey(), "toIncidentKey");
            if (from.equals(to)) {
                throw ApiException.badRequest("依赖边不能自引用: " + from);
            }
            dedup.add(new EdgeChange(op, from, to));
        }
        if (dedup.size() < MIN_CHANGES) {
            throw ApiException.badRequest("changes 至少包含 " + MIN_CHANGES + " 条有效边变更");
        }
        return new ArrayList<>(dedup);
    }

    /**
     * 锁定全部受影响事件（按 id 排序避免多事件死锁），并要求已接管（有指挥官可冻结）。
     */
    private List<Incident> lockAffectedIncidents(List<EdgeChange> changes) {
        Map<String, Incident> unique = new TreeMap<>();
        for (EdgeChange change : changes) {
            for (String key : List.of(change.fromIncidentKey(), change.toIncidentKey())) {
                Incident incident = incidents.lockByKey(key)
                        .orElseThrow(() -> ApiException.notFound("受影响事件不存在: " + key));
                if (incident.commander() == null) {
                    throw ApiException.conflict(
                            "受影响事件尚未接管，无法冻结指挥官名册: " + key);
                }
                unique.putIfAbsent(key, incident);
            }
        }
        return unique.values().stream()
                .sorted(Comparator.comparingLong(Incident::id)).toList();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest(field + " 不能为空");
        }
        return value.strip();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("序列化失败", e);
        }
    }

    private List<EdgeChange> parseChanges(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<EdgeChange>>() {
            });
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("变更集合反序列化失败", e);
        }
    }

    private List<DependencyEdgeView> parseEdgeViews(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<DependencyEdgeView>>() {
            });
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("边集快照反序列化失败", e);
        }
    }

    /** 全量边快照：按 (from,to) 稳定排序。 */
    private List<DependencyEdgeView> snapshotEdges(List<DependencyEdgeRepository.EdgeRow> rows) {
        return rows.stream()
                .sorted(Comparator.comparing(DependencyEdgeRepository.EdgeRow::fromIncidentKey)
                        .thenComparing(DependencyEdgeRepository.EdgeRow::toIncidentKey))
                .map(r -> new DependencyEdgeView(r.fromIncidentKey(), r.toIncidentKey(),
                        r.source(), r.refId()))
                .toList();
    }

    /**
     * 组装提案视图：名册按席位 id、票决按票 id 稳定排序；快照按落库 JSON 还原。
     */
    private ProposalView toView(DependencyProposal proposal) {
        List<ProposalRosterEntry> roster = proposals.listRoster(proposal.id());
        Map<Long, String> incidentKeys = incidents.findByIds(
                roster.stream().map(ProposalRosterEntry::incidentId).filter(java.util.Objects::nonNull)
                        .distinct().toList())
                .entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey,
                        e -> e.getValue().incidentKey()));
        List<RosterEntryView> rosterViews = roster.stream()
                .map(r -> new RosterEntryView(r.personId(), r.role().name(),
                        r.incidentId() == null ? null : incidentKeys.get(r.incidentId())))
                .toList();
        List<VoteView> voteViews = proposals.listVotes(proposal.id()).stream()
                .map(v -> new VoteView(v.personId(), v.choice().name(), v.votedAt()))
                .toList();
        return new ProposalView(proposal.proposalKey(), proposal.expectedGraphVersion(),
                proposal.status().name(), proposal.businessNote(), proposal.safetyReviewer(),
                proposal.createdBy(), proposal.createdAt(), proposal.activatedAt(),
                proposal.activatedGraphVersion(), parseChanges(proposal.changesJson()).stream()
                .map(c -> new EdgeChangeView(c.op().name(), c.fromIncidentKey(), c.toIncidentKey()))
                .toList(),
                rosterViews, voteViews,
                parseEdgeViews(proposal.beforeEdgesJson()),
                parseEdgeViews(proposal.afterEdgesJson()));
    }

    /** 有向边端点对（结构化身份）。 */
    private record EdgePair(long fromIncidentId, long toIncidentId) {
    }
}
