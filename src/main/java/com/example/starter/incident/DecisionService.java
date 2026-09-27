package com.example.starter.incident;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.starter.incident.dto.Requests.DecisionCreateRequest;
import com.example.starter.incident.dto.Requests.DecisionExpireCheckRequest;
import com.example.starter.incident.dto.Requests.VoteRequest;
import com.example.starter.incident.dto.Requests.VoteWithdrawRequest;
import com.example.starter.incident.dto.Responses.DecisionDiagnosticsView;
import com.example.starter.incident.dto.Responses.DecisionHistoryView;
import com.example.starter.incident.dto.Responses.DecisionListView;
import com.example.starter.incident.dto.Responses.DecisionView;
import com.example.starter.incident.dto.Responses.VoteView;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 事件处置决策提案服务：登记 UTC 到期时刻、所需角色集合与法定人数。
 * 并发约定：所有写接口先 SELECT ... FOR UPDATE 锁定事件行，再锁定提案行，
 * 投票、撤回、任务修订与到期判定按事务提交顺序裁决。
 * 生效规则：投票窗口 [登记时刻, expiresAt) 左闭右开；窗口内当前任务版本的
 * CAST 支持票达到法定人数即生效，并同事务原子写入任务变更（COMPLETE/CANCEL）。
 * 到期规则：到期后任何投票返回 410 且提案转 EXPIRED（到期转移独立提交，不占幂等键）。
 * 版本规则：任务修订使 OPEN 提案的全部 CAST 票转 STALE（保留为历史证据），
 * 不计入新版本；同一角色对同一提案的同一任务版本仅一票。
 * 幂等约定：commandKey 全局唯一，同键同参重放首次响应快照，同键改参 409，失败不占键。
 */
@Service
public class DecisionService {

    /** 决策生效写入任务变更时的操作者标记前缀。 */
    static final String DECISION_ACTOR_PREFIX = "decision:";

    private final IncidentRepository incidents;
    private final IncidentTaskRepository tasks;
    private final DecisionRepository decisions;
    private final CommandKeyExecutor executor;
    private final Clock clock;
    private final TransactionTemplate tx;

    public DecisionService(IncidentRepository incidents, IncidentTaskRepository tasks,
                           DecisionRepository decisions, CommandKeyExecutor executor,
                           Clock clock, PlatformTransactionManager transactionManager) {
        this.incidents = incidents;
        this.tasks = tasks;
        this.decisions = decisions;
        this.executor = executor;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactionManager);
    }

    private Instant now() {
        return clock.instant();
    }

    /**
     * 登记决策提案：仅当前指挥人；proposalKey 事件内唯一（同键同内容幂等，同键不同内容 409）。
     * 到期时刻不得早于当前注入时钟、角色集合为空、法定人数超出 [1, 角色数] 均返回 422，
     * 并在 details 中返回实际值、要求值或差额。
     */
    @Transactional
    public DecisionView register(String incidentKey, String actor, DecisionCreateRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        String proposalKey = requireText(req.proposalKey(), "proposalKey");
        String taskKey = requireText(req.taskKey(), "taskKey");
        DecisionAction action = parseAction(req.taskAction());
        if (req.requiredRoles() == null || req.requiredRoles().isEmpty()) {
            throw ApiException.unprocessable("所需角色集合不能为空",
                    Map.of("actualRoles", 0, "requiredMinRoles", 1));
        }
        List<String> roles = CommandKeyExecutor.normalizeSet(req.requiredRoles().stream()
                .map(r -> requireText(r, "requiredRole"))
                .toList());
        if (req.quorum() == null) {
            throw ApiException.badRequest("quorum 不能为空");
        }
        int quorum = req.quorum();
        if (quorum < 1) {
            throw ApiException.unprocessable("法定人数必须至少为 1",
                    Map.of("actualQuorum", quorum, "requiredMinQuorum", 1,
                            "deficit", 1 - quorum));
        }
        if (quorum > roles.size()) {
            throw ApiException.unprocessable("法定人数超过角色集合大小",
                    Map.of("actualQuorum", quorum, "roleCount", roles.size(),
                            "excess", quorum - roles.size()));
        }
        if (req.expiresAt() == null) {
            throw ApiException.badRequest("expiresAt 不能为空");
        }
        Instant expiresAt = req.expiresAt().truncatedTo(ChronoUnit.MICROS);
        Incident incident = lockIncident(incidentKey);
        Instant now = now();
        if (expiresAt.isBefore(now)) {
            throw ApiException.unprocessable("到期时刻不得早于当前时刻",
                    Map.of("actualExpiresAt", expiresAt.toString(),
                            "requiredNotBefore", now.toString()));
        }
        return executor.run(commandKey, "decision_register",
                CommandKeyExecutor.hash(incidentKey, actor, proposalKey, taskKey, action.name(),
                        String.valueOf(quorum), expiresAt.toString(),
                        CommandKeyExecutor.hashSet(roles)),
                DecisionView.class, () -> {
                    requireCommander(incident, actor);
                    var existing = decisions.findByKey(incident.id(), proposalKey);
                    if (existing.isPresent()) {
                        IncidentDecision found = existing.get();
                        if (!found.taskKey().equals(taskKey) || found.taskAction() != action
                                || !found.requiredRoles().equals(roles)
                                || found.quorum() != quorum
                                || !found.expiresAt().equals(expiresAt)) {
                            throw ApiException.conflict(
                                    "proposalKey 已被不同内容使用: " + proposalKey);
                        }
                        return toView(found);
                    }
                    IncidentTask task = tasks.findByKey(incident.id(), taskKey)
                            .orElseThrow(() -> ApiException.notFound("任务不存在: " + taskKey));
                    if (task.status() != TaskStatus.OPEN) {
                        throw ApiException.conflict(
                                "任务已处于终态 " + task.status() + "，不能登记决策提案");
                    }
                    IncidentDecision proposal = new IncidentDecision(0L, incident.id(),
                            proposalKey, task.id(), task.taskKey(), action, task.version(), roles,
                            quorum, DecisionStatus.OPEN, null, null, expiresAt, actor, now, now);
                    try {
                        decisions.insert(proposal);
                    } catch (DuplicateKeyException e) {
                        throw ApiException.conflict("proposalKey 已存在: " + proposalKey);
                    }
                    return toView(decisions.findByKey(incident.id(), proposalKey).orElseThrow());
                });
    }

    /**
     * 投票：角色必须属于提案所需角色集合（否则 422）；同一角色对同一任务版本仅一票（重复 409）。
     * 到期后投票返回 410 且提案转 EXPIRED（到期转移先行提交，本次投票不占幂等键）。
     * 窗口内 CAST 支持票达法定人数即生效，并同事务原子写入任务变更。
     */
    public VoteView vote(String incidentKey, String proposalKey, String actor, VoteRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        String role = requireText(req.role(), "role");
        VoteBallot ballot = parseBallot(req.ballot());
        String voteActor = requireText(actor, "X-Actor-Id");
        TxOutcome<VoteView> outcome = tx.execute(status -> doVote(
                incidentKey, proposalKey, voteActor, commandKey, role, ballot));
        if (outcome == null) {
            throw new IllegalStateException("投票事务未返回结果");
        }
        if (outcome.expired()) {
            throw ApiException.gone("提案已到期（EXPIRED），投票窗口已关闭: " + proposalKey);
        }
        return outcome.view();
    }

    private TxOutcome<VoteView> doVote(String incidentKey, String proposalKey, String actor,
                                       String commandKey, String role, VoteBallot ballot) {
        Incident incident = lockIncident(incidentKey);
        IncidentDecision proposal = lockDecision(incident.id(), proposalKey);
        if (proposal.status() == DecisionStatus.OPEN && !now().isBefore(proposal.expiresAt())) {
            decisions.markExpired(proposal.id(), now());
            return TxOutcome.expiredOutcome();
        }
        if (proposal.status() == DecisionStatus.EXPIRED) {
            return TxOutcome.expiredOutcome();
        }
        return TxOutcome.of(executor.run(commandKey, "decision_vote",
                CommandKeyExecutor.hash(incidentKey, proposalKey, actor, role, ballot.name()),
                VoteView.class, () -> {
                    if (proposal.status() != DecisionStatus.OPEN) {
                        throw ApiException.conflict("提案已生效（EFFECTIVE），不能再投票");
                    }
                    if (!proposal.requiredRoles().contains(role)) {
                        Map<String, Object> details = new LinkedHashMap<>();
                        details.put("actualRole", role);
                        details.put("requiredRoles", proposal.requiredRoles());
                        throw ApiException.unprocessable("投票角色不在所需角色集合内", details);
                    }
                    int version = proposal.currentTaskVersion();
                    if (decisions.findVote(proposal.id(), role, version).isPresent()) {
                        throw ApiException.conflict("同一角色对同一提案的同一任务版本仅一票: " + role);
                    }
                    Instant now = now();
                    DecisionVote vote = new DecisionVote(0L, proposal.id(), role, ballot, version,
                            VoteStatus.CAST, actor, now, now);
                    try {
                        decisions.insertVote(vote);
                    } catch (DuplicateKeyException e) {
                        throw ApiException.conflict("同一角色对同一提案的同一任务版本仅一票: " + role);
                    }
                    if (ballot == VoteBallot.SUPPORT
                            && decisions.countCastVotes(proposal.id(), version, VoteBallot.SUPPORT)
                                    >= proposal.quorum()) {
                        // 达法定人数：生效并同事务原子写入任务变更
                        IncidentTask task = tasks.findByKey(incident.id(), proposal.taskKey())
                                .orElseThrow(() -> ApiException.notFound(
                                        "任务不存在: " + proposal.taskKey()));
                        if (task.status() != TaskStatus.OPEN) {
                            throw ApiException.conflict("任务已处于终态 " + task.status()
                                    + "，提案无法生效");
                        }
                        if (proposal.taskAction() == DecisionAction.COMPLETE) {
                            tasks.markDone(task.id(), DECISION_ACTOR_PREFIX + proposalKey, now);
                        } else {
                            tasks.markCancelled(task.id(), DECISION_ACTOR_PREFIX + proposalKey,
                                    now);
                        }
                        decisions.markEffective(proposal.id(), version, now);
                    }
                    return toVoteView(decisions.findVote(proposal.id(), role, version)
                            .orElseThrow());
                }));
    }

    /**
     * 撤回投票：仅可撤回本角色当前任务版本下的 CAST 票；撤回后不计入法定人数。
     * 提案已到期/已生效不能撤回（到期提案先转 EXPIRED 再返回 409）。
     */
    public VoteView withdraw(String incidentKey, String proposalKey, String actor,
                             VoteWithdrawRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        String role = requireText(req.role(), "role");
        String withdrawActor = requireText(actor, "X-Actor-Id");
        TxOutcome<VoteView> outcome = tx.execute(status -> doWithdraw(
                incidentKey, proposalKey, withdrawActor, commandKey, role));
        if (outcome == null) {
            throw new IllegalStateException("撤回事务未返回结果");
        }
        if (outcome.expired()) {
            throw ApiException.conflict("提案已到期（EXPIRED），不能撤回投票");
        }
        return outcome.view();
    }

    private TxOutcome<VoteView> doWithdraw(String incidentKey, String proposalKey, String actor,
                                           String commandKey, String role) {
        Incident incident = lockIncident(incidentKey);
        IncidentDecision proposal = lockDecision(incident.id(), proposalKey);
        if (proposal.status() == DecisionStatus.OPEN && !now().isBefore(proposal.expiresAt())) {
            decisions.markExpired(proposal.id(), now());
            return TxOutcome.expiredOutcome();
        }
        return TxOutcome.of(executor.run(commandKey, "decision_withdraw",
                CommandKeyExecutor.hash(incidentKey, proposalKey, actor, role),
                VoteView.class, () -> {
                    if (proposal.status() != DecisionStatus.OPEN) {
                        throw ApiException.conflict(
                                "提案状态为 " + proposal.status() + "，不能撤回投票");
                    }
                    DecisionVote vote = decisions
                            .findVote(proposal.id(), role, proposal.currentTaskVersion())
                            .orElseThrow(() -> ApiException.notFound(
                                    "该角色在当前任务版本下没有投票: " + role));
                    if (vote.status() != VoteStatus.CAST) {
                        throw ApiException.conflict(
                                "投票状态为 " + vote.status() + "，不能撤回");
                    }
                    decisions.withdrawVote(vote.id(), now());
                    return toVoteView(decisions
                            .findVote(proposal.id(), role, proposal.currentTaskVersion())
                            .orElseThrow());
                }));
    }

    /**
     * 到期判定：以注入 Clock 的当前时刻评估，OPEN 且当前时刻 ≥ 到期时刻则提案转 EXPIRED；
     * 否则保持现状。同键重放首次结果，失败不占键。
     */
    @Transactional
    public DecisionView expireCheck(String incidentKey, String proposalKey,
                                    DecisionExpireCheckRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        Incident incident = lockIncident(incidentKey);
        IncidentDecision proposal = lockDecision(incident.id(), proposalKey);
        return executor.run(commandKey, "decision_expire",
                CommandKeyExecutor.hash(incidentKey, proposalKey),
                DecisionView.class, () -> {
                    if (proposal.status() == DecisionStatus.OPEN
                            && !now().isBefore(proposal.expiresAt())) {
                        decisions.markExpired(proposal.id(), now());
                    }
                    return toView(decisions.findByKey(incident.id(), proposalKey).orElseThrow());
                });
    }

    /**
     * 查询提案明细（含当前计票统计）。只读，不隐式写入。
     */
    @Transactional(readOnly = true)
    public DecisionView get(String incidentKey, String proposalKey) {
        Incident incident = incidents.findByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
        IncidentDecision proposal = decisions.findByKey(incident.id(), proposalKey)
                .orElseThrow(() -> ApiException.notFound("提案不存在: " + proposalKey));
        return toView(proposal);
    }

    /**
     * 查询提案完整历史：提案视图 + 全部投票证据（按投票顺序，含 WITHDRAWN/STALE）。
     * 只读，不隐式写入；历史证据不被后续操作覆盖。
     */
    @Transactional(readOnly = true)
    public DecisionHistoryView history(String incidentKey, String proposalKey) {
        Incident incident = incidents.findByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
        IncidentDecision proposal = decisions.findByKey(incident.id(), proposalKey)
                .orElseThrow(() -> ApiException.notFound("提案不存在: " + proposalKey));
        List<VoteView> votes = decisions.listVotes(proposal.id()).stream()
                .map(DecisionService::toVoteView).toList();
        return new DecisionHistoryView(toView(proposal), votes);
    }

    /**
     * 按事件查询全部提案（按登记顺序）。只读，不隐式写入。
     */
    @Transactional(readOnly = true)
    public DecisionListView list(String incidentKey) {
        Incident incident = incidents.findByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
        List<DecisionView> views = decisions.listByIncident(incident.id()).stream()
                .map(this::toView).toList();
        return new DecisionListView(incident.incidentKey(), views);
    }

    /**
     * 查询提案诊断：各状态票数与法定人数差额。只读，不隐式写入。
     */
    @Transactional(readOnly = true)
    public DecisionDiagnosticsView diagnostics(String incidentKey, String proposalKey) {
        Incident incident = incidents.findByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
        IncidentDecision proposal = decisions.findByKey(incident.id(), proposalKey)
                .orElseThrow(() -> ApiException.notFound("提案不存在: " + proposalKey));
        int support = decisions.countCastVotes(proposal.id(), proposal.currentTaskVersion(),
                VoteBallot.SUPPORT);
        int oppose = decisions.countCastVotes(proposal.id(), proposal.currentTaskVersion(),
                VoteBallot.OPPOSE);
        return new DecisionDiagnosticsView(proposal.proposalKey(), proposal.status().name(),
                proposal.quorum(), support, oppose,
                decisions.countVotesByStatus(proposal.id(), VoteStatus.WITHDRAWN),
                decisions.countVotesByStatus(proposal.id(), VoteStatus.STALE),
                Math.max(0, proposal.quorum() - support),
                proposal.expiresAt(), proposal.decidedAt());
    }

    private Incident lockIncident(String incidentKey) {
        requireText(incidentKey, "incidentKey");
        return incidents.lockByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
    }

    private IncidentDecision lockDecision(long incidentId, String proposalKey) {
        requireText(proposalKey, "proposalKey");
        return decisions.lockByKey(incidentId, proposalKey)
                .orElseThrow(() -> ApiException.notFound("提案不存在: " + proposalKey));
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

    private static DecisionAction parseAction(String value) {
        String text = requireText(value, "taskAction");
        try {
            return DecisionAction.valueOf(text);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("未知任务变更类型: " + text);
        }
    }

    private static VoteBallot parseBallot(String value) {
        String text = requireText(value, "ballot");
        try {
            return VoteBallot.valueOf(text);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("未知票型: " + text);
        }
    }

    /**
     * 组装提案视图：计票统计按当前任务版本的 CAST 票实时计算，剩余差额不为负。
     */
    private DecisionView toView(IncidentDecision proposal) {
        int support = decisions.countCastVotes(proposal.id(), proposal.currentTaskVersion(),
                VoteBallot.SUPPORT);
        int oppose = decisions.countCastVotes(proposal.id(), proposal.currentTaskVersion(),
                VoteBallot.OPPOSE);
        return new DecisionView(proposal.proposalKey(), proposal.taskKey(),
                proposal.taskAction().name(), proposal.currentTaskVersion(),
                proposal.requiredRoles(), proposal.quorum(), support, oppose,
                Math.max(0, proposal.quorum() - support), proposal.status().name(),
                proposal.decidedTaskVersion(), proposal.decidedAt(), proposal.expiresAt(),
                proposal.createdBy(), proposal.createdAt());
    }

    private static VoteView toVoteView(DecisionVote vote) {
        return new VoteView(vote.role(), vote.ballot().name(), vote.taskVersion(),
                vote.status().name(), vote.actor(), vote.castAt(), vote.updatedAt());
    }

    /**
     * 写事务结果：正常视图或“已到期”标记（到期转移随事务提交后由外层抛出对应错误）。
     */
    private record TxOutcome<T>(T view, boolean expired) {

        static <T> TxOutcome<T> of(T view) {
            return new TxOutcome<>(view, false);
        }

        static <T> TxOutcome<T> expiredOutcome() {
            return new TxOutcome<>(null, true);
        }
    }
}
