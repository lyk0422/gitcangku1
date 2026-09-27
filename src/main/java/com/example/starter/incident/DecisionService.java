package com.example.starter.incident;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.example.starter.incident.dto.Requests.ProposalCreateRequest;
import com.example.starter.incident.dto.Requests.TaskReviseRequest;
import com.example.starter.incident.dto.Requests.VoteRequest;
import com.example.starter.incident.dto.Requests.VoteWithdrawRequest;
import com.example.starter.incident.dto.Responses.ProposalDiagnosticsView;
import com.example.starter.incident.dto.Responses.ProposalView;
import com.example.starter.incident.dto.Responses.DecisionListView;
import com.example.starter.incident.dto.Responses.TaskBlockerView;
import com.example.starter.incident.dto.Responses.TaskView;
import com.example.starter.incident.dto.Responses.VoteView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 事件处置提案（决策到期）核心服务，衔接事件指挥、交接与任务依赖。
 *
 * <p>投票口径：UTC 为统一时间口径，投票有效区间为左闭右开 [createdAt, expiresAt)；
 * 当前时刻 ≥ expiresAt 的任何写操作先将 OPEN 提案固化为 EXPIRED，投票/撤回返回 410。
 * 支持或反对票必须来自登记的不同有效角色，同一提案同一角色至多一张当前版本 CAST 票；
 * 到期前支持票达法定人数才生效，生效与任务变更同一事务原子提交，任一校验失败整体回滚。
 *
 * <p>版本口径：提案登记时固化任务版本 baseTaskRevision；任务每修订一次版本 +1，
 * 引用该任务且仍 OPEN 的提案，其当前版本 CAST 票全部转 STALE 并永久保留，不计入新版本；
 * 生效、到期、失效均固化任务版本、角色与时刻，后续任务修订不改写提案历史。
 *
 * <p>并发与幂等：写路径先 SELECT ... FOR UPDATE 锁定事件行，投票、撤回、修订与到期判定
 * 按事务提交顺序裁决；commandKey 全局唯一，同键同参重放首次成功响应快照，
 * 同键异参返回 409，业务失败回滚不占键。请求指纹覆盖操作者、操作类型、
 * 规范化（去重排序）集合与全部参数字段，集合以 JSON 数组编码而非未转义分隔符拼接。
 */
@Service
public class DecisionService {

    /** 视为阻塞已解除的目标事件状态。 */
    private static final Set<IncidentStatus> UNBLOCKING_STATUSES = Set.of(
            IncidentStatus.CONTAINED, IncidentStatus.RESOLVED, IncidentStatus.CLOSED);

    private final IncidentRepository incidents;
    private final IncidentTaskRepository tasks;
    private final DecisionRepository decisions;
    private final DecisionExpiryService expiryService;
    private final CommandKeyRepository commandKeys;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public DecisionService(IncidentRepository incidents, IncidentTaskRepository tasks,
                           DecisionRepository decisions, DecisionExpiryService expiryService,
                           CommandKeyRepository commandKeys,
                           ObjectMapper objectMapper, Clock clock) {
        this.incidents = incidents;
        this.tasks = tasks;
        this.decisions = decisions;
        this.expiryService = expiryService;
        this.commandKeys = commandKeys;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    private Instant now() {
        return clock.instant();
    }

    /**
     * 登记处置提案：仅当前指挥人；任务必须存在且仍 OPEN；
     * 到期时刻早于当前时钟、角色集合为空或法定人数超过角色数（含小于 1）返回 422；
     * proposalKey 事件内唯一，重复返回 409。登记时固化任务版本与角色集合快照。
     */
    @Transactional
    public ProposalView createProposal(String incidentKey, String actor, ProposalCreateRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        String proposalKey = requireText(req.proposalKey(), "proposalKey");
        String taskKey = requireText(req.taskKey(), "taskKey");
        DecisionType type;
        try {
            type = DecisionType.valueOf(requireText(req.decisionType(), "decisionType"));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("decisionType 必须为 TASK_COMPLETE/TASK_CANCEL");
        }
        if (req.expiresAt() == null) {
            throw ApiException.badRequest("expiresAt 不能为空");
        }
        if (req.quorum() == null) {
            throw ApiException.badRequest("quorum 不能为空");
        }
        List<String> roles = req.requiredRoles() == null ? List.of()
                : req.requiredRoles().stream()
                        .map(r -> requireText(r, "requiredRole"))
                        .distinct()
                        .sorted(Comparator.naturalOrder())
                        .toList();
        Incident incident = lockIncident(incidentKey);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("proposalKey", proposalKey);
        params.put("taskKey", taskKey);
        params.put("decisionType", type.name());
        params.put("expiresAt", req.expiresAt().toString());
        params.put("requiredRoles", roles);
        params.put("quorum", req.quorum());
        return runIdempotent(commandKey, "decision_create", actor, params, ProposalView.class, () -> {
            requireCommander(incident, actor);
            if (incident.status() == IncidentStatus.CLOSED) {
                throw ApiException.illegalTransition("事件已关闭，不能再登记处置提案");
            }
            if (roles.isEmpty()) {
                throw ApiException.invalidProposal("所需角色集合不能为空");
            }
            int quorum = req.quorum();
            if (quorum < 1) {
                throw ApiException.invalidProposal("法定人数至少为 1，实际值: " + quorum);
            }
            if (quorum > roles.size()) {
                throw ApiException.invalidProposal("法定人数 " + quorum + " 超过所需角色数 "
                        + roles.size() + "，差额: " + (quorum - roles.size()));
            }
            Instant current = now();
            if (req.expiresAt().isBefore(current)) {
                throw ApiException.invalidProposal("到期时刻 " + req.expiresAt()
                        + " 早于当前时刻 " + current);
            }
            if (decisions.findByKey(incident.id(), proposalKey).isPresent()) {
                throw ApiException.conflict("proposalKey 已存在: " + proposalKey);
            }
            IncidentTask task = tasks.findByKey(incident.id(), taskKey)
                    .orElseThrow(() -> ApiException.notFound("任务不存在: " + taskKey));
            if (task.status() != TaskStatus.OPEN) {
                throw ApiException.conflict("任务已处于终态 " + task.status() + "，不能登记提案");
            }
            Instant createdAt = now();
            final long proposalId;
            try {
                proposalId = decisions.insertProposal(new DecisionProposal(0L, incident.id(),
                        proposalKey, taskKey, type, req.expiresAt(), quorum, task.revision(),
                        DecisionStatus.OPEN, actor, createdAt, null, null, null, null));
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("proposalKey 已存在: " + proposalKey);
            }
            for (String role : roles) {
                decisions.insertRole(proposalId, role);
            }
            return toView(incident.id(),
                    decisions.findByKey(incident.id(), proposalKey).orElseThrow());
        });
    }

    /**
     * 投票：角色必须属于所需角色集合；同提案同角色仅一张当前版本有效票；
     * 当前时刻 ≥ expiresAt 时提案转 EXPIRED 并返回 410（左闭右开，到期当刻即失效）；
     * 已生效返回 409。到期前支持票达法定人数时同事务原子写入任务变更：
     * COMPLETE 仍须全部阻塞事件解除，否则 409 回滚（不留票、不占键、无半成品）。
     */
    @Transactional
    public ProposalView vote(String incidentKey, String proposalKey, String actor, VoteRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        String roleCode = requireText(req.roleCode(), "roleCode");
        VoteChoice choice;
        try {
            choice = VoteChoice.valueOf(requireText(req.choice(), "choice"));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("choice 必须为 SUPPORT/OPPOSE");
        }
        Incident incident = lockIncident(incidentKey);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("proposalKey", proposalKey);
        params.put("roleCode", roleCode);
        params.put("choice", choice.name());
        return runIdempotent(commandKey, "decision_vote", actor, params, ProposalView.class, () -> {
            DecisionProposal proposal = getOpenOrExpire(incident.id(), proposalKey);
            List<String> roles = decisions.listRoles(proposal.id());
            if (!roles.contains(roleCode)) {
                throw ApiException.badRequest("角色 " + roleCode + " 不属于提案所需角色集合 "
                        + roles);
            }
            if (decisions.findLatestByRole(proposal.id(), roleCode)
                    .filter(v -> v.status() == VoteStatus.CAST).isPresent()) {
                throw ApiException.conflict("角色 " + roleCode + " 已对该提案投过有效票");
            }
            IncidentTask task = tasks.findByKey(incident.id(), proposal.taskKey())
                    .orElseThrow(() -> ApiException.notFound("任务不存在: " + proposal.taskKey()));
            Instant votedAt = now();
            try {
                decisions.insertVote(new DecisionVote(0L, proposal.id(), task.revision(),
                        actor, roleCode, choice, VoteStatus.CAST, votedAt, null, null));
            } catch (DuplicateKeyException e) {
                // uk_decision_active_role：并发同角色投票只允许一张 CAST 票
                throw ApiException.conflict("角色 " + roleCode + " 已对该提案投过有效票");
            }
            if (choice == VoteChoice.SUPPORT) {
                int support = decisions.countCastVotes(proposal.id(), task.revision(),
                        VoteChoice.SUPPORT);
                if (support >= proposal.quorum()) {
                    applyEffect(incident, proposal, task, actor, votedAt);
                }
            }
            return toView(incident.id(),
                    decisions.findByKey(incident.id(), proposalKey).orElseThrow());
        });
    }

    /**
     * 撤回：仅投票人本人可撤回自己当前版本的 CAST 票；到期提案返回 410，
     * 已生效返回 409，无有效票返回 409。撤回票转 WITHDRAWN 永久保留，释放角色投票占用。
     */
    @Transactional
    public ProposalView withdraw(String incidentKey, String proposalKey, String actor,
                                 VoteWithdrawRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        Incident incident = lockIncident(incidentKey);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("proposalKey", proposalKey);
        return runIdempotent(commandKey, "decision_vote_withdraw", actor, params,
                ProposalView.class, () -> {
                    DecisionProposal proposal = getOpenOrExpire(incident.id(), proposalKey);
                    // CAST 票只可能属于尚未失效的最新版本，按本人 + CAST 定位即可
                    List<DecisionVote> mine = decisions.listVotes(proposal.id()).stream()
                            .filter(v -> v.status() == VoteStatus.CAST
                                    && v.voterActor().equals(actor))
                            .toList();
                    if (mine.isEmpty()) {
                        throw ApiException.conflict("没有可撤回的当前版本有效票: " + proposalKey);
                    }
                    Instant withdrawnAt = now();
                    for (DecisionVote vote : mine) {
                        if (decisions.withdraw(vote.id(), actor, withdrawnAt) == 0) {
                            throw ApiException.conflict("投票已被并发处理，不能撤回");
                        }
                    }
                    return toView(incident.id(),
                            decisions.findByKey(incident.id(), proposalKey).orElseThrow());
                });
    }

    /**
     * 任务修订：仅当前指挥人，仅 OPEN 任务可修订；版本号 +1（可选更新标题）。
     * 引用该任务的 OPEN 提案按提交顺序裁决：已到达到期时刻的先固化为 EXPIRED，
     * 其余提案当前版本 CAST 票全部转 STALE（不计入新版本）；
     * EFFECTIVE/EXPIRED 提案及其投票历史不受影响。
     */
    @Transactional
    public TaskView reviseTask(String incidentKey, String taskKey, String actor,
                               TaskReviseRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        String note = requireText(req.note(), "note");
        String newTitle = req.title() == null ? null : requireText(req.title(), "title");
        Incident incident = lockIncident(incidentKey);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("taskKey", taskKey);
        params.put("title", newTitle);
        params.put("note", note);
        return runIdempotent(commandKey, "task_revise", actor, params, TaskView.class, () -> {
            requireCommander(incident, actor);
            IncidentTask task = tasks.findByKey(incident.id(), taskKey)
                    .orElseThrow(() -> ApiException.notFound("任务不存在: " + taskKey));
            if (task.status() != TaskStatus.OPEN) {
                throw ApiException.conflict("任务已处于终态 " + task.status() + "，不能修订");
            }
            IncidentTask locked = tasks.lockById(task.id()).orElseThrow();
            Instant revisedAt = now();
            int newRevision = tasks.revise(locked.id(), newTitle, actor, revisedAt);
            if (newRevision == 0 || newRevision == locked.revision()) {
                throw ApiException.conflict("任务已被并发处理，修订未生效");
            }
            for (DecisionProposal proposal : decisions.listOpenByTask(incident.id(), taskKey)) {
                DecisionProposal lockedProposal = decisions.lockById(proposal.id()).orElseThrow();
                if (lockedProposal.status() != DecisionStatus.OPEN) {
                    continue;
                }
                if (!revisedAt.isBefore(lockedProposal.expiresAt())) {
                    decisions.markExpired(lockedProposal.id(), revisedAt);
                } else {
                    decisions.markCastVotesStale(lockedProposal.id(), revisedAt);
                }
            }
            return toTaskView(incident.id(),
                    tasks.findByKey(incident.id(), taskKey).orElseThrow());
        });
    }

    /** 查询提案明细（含所需角色、计票与全部投票历史）。只读，不隐式写入。 */
    @Transactional(readOnly = true)
    public ProposalView getProposal(String incidentKey, String proposalKey) {
        Incident incident = incidents.findByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
        DecisionProposal proposal = decisions.findByKey(incident.id(), proposalKey)
                .orElseThrow(() -> ApiException.notFound("提案不存在: " + proposalKey));
        return toView(incident.id(), proposal);
    }

    /** 查询事件全部提案，按登记顺序稳定返回。只读，不隐式写入。 */
    @Transactional(readOnly = true)
    public DecisionListView listProposals(String incidentKey) {
        Incident incident = incidents.findByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
        List<ProposalView> views = decisions.listByIncident(incident.id()).stream()
                .map(p -> toView(incident.id(), p))
                .toList();
        return new DecisionListView(incidentKey, views);
    }

    /**
     * 查询提案诊断：当前时钟时刻、区间是否开放、剩余秒数（实际值）、
     * 支持/反对实际票数、距法定人数差额、已投票角色与尚未投票角色（实际差额）。
     * 只读，不隐式写入，到期状态以提案固化状态为准不做惰性翻转。
     */
    @Transactional(readOnly = true)
    public ProposalDiagnosticsView diagnostics(String incidentKey, String proposalKey) {
        Incident incident = incidents.findByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
        DecisionProposal proposal = decisions.findByKey(incident.id(), proposalKey)
                .orElseThrow(() -> ApiException.notFound("提案不存在: " + proposalKey));
        IncidentTask task = tasks.findByKey(incident.id(), proposal.taskKey())
                .orElseThrow(() -> ApiException.notFound("任务不存在: " + proposal.taskKey()));
        List<String> roles = decisions.listRoles(proposal.id());
        // OPEN 跟随任务当前版本；终态提案按终局 CAST 票统计，后续修订不改写诊断历史值
        List<DecisionVote> castVotes = proposal.status() == DecisionStatus.OPEN
                ? decisions.listCastVotes(proposal.id(), task.revision())
                : decisions.listVotes(proposal.id()).stream()
                        .filter(v -> v.status() == VoteStatus.CAST).toList();
        int support = (int) castVotes.stream().filter(v -> v.choice() == VoteChoice.SUPPORT).count();
        int oppose = (int) castVotes.stream().filter(v -> v.choice() == VoteChoice.OPPOSE).count();
        Set<String> votedRoles = castVotes.stream().map(DecisionVote::roleCode).collect(Collectors.toSet());
        List<String> missingRoles = roles.stream().filter(r -> !votedRoles.contains(r)).toList();
        Instant current = now();
        boolean votingOpen = proposal.status() == DecisionStatus.OPEN
                && !current.isBefore(proposal.createdAt()) && current.isBefore(proposal.expiresAt());
        long secondsRemaining = current.isBefore(proposal.expiresAt())
                ? Duration.between(current, proposal.expiresAt()).toSeconds() : 0L;
        return new ProposalDiagnosticsView(proposalKey, proposal.status().name(), current,
                proposal.expiresAt(), votingOpen, secondsRemaining, proposal.quorum(),
                support, oppose, Math.max(0, proposal.quorum() - support),
                castVotes.size(), votedRoles.size(), roles.size(), missingRoles,
                task.revision(), proposal.baseTaskRevision());
    }

    /**
     * 达法定人数后的生效写入：在事件行锁与提案行锁内再锁任务行，
     * 校验任务仍 OPEN 且版本未变；COMPLETE 先过阻塞门禁；条件更新任务与提案均为 1 行，
     * 任一失败抛异常回滚整个事务，保证票、任务变更、提案生效同生共死。
     */
    private void applyEffect(Incident incident, DecisionProposal proposal, IncidentTask task,
                             String actor, Instant at) {
        IncidentTask locked = tasks.lockById(task.id()).orElseThrow();
        if (locked.status() != TaskStatus.OPEN) {
            throw ApiException.conflict("任务已处于终态 " + locked.status()
                    + "，决策无法生效，投票回滚: " + proposal.proposalKey());
        }
        if (locked.revision() != task.revision()) {
            throw ApiException.conflict("任务版本已变化（要求版本 " + task.revision()
                    + "，实际版本 " + locked.revision() + "），决策无法生效，投票回滚",
                    Map.of("requiredRevision", task.revision(),
                            "actualRevision", locked.revision()));
        }
        int updated;
        if (proposal.decisionType() == DecisionType.TASK_COMPLETE) {
            List<String> unresolved = unresolvedBlockers(locked.id());
            if (!unresolved.isEmpty()) {
                throw ApiException.conflict("存在未解除阻塞的事件，决策无法生效，投票回滚: "
                        + String.join(",", unresolved), List.copyOf(unresolved));
            }
            updated = tasks.markDoneIfRevision(locked.id(), locked.revision(), actor, at);
        } else {
            updated = tasks.markCancelledIfRevision(locked.id(), locked.revision(), actor, at);
        }
        if (updated == 0) {
            throw ApiException.conflict("任务已被并发修订或处理，决策无法生效，投票回滚");
        }
        if (decisions.markEffective(proposal.id(), at, actor, locked.revision()) == 0) {
            throw ApiException.conflict("提案已被并发处理，决策未生效，投票回滚");
        }
    }

    /**
     * 投票/撤回入口的到期判定（调用方已持有事件行锁，同事件写操作不会并发）：
     * 普通读提案（不锁提案行，避免与独立到期事务自锁）；OPEN 且当前时刻 ≥ expiresAt 时，
     * 通过 REQUIRES_NEW 已提交事务把提案固化为 EXPIRED，再抛出 410——外层因 410 回滚时
     * 不会撤销已提交的 EXPIRED 固化，且失败不占用 commandKey。
     */
    private DecisionProposal getOpenOrExpire(long incidentId, String proposalKey) {
        DecisionProposal proposal = decisions.findByKey(incidentId, proposalKey)
                .orElseThrow(() -> ApiException.notFound("提案不存在: " + proposalKey));
        if (proposal.status() != DecisionStatus.OPEN
                || now().isBefore(proposal.expiresAt())) {
            return requireOpen(proposal);
        }
        Instant expiredAt = now();
        expiryService.commitExpiryIfOpen(proposal.id(), expiredAt);
        DecisionProposal latest = reloadProposal(proposal.id(), incidentId, proposalKey);
        return requireOpen(latest);
    }

    private static DecisionProposal requireOpen(DecisionProposal proposal) {
        return switch (proposal.status()) {
            case OPEN -> proposal;
            case EXPIRED -> throw ApiException.gone("提案已到期，写操作不再受理: "
                    + proposal.proposalKey());
            case EFFECTIVE -> throw ApiException.conflict("提案已生效: " + proposal.proposalKey());
        };
    }

    private DecisionProposal reloadProposal(long proposalId, long incidentId, String proposalKey) {
        return decisions.findByKey(incidentId, proposalKey)
                .or(() -> decisions.lockById(proposalId))
                .orElseThrow(() -> ApiException.notFound("提案不存在: " + proposalKey));
    }

    private List<String> unresolvedBlockers(long taskId) {
        return incidents.listBlockingIncidents(taskId).stream()
                .filter(b -> !UNBLOCKING_STATUSES.contains(b.status()))
                .map(Incident::incidentKey)
                .toList();
    }

    private Incident lockIncident(String incidentKey) {
        requireText(incidentKey, "incidentKey");
        return incidents.lockByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
    }

    private static void requireCommander(Incident incident, String actor) {
        if (incident.commander() == null || !incident.commander().equals(actor)) {
            throw ApiException.conflict("只有当前指挥人 "
                    + (incident.commander() == null ? "(无)" : incident.commander()) + " 能执行该操作");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest(field + " 不能为空");
        }
        return value.strip();
    }

    /**
     * 幂等执行：同键同参重放首次响应，同键改参 409；并发同键由唯一约束串行化，
     * 业务失败随事务回滚，不保留占位。
     */
    private <T> T runIdempotent(String commandKey, String operation, String actor,
                                Map<String, Object> params, Class<T> type, Supplier<T> business) {
        String requestHash = fingerprint(operation, actor, params);
        var existing = commandKeys.find(commandKey);
        if (existing.isPresent()) {
            return replay(existing.get(), operation, requestHash, type);
        }
        try {
            commandKeys.insertPlaceholder(commandKey, operation, requestHash, now());
        } catch (DuplicateKeyException e) {
            var committed = commandKeys.findForUpdate(commandKey)
                    .orElseThrow(() -> ApiException.conflict("commandKey 处理冲突: " + commandKey));
            return replay(committed, operation, requestHash, type);
        }
        T result = business.get();
        commandKeys.fillResponse(commandKey, 200, toJson(result));
        return result;
    }

    private <T> T replay(CommandKeyRecord record, String operation, String requestHash, Class<T> type) {
        if (!record.operation().equals(operation) || !record.requestHash().equals(requestHash)) {
            throw ApiException.conflict("commandKey 已被不同参数的请求使用: " + record.commandKey());
        }
        if (record.responseBody() == null) {
            throw ApiException.conflict("commandKey 正在处理中: " + record.commandKey());
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

    /**
     * 计算请求指纹：规范化 JSON（操作者、操作类型、去重排序集合与全部参数字段）
     * 的 SHA-256 摘要。集合以 JSON 数组编码，元素经 JSON 转义，不直接拼接分隔符。
     */
    private String fingerprint(String operation, String actor, Map<String, Object> params) {
        return RequestFingerprints.sha256(objectMapper, operation, actor, params);
    }

    /**
     * 组装提案视图。计票口径：OPEN 跟随任务当前版本（旧版本 STALE 不计入）；
     * EFFECTIVE/EXPIRED 为终态，其 CAST 票即终局计票，后续任务修订不会改写历史票数。
     * votes 返回全部投票历史（含 STALE/WITHDRAWN），按落库 id 升序稳定排序。
     */
    private ProposalView toView(long incidentId, DecisionProposal proposal) {
        IncidentTask task = tasks.findByKey(incidentId, proposal.taskKey())
                .orElseThrow(() -> ApiException.notFound("任务不存在: " + proposal.taskKey()));
        List<String> roles = decisions.listRoles(proposal.id());
        List<VoteView> voteViews = decisions.listVotes(proposal.id()).stream()
                .map(DecisionService::toVoteView)
                .toList();
        final int support;
        final int oppose;
        if (proposal.status() == DecisionStatus.OPEN) {
            support = decisions.countCastVotes(proposal.id(), task.revision(), VoteChoice.SUPPORT);
            oppose = decisions.countCastVotes(proposal.id(), task.revision(), VoteChoice.OPPOSE);
        } else {
            support = decisions.countCastVotesAnyRevision(proposal.id(), VoteChoice.SUPPORT);
            oppose = decisions.countCastVotesAnyRevision(proposal.id(), VoteChoice.OPPOSE);
        }
        return new ProposalView(proposal.proposalKey(), proposal.taskKey(),
                proposal.decisionType().name(), proposal.status().name(), roles, proposal.quorum(),
                task.revision(), proposal.baseTaskRevision(), support, oppose,
                Math.max(0, proposal.quorum() - support),
                proposal.createdBy(), proposal.createdAt(), proposal.expiresAt(),
                proposal.effectiveAt(), proposal.effectiveBy(),
                proposal.effectiveTaskRevision(), proposal.expiredAt(), voteViews);
    }

    private static VoteView toVoteView(DecisionVote vote) {
        return new VoteView(vote.id(), vote.taskRevision(), vote.voterActor(), vote.roleCode(),
                vote.choice().name(), vote.status().name(), vote.votedAt(),
                vote.staleAt(), vote.withdrawnAt());
    }

    /**
     * 组装任务视图：阻塞状态按目标事件查询时的当前状态计算，不写回依赖任务。
     */
    private TaskView toTaskView(long incidentId, IncidentTask task) {
        List<TaskBlockerView> blockers = incidents.listBlockingIncidents(task.id()).stream()
                .map(b -> new TaskBlockerView(b.incidentKey(), b.status().name(),
                        UNBLOCKING_STATUSES.contains(b.status())))
                .toList();
        return new TaskView(task.taskKey(), task.revision(), task.groupCode(), task.title(),
                task.status().name(), blockers, task.createdBy(), task.createdAt(),
                task.doneBy(), task.doneAt(), task.cancelledBy(), task.cancelledAt());
    }
}
