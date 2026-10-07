package com.example.starter.incident;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import com.example.starter.incident.dto.Requests.PlanCancelRequest;
import com.example.starter.incident.dto.Requests.PlanCompensateRequest;
import com.example.starter.incident.dto.Requests.PlanCreateRequest;
import com.example.starter.incident.dto.Requests.PlanExecuteRequest;
import com.example.starter.incident.dto.Requests.PlanStepInput;
import com.example.starter.incident.dto.Responses.CompensationView;
import com.example.starter.incident.dto.Responses.IncidentPlansView;
import com.example.starter.incident.dto.Responses.PlanStepView;
import com.example.starter.incident.dto.Responses.PlanSummaryView;
import com.example.starter.incident.dto.Responses.PlanView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 指令计划核心服务：只操作合成状态的有序计划，提供执行、取消与补偿路径。
 * 并发约定：所有写接口先 SELECT ... FOR UPDATE 锁定事件行，同事务内完成
 * 权限校验、幂等占位、业务校验与写入，执行与取消按事务提交顺序串行生效，
 * 不会出现取消后新增效果。
 * 幂等约定：requestId 全局唯一，同键同参重放首次完整响应，同键改参 409，
 * 失败不占键；当前指挥人权限校验先于幂等重放（交接后旧指挥人 403）。
 * 补偿约定：取消 RUNNING/COMPLETED 计划进入 COMPENSATING，按已执行步骤逆序逐项补偿，
 * 全部补偿后 CANCELLED；补偿失败仅保存失败诊断、不改变 APPLIED 状态，允许新 requestId 重试。
 */
@Service
public class CommandPlanService {

    /** 每计划步骤数上限。 */
    private static final int MAX_STEPS_PER_PLAN = 5;

    /** 补偿结果：成功。 */
    private static final String OUTCOME_SUCCESS = "SUCCESS";

    /** 补偿结果：失败（仅保存诊断，不改变步骤状态）。 */
    private static final String OUTCOME_FAILED = "FAILED";

    /** 步骤内容拼接分隔符（Unit Separator）。 */
    private static final String STEP_SEP = "";

    private final IncidentRepository incidents;
    private final CommandPlanRepository plans;
    private final IdempotencySupport idempotency;
    private final Clock clock;

    public CommandPlanService(IncidentRepository incidents, CommandPlanRepository plans,
                              IdempotencySupport idempotency, Clock clock) {
        this.incidents = incidents;
        this.plans = plans;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    private Instant now() {
        return clock.instant();
    }

    /**
     * 创建指令计划：仅当前指挥人；planKey 事件内唯一（同键同内容幂等，同键不同内容 409）；
     * 1~5 个有序步骤，stepKey 计划内唯一，效果与补偿说明非空。初始状态 RUNNING、版本 1。
     */
    @Transactional
    public PlanView createPlan(String incidentKey, String actor, PlanCreateRequest req) {
        String requestId = requireText(req.requestId(), "requestId");
        String planKey = requireText(req.planKey(), "planKey");
        List<PlanStepInput> steps = req.steps() == null ? List.of() : req.steps();
        if (steps.isEmpty() || steps.size() > MAX_STEPS_PER_PLAN) {
            throw ApiException.badRequest("步骤数须为 1~" + MAX_STEPS_PER_PLAN);
        }
        for (PlanStepInput step : steps) {
            requireText(step.stepKey(), "stepKey");
            requireText(step.effectText(), "effectText");
            requireText(step.compensationText(), "compensationText");
        }
        if (steps.stream().map(PlanStepInput::stepKey).distinct().count() != steps.size()) {
            throw ApiException.badRequest("stepKey 在计划内必须唯一");
        }
        Incident incident = lockIncident(incidentKey, actor);
        requireCommander(incident, actor);
        return idempotency.run(requestId, "plan_create",
                IdempotencySupport.hash(incidentKey, actor, planKey, stepsContent(steps)),
                PlanView.class, () -> {
                    if (incident.status() == IncidentStatus.RESOLVED
                            || incident.status() == IncidentStatus.CLOSED) {
                        throw ApiException.illegalTransition(
                                incident.status() + " 状态不允许创建指令计划");
                    }
                    var existing = plans.findPlanByKey(incident.id(), planKey);
                    if (existing.isPresent()) {
                        CommandPlan found = existing.get();
                        List<PlanStepInput> foundSteps = plans.listSteps(found.id()).stream()
                                .map(s -> new PlanStepInput(s.stepKey(), s.effectText(),
                                        s.compensationText()))
                                .toList();
                        if (!foundSteps.equals(steps)) {
                            throw ApiException.conflict("planKey 已被不同内容使用: " + planKey);
                        }
                        return toPlanView(found);
                    }
                    Instant now = now();
                    long planId = plans.insertPlan(new CommandPlan(0L, incident.id(), planKey,
                            PlanStatus.RUNNING, 1, actor, now, now, null, null));
                    for (int i = 0; i < steps.size(); i++) {
                        PlanStepInput step = steps.get(i);
                        plans.insertStep(new CommandPlanStep(0L, planId, step.stepKey().strip(),
                                i + 1, step.effectText(), step.compensationText(),
                                PlanStepStatus.PENDING, null, null, null, null, now));
                    }
                    return toPlanView(plans.findPlanByKey(incident.id(), planKey).orElseThrow());
                });
    }

    /**
     * 执行步骤：仅当前指挥人；仅 RUNNING/COMPLETED 计划可执行；
     * 显式执行只允许当前下一步（第一个 PENDING 步骤），跳步 409；
     * 已 APPLIED 步骤重复执行不重复记效果（幂等返回）；expectedVersion 与当前版本不一致 409。
     * 执行原子记录 APPLIED 并递增版本，全部执行后计划 COMPLETED。
     */
    @Transactional
    public PlanView executeStep(String incidentKey, String planKey, String stepKey, String actor,
                                PlanExecuteRequest req) {
        String requestId = requireText(req.requestId(), "requestId");
        if (req.expectedVersion() == null) {
            throw ApiException.badRequest("expectedVersion 不能为空");
        }
        Incident incident = lockIncident(incidentKey, actor);
        requireCommander(incident, actor);
        return idempotency.run(requestId, "plan_execute",
                IdempotencySupport.hash(incidentKey, planKey, stepKey, actor,
                        String.valueOf(req.expectedVersion())),
                PlanView.class, () -> {
                    CommandPlan plan = findPlan(incident.id(), planKey);
                    CommandPlanStep step = findStep(plan.id(), stepKey);
                    if (plan.status() == PlanStatus.COMPENSATING
                            || plan.status() == PlanStatus.CANCELLED) {
                        throw ApiException.conflict("计划已取消（" + plan.status() + "），不能执行步骤");
                    }
                    if (step.status() == PlanStepStatus.APPLIED) {
                        // 再次执行旧成功步骤：不重复记效果
                        return toPlanView(plan);
                    }
                    CommandPlanStep next = plans.listSteps(plan.id()).stream()
                            .filter(s -> s.status() == PlanStepStatus.PENDING)
                            .findFirst().orElseThrow();
                    if (next.id() != step.id()) {
                        throw ApiException.conflict("只允许执行当前下一步 " + next.stepKey()
                                + "，不能跳步执行 " + stepKey);
                    }
                    if (req.expectedVersion() != plan.version()) {
                        throw ApiException.conflict("计划版本不一致：期望 " + req.expectedVersion()
                                + "，当前 " + plan.version());
                    }
                    Instant now = now();
                    plans.markStepApplied(step.id(), actor, now);
                    boolean allApplied = plans.listSteps(plan.id()).stream()
                            .allMatch(s -> s.status() == PlanStepStatus.APPLIED);
                    plans.updatePlanState(plan.id(),
                            allApplied ? PlanStatus.COMPLETED : PlanStatus.RUNNING,
                            plan.version() + 1, plan.cancelledBy(), plan.cancelledAt(), now);
                    return toPlanView(plans.findPlanByKey(incident.id(), planKey).orElseThrow());
                });
    }

    /**
     * 取消计划：仅当前指挥人；仅 RUNNING/COMPLETED 可取消；
     * 取消后进入 COMPENSATING（无已执行步骤则直接 CANCELLED），未执行步骤不再执行。
     */
    @Transactional
    public PlanView cancelPlan(String incidentKey, String planKey, String actor,
                               PlanCancelRequest req) {
        String requestId = requireText(req.requestId(), "requestId");
        Incident incident = lockIncident(incidentKey, actor);
        requireCommander(incident, actor);
        return idempotency.run(requestId, "plan_cancel",
                IdempotencySupport.hash(incidentKey, planKey, actor),
                PlanView.class, () -> {
                    CommandPlan plan = findPlan(incident.id(), planKey);
                    if (plan.status() == PlanStatus.COMPENSATING
                            || plan.status() == PlanStatus.CANCELLED) {
                        throw ApiException.conflict("计划已处于 " + plan.status() + "，不能重复取消");
                    }
                    Instant now = now();
                    boolean anyApplied = plans.listSteps(plan.id()).stream()
                            .anyMatch(s -> s.status() == PlanStepStatus.APPLIED);
                    plans.updatePlanState(plan.id(),
                            anyApplied ? PlanStatus.COMPENSATING : PlanStatus.CANCELLED,
                            plan.version() + 1, actor, now, now);
                    return toPlanView(plans.findPlanByKey(incident.id(), planKey).orElseThrow());
                });
    }

    /**
     * 补偿步骤：仅当前指挥人；仅 COMPENSATING 计划可补偿；
     * 按已执行步骤逆序逐项补偿（当前应补偿最高 seq 的 APPLIED 步骤），跳步 409；
     * expectedVersion 与当前版本不一致时补偿失败：仅保存失败诊断、不改变 APPLIED 状态，
     * 允许新 requestId 重试；全部补偿后计划 CANCELLED。
     */
    @Transactional
    public PlanView compensateStep(String incidentKey, String planKey, String stepKey, String actor,
                                   PlanCompensateRequest req) {
        String requestId = requireText(req.requestId(), "requestId");
        String evidence = requireText(req.evidence(), "evidence");
        if (req.expectedVersion() == null) {
            throw ApiException.badRequest("expectedVersion 不能为空");
        }
        Incident incident = lockIncident(incidentKey, actor);
        requireCommander(incident, actor);
        return idempotency.run(requestId, "plan_compensate",
                IdempotencySupport.hash(incidentKey, planKey, stepKey, actor, evidence,
                        String.valueOf(req.expectedVersion())),
                PlanView.class, () -> {
                    CommandPlan plan = findPlan(incident.id(), planKey);
                    CommandPlanStep step = findStep(plan.id(), stepKey);
                    if (plan.status() != PlanStatus.COMPENSATING) {
                        throw ApiException.conflict("计划状态为 " + plan.status()
                                + "，不在补偿中，不能补偿");
                    }
                    CommandPlanStep next = plans.listSteps(plan.id()).stream()
                            .filter(s -> s.status() == PlanStepStatus.APPLIED)
                            .reduce((first, second) -> second).orElseThrow();
                    if (next.id() != step.id()) {
                        throw ApiException.conflict("只应按逆序补偿当前步骤 " + next.stepKey()
                                + "，不能跳步补偿 " + stepKey);
                    }
                    Instant now = now();
                    if (req.expectedVersion() != plan.version()) {
                        // 补偿失败：仅保存失败诊断，不改变 APPLIED 状态，允许新 requestId 重试
                        plans.insertCompensation(new CommandPlanCompensation(0L, plan.id(),
                                step.id(), step.stepKey(), evidence, OUTCOME_FAILED,
                                "计划版本不一致：期望 " + req.expectedVersion() + "，当前 "
                                        + plan.version(),
                                actor, now));
                        return toPlanView(plans.findPlanByKey(incident.id(), planKey).orElseThrow());
                    }
                    plans.markStepCompensated(step.id(), actor, now);
                    plans.insertCompensation(new CommandPlanCompensation(0L, plan.id(), step.id(),
                            step.stepKey(), evidence, OUTCOME_SUCCESS, null, actor, now));
                    boolean anyApplied = plans.listSteps(plan.id()).stream()
                            .anyMatch(s -> s.status() == PlanStepStatus.APPLIED);
                    plans.updatePlanState(plan.id(),
                            anyApplied ? PlanStatus.COMPENSATING : PlanStatus.CANCELLED,
                            plan.version() + 1, plan.cancelledBy(), plan.cancelledAt(), now);
                    return toPlanView(plans.findPlanByKey(incident.id(), planKey).orElseThrow());
                });
    }

    /**
     * 按事件查询指令计划列表（摘要）。只读，不隐式写入。
     */
    @Transactional(readOnly = true)
    public IncidentPlansView listPlans(String incidentKey) {
        Incident incident = incidents.findByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
        List<PlanSummaryView> views = plans.listPlansByIncident(incident.id()).stream()
                .map(p -> new PlanSummaryView(p.planKey(), p.status().name(), p.version(),
                        p.createdBy(), p.createdAt(), p.cancelledBy(), p.cancelledAt()))
                .toList();
        return new IncidentPlansView(incident.incidentKey(), views);
    }

    /**
     * 查询单个指令计划明细（含步骤与补偿证据）。只读，不隐式写入。
     */
    @Transactional(readOnly = true)
    public PlanView getPlan(String incidentKey, String planKey) {
        Incident incident = incidents.findByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
        return toPlanView(findPlan(incident.id(), planKey));
    }

    private Incident lockIncident(String incidentKey, String actor) {
        requireText(incidentKey, "incidentKey");
        requireText(actor, "actor");
        return incidents.lockByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
    }

    private CommandPlan findPlan(long incidentId, String planKey) {
        return plans.findPlanByKey(incidentId, planKey)
                .orElseThrow(() -> ApiException.notFound("计划不存在: " + planKey));
    }

    private CommandPlanStep findStep(long planId, String stepKey) {
        return plans.listSteps(planId).stream()
                .filter(s -> s.stepKey().equals(stepKey))
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("步骤不存在: " + stepKey));
    }

    private static void requireCommander(Incident incident, String actor) {
        if (incident.commander() == null || !incident.commander().equals(actor)) {
            throw ApiException.forbidden("只有当前指挥人 "
                    + (incident.commander() == null ? "(无)" : incident.commander()) + " 能执行该操作");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest(field + " 不能为空");
        }
        return value.strip();
    }

    private static String stepsContent(List<PlanStepInput> steps) {
        StringBuilder sb = new StringBuilder();
        for (PlanStepInput step : steps) {
            sb.append(step.stepKey()).append(STEP_SEP)
                    .append(step.effectText()).append(STEP_SEP)
                    .append(step.compensationText()).append(STEP_SEP);
        }
        return sb.toString();
    }

    private PlanView toPlanView(CommandPlan plan) {
        List<PlanStepView> stepViews = plans.listSteps(plan.id()).stream()
                .map(s -> new PlanStepView(s.stepKey(), s.seq(), s.effectText(), s.compensationText(),
                        s.status().name(), s.appliedBy(), s.appliedAt(), s.compensatedBy(),
                        s.compensatedAt()))
                .toList();
        List<CompensationView> compViews = plans.listCompensations(plan.id()).stream()
                .map(c -> new CompensationView(c.stepKey(), c.evidence(), c.outcome(),
                        c.diagnostics(), c.actor(), c.createdAt()))
                .toList();
        return new PlanView(plan.planKey(), plan.status().name(), plan.version(), stepViews,
                compViews, plan.createdBy(), plan.createdAt(), plan.cancelledBy(),
                plan.cancelledAt());
    }
}
