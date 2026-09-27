package com.example.starter.incident;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import com.example.starter.incident.dto.Requests.ProposalCreateRequest;
import com.example.starter.incident.dto.Requests.TaskReviseRequest;
import com.example.starter.incident.dto.Requests.VoteRequest;
import com.example.starter.incident.dto.Requests.WithdrawRequest;
import com.example.starter.incident.dto.Responses.ProposalDetailView;
import com.example.starter.incident.dto.Responses.ProposalListView;
import com.example.starter.incident.dto.Responses.ProposalView;
import com.example.starter.incident.dto.Responses.TaskBlockerView;
import com.example.starter.incident.dto.Responses.TaskView;
import com.example.starter.incident.dto.Responses.VoteView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 事件决策提案服务：提案登记（UTC 到期时刻、所需角色集合、法定人数）、
 * 投票/撤回、任务修订与到期失效裁决。
 * 并发约定：所有写接口先 SELECT ... FOR UPDATE 锁定事件行，投票、撤回、
 * 任务修订和到期判定在同一事务内按提交顺序裁决。
 * 幂等约定：commandKey 全局唯一，同键同参重放首次响应快照，同键改参 409，
 * 失败（含 410 到期）不占键。请求指纹对集合逐元素长度前缀编码，不用分隔符拼接。
 * 生效裁决：到期前 CAST 支持票达到法定人数时，提案转 EFFECTIVE 并同事务原子写入
 * 任务变更（不递增任务版本）；到期后任何投票/撤回返回 410 且提案转 EXPIRED
 * （到期判定与 410 响应同事务提交，见 noRollbackFor）。
 * 任务修订：版本 +1，该任务所有 OPEN 提案的 CAST 票转 STALE，不计入新版本；
 * 已裁决提案的投票历史不改写。
 */
@Service
public class DecisionService {

    private final IncidentRepository incidents;
    private final IncidentTaskRepository tasks;
    private final DecisionProposalRepository proposals;
    private final CommandKeyRepository commandKeys;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public DecisionService(IncidentRepository incidents, IncidentTaskRepository tasks,
                           DecisionProposalRepository proposals, CommandKeyRepository commandKeys,
                           ObjectMapper objectMapper, Clock clock) {
        this.incidents = incidents;
        this.tasks = tasks;
        this.proposals = proposals;
        this.commandKeys = commandKeys;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    private Instant now() {
        return clock.instant();
    }

    /**
     * 登记决策提案：仅当前指挥人；CLOSED 事件禁止登记。
     * 到期时刻不得早于当前可注入时钟、角色集合为空、法定人数小于 1 或超过角色数均返回 422。
     * proposalKey 幂等：同键同内容返回首次提案，同键不同内容返回 409。
     */
    @Transactional
    public ProposalView createProposal(String incidentKey, String actor, ProposalCreateRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        String proposalKey = requireText(req.proposalKey(), "proposalKey");
        String taskKey = requireText(req.taskKey(), "taskKey");
        String proposedTitle = requireText(req.proposedTitle(), "proposedTitle");
        if (req.quorum() == null) {
            throw ApiException.badRequest("quorum 不能为空");
        }
        int quorum = req.quorum();
        if (req.expiresAt() == null) {
            throw ApiException.badRequest("expiresAt 不能为空");
        }
        Instant expiresAt = req.expiresAt().truncatedTo(ChronoUnit.MICROS);
        List<String> roles = normalizeRoles(req.requiredRoles());
        if (roles.isEmpty()) {
            throw ApiException.unprocessable("所需角色集合不能为空");
        }
        if (quorum < 1 || quorum > roles.size()) {
            throw ApiException.unprocessable("法定人数必须介于 1 与角色数之间，实际: " + quorum
                    + "，角色数: " + roles.size());
        }
        if (expiresAt.isBefore(now())) {
            throw ApiException.unprocessable("到期时刻不得早于当前时刻，到期: " + expiresAt
                    + "，当前: " + now());
        }
        Incident incident = lockIncident(incidentKey);
        List<String> fingerprint = new ArrayList<>(List.of(incidentKey, actor, proposalKey,
                taskKey, proposedTitle, String.valueOf(quorum), expiresAt.toString()));
        fingerprint.addAll(roles);
        return runIdempotent(commandKey, "proposal_create",
                hashFields(fingerprint.toArray(new String[0])), ProposalView.class, () -> {
                    requireCommander(incident, actor);
                    if (incident.status() == IncidentStatus.CLOSED) {
                        throw ApiException.illegalTransition("事件已关闭，不能再登记决策提案");
                    }
                    IncidentTask task = tasks.findByKey(incident.id(), taskKey)
                            .orElseThrow(() -> ApiException.notFound("任务不存在: " + taskKey));
                    var existing = proposals.findByKey(incident.id(), proposalKey);
                    if (existing.isPresent()) {
                        DecisionProposal found = existing.get();
                        if (found.taskId() != task.id()
                                || !found.proposedTitle().equals(proposedTitle)
                                || found.quorum() != quorum
                                || !found.expiresAt().equals(expiresAt)
                                || !proposals.listRoles(found.id()).equals(roles)) {
                            throw ApiException.conflict(
                                    "proposalKey 已被不同内容使用: " + proposalKey);
                        }
                        return toProposalView(found);
                    }
                    Instant now = now();
                    long proposalId = proposals.insert(new DecisionProposal(0L, incident.id(),
                            proposalKey, task.id(), task.version(), proposedTitle, quorum,
                            expiresAt, ProposalStatus.OPEN, actor, now, null, null));
                    for (String role : roles) {
                        proposals.insertRole(proposalId, role);
                    }
                    return toProposalView(proposals.findByKey(incident.id(), proposalKey)
                            .orElseThrow());
                });
    }

    /**
     * 投票：投票人（X-Actor-Id）必须在提案所需角色集合内，同一角色同一提案至多一票
     * 有效票（CAST）；到期后投票返回 410 且提案同事务转 EXPIRED；已生效提案投票返回 409。
     * 到期前 CAST 支持票达法定人数时提案转 EFFECTIVE 并原子写入任务变更。
     */
    @Transactional(noRollbackFor = ProposalGoneException.class)
    public ProposalDetailView vote(String incidentKey, String proposalKey, String actor,
                                   VoteRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        String role = requireText(actor, "actor");
        VoteChoice choice = parseChoice(req.vote());
        Incident incident = lockIncident(incidentKey);
        String requestHash = hashFields(incidentKey, role, proposalKey, choice.name());
        return runIdempotentAfterPrecheck(commandKey, "proposal_vote", requestHash,
                ProposalDetailView.class,
                () -> expireIfDue(findProposal(incident, proposalKey)),
                proposal -> {
                    if (proposal.status() == ProposalStatus.EFFECTIVE) {
                        throw ApiException.conflict("提案已生效，不能再投票");
                    }
                    if (!proposals.listRoles(proposal.id()).contains(role)) {
                        throw ApiException.conflict(
                                "角色 " + role + " 不在提案要求的角色集合中");
                    }
                    if (proposals.findActiveVote(proposal.id(), role).isPresent()) {
                        throw ApiException.conflict("角色 " + role + " 已对该提案投过有效票");
                    }
                    IncidentTask task = tasks.findById(proposal.taskId()).orElseThrow();
                    Instant now = now();
                    proposals.insertVote(new ProposalVote(0L, proposal.id(), role, choice,
                            task.version(), VoteStatus.CAST, now, now));
                    long support = proposals.countActiveByChoice(proposal.id(),
                            VoteChoice.SUPPORT);
                    if (support >= proposal.quorum()) {
                        proposals.markDecided(proposal.id(), ProposalStatus.EFFECTIVE, now,
                                task.version());
                        tasks.applyProposedTitle(task.id(), proposal.proposedTitle(), now);
                    }
                    return toDetailView(findProposal(incident, proposalKey));
                });
    }

    /**
     * 撤回投票：仅可撤回自己的有效票（CAST）；到期后撤回返回 410 且提案同事务转 EXPIRED；
     * 已生效提案或没有有效票返回 409。
     */
    @Transactional(noRollbackFor = ProposalGoneException.class)
    public ProposalDetailView withdraw(String incidentKey, String proposalKey, String actor,
                                       WithdrawRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        String role = requireText(actor, "actor");
        Incident incident = lockIncident(incidentKey);
        String requestHash = hashFields(incidentKey, role, proposalKey);
        return runIdempotentAfterPrecheck(commandKey, "proposal_withdraw", requestHash,
                ProposalDetailView.class,
                () -> expireIfDue(findProposal(incident, proposalKey)),
                proposal -> {
                    if (proposal.status() == ProposalStatus.EFFECTIVE) {
                        throw ApiException.conflict("提案已生效，不能再撤回投票");
                    }
                    ProposalVote active = proposals.findActiveVote(proposal.id(), role)
                            .orElseThrow(() -> ApiException.conflict(
                                    "角色 " + role + " 没有可撤回的有效票"));
                    proposals.withdrawVote(active.id(), now());
                    return toDetailView(findProposal(incident, proposalKey));
                });
    }

    /**
     * 任务修订：仅当前指挥人；仅 OPEN 任务可修订；版本 +1 并更新标题。
     * 同事务内先对该任务 OPEN 提案做到期判定（到期的转 EXPIRED），
     * 再将剩余 OPEN 提案的 CAST 票转 STALE（不计入新版本）。
     */
    @Transactional
    public TaskView reviseTask(String incidentKey, String taskKey, String actor,
                               TaskReviseRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        String title = requireText(req.title(), "title");
        Incident incident = lockIncident(incidentKey);
        return runIdempotent(commandKey, "task_revise",
                hashFields(incidentKey, actor, taskKey, title), TaskView.class, () -> {
                    requireCommander(incident, actor);
                    IncidentTask task = tasks.findByKey(incident.id(), taskKey)
                            .orElseThrow(() -> ApiException.notFound("任务不存在: " + taskKey));
                    if (task.status() != TaskStatus.OPEN) {
                        throw ApiException.conflict(
                                "任务已处于终态 " + task.status() + "，不能修订");
                    }
                    Instant now = now();
                    for (DecisionProposal open : proposals.listOpenByTask(task.id())) {
                        if (!now.isBefore(open.expiresAt())) {
                            proposals.markDecided(open.id(), ProposalStatus.EXPIRED, now,
                                    task.version());
                        }
                    }
                    tasks.revise(task.id(), title, now);
                    proposals.staleVotesForTask(task.id(), now);
                    return toTaskView(tasks.findById(task.id()).orElseThrow());
                });
    }

    /**
     * 查询提案明细（含完整投票历史）。只读，不隐式写入，不做到期判定。
     */
    @Transactional(readOnly = true)
    public ProposalDetailView getProposal(String incidentKey, String proposalKey) {
        Incident incident = incidents.findByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
        return toDetailView(findProposal(incident, proposalKey));
    }

    /**
     * 按事件查询提案列表（按登记顺序）。只读，不隐式写入，不做到期判定。
     */
    @Transactional(readOnly = true)
    public ProposalListView listProposals(String incidentKey) {
        Incident incident = incidents.findByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
        List<ProposalView> views = proposals.listByIncident(incident.id()).stream()
                .map(this::toProposalView).toList();
        return new ProposalListView(incident.incidentKey(), views);
    }

    /**
     * 到期判定：OPEN 提案当前时刻 ≥ 到期时刻（左闭右开区间右端点）时转 EXPIRED，
     * 固化裁决时刻与当时任务版本；已到期提案的后续投票/撤回由调用方返回 410。
     */
    private DecisionProposal expireIfDue(DecisionProposal proposal) {
        if (proposal.status() == ProposalStatus.OPEN && !now().isBefore(proposal.expiresAt())) {
            IncidentTask task = tasks.findById(proposal.taskId()).orElseThrow();
            proposals.markDecided(proposal.id(), ProposalStatus.EXPIRED, now(), task.version());
            return proposals.findByKey(proposal.incidentId(), proposal.proposalKey())
                    .orElseThrow();
        }
        return proposal;
    }

    private DecisionProposal findProposal(Incident incident, String proposalKey) {
        requireText(proposalKey, "proposalKey");
        return proposals.findByKey(incident.id(), proposalKey)
                .orElseThrow(() -> ApiException.notFound("提案不存在: " + proposalKey));
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

    private static VoteChoice parseChoice(String vote) {
        String text = requireText(vote, "vote");
        try {
            return VoteChoice.valueOf(text);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("vote 必须为 SUPPORT 或 OPPOSE，实际: " + text);
        }
    }

    /**
     * 规范化角色集合：逐项去空白（空白项 400），去重后按字典序返回（稳定排序）。
     */
    private static List<String> normalizeRoles(List<String> requiredRoles) {
        if (requiredRoles == null) {
            return List.of();
        }
        return requiredRoles.stream()
                .map(r -> requireText(r, "requiredRoles 元素"))
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * 幂等执行（无前置检查）：同键同参重放首次响应，同键改参 409；失败回滚不占键。
     */
    private <T> T runIdempotent(String commandKey, String operation, String requestHash,
                                Class<T> type, Supplier<T> business) {
        return runIdempotentAfterPrecheck(commandKey, operation, requestHash, type,
                () -> null, ignored -> business.get());
    }

    /**
     * 幂等执行（带前置检查）：先按 commandKey 重放首次响应快照；未命中时执行前置检查
     * （如提案到期判定，可能抛出 ProposalGoneException 提交 EXPIRED 并返回 410），
     * 通过后才占位 commandKey 并执行业务，保证 410/校验失败均不占键。
     */
    private <P, T> T runIdempotentAfterPrecheck(String commandKey, String operation,
                                                String requestHash, Class<T> type,
                                                Supplier<P> precheck,
                                                java.util.function.Function<P, T> business) {
        var existing = commandKeys.find(commandKey);
        if (existing.isPresent()) {
            return replay(existing.get(), operation, requestHash, type);
        }
        P prechecked = precheck.get();
        if (prechecked instanceof DecisionProposal proposal
                && proposal.status() == ProposalStatus.EXPIRED) {
            throw new ProposalGoneException("提案已到期失效，不能执行该操作: " + proposal.proposalKey());
        }
        try {
            commandKeys.insertPlaceholder(commandKey, operation, requestHash, now());
        } catch (DuplicateKeyException e) {
            var committed = commandKeys.findForUpdate(commandKey)
                    .orElseThrow(() -> ApiException.conflict("commandKey 处理冲突: " + commandKey));
            return replay(committed, operation, requestHash, type);
        }
        T result = business.apply(prechecked);
        commandKeys.fillResponse(commandKey, 200, toJson(result));
        return result;
    }

    private <T> T replay(CommandKeyRecord record, String operation, String requestHash,
                         Class<T> type) {
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
     * 请求指纹：对每个字段做长度前缀编码后取 SHA-256，
     * 集合逐元素独立编码，不通过未转义分隔符拼接。
     */
    private static String hashFields(String... parts) {
        StringBuilder canonical = new StringBuilder();
        for (String part : parts) {
            canonical.append(part.length()).append(':').append(part);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] out = digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(out);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 组装提案视图：supportCount/opposeCount 为当前 CAST 票实际数，
     * remainingToQuorum 为距生效差额 max(quorum - supportCount, 0)。
     */
    private ProposalView toProposalView(DecisionProposal proposal) {
        List<String> roles = proposals.listRoles(proposal.id());
        long support = proposals.countActiveByChoice(proposal.id(), VoteChoice.SUPPORT);
        long oppose = proposals.countActiveByChoice(proposal.id(), VoteChoice.OPPOSE);
        IncidentTask task = tasks.findById(proposal.taskId()).orElseThrow();
        return new ProposalView(proposal.proposalKey(), task.taskKey(), proposal.taskVersion(),
                proposal.proposedTitle(), roles, proposal.quorum(), proposal.expiresAt(),
                proposal.status().name(), support, oppose,
                Math.max(proposal.quorum() - support, 0L), proposal.createdBy(),
                proposal.createdAt(), proposal.decidedAt(), proposal.decidedTaskVersion());
    }

    /**
     * 组装提案明细视图：投票历史按投票先后返回，含 WITHDRAWN/STALE 记录。
     */
    private ProposalDetailView toDetailView(DecisionProposal proposal) {
        List<VoteView> votes = proposals.listVotes(proposal.id()).stream()
                .map(v -> new VoteView(v.role(), v.vote().name(), v.taskVersion(),
                        v.status().name(), v.castAt(), v.updatedAt()))
                .toList();
        return new ProposalDetailView(toProposalView(proposal), votes);
    }

    /**
     * 组装任务视图（与 IncidentService 一致：阻塞状态按目标事件当前状态计算）。
     */
    private TaskView toTaskView(IncidentTask task) {
        List<TaskBlockerView> blockers = incidents.listBlockingIncidents(task.id()).stream()
                .map(b -> new TaskBlockerView(b.incidentKey(), b.status().name(),
                        b.status() == IncidentStatus.CONTAINED
                                || b.status() == IncidentStatus.RESOLVED
                                || b.status() == IncidentStatus.CLOSED))
                .toList();
        return new TaskView(task.taskKey(), task.groupCode(), task.title(), task.version(),
                task.status().name(), blockers, task.createdBy(), task.createdAt(),
                task.doneBy(), task.doneAt(), task.cancelledBy(), task.cancelledAt());
    }
}
