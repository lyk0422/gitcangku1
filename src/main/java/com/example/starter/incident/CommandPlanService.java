package com.example.starter.incident;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import com.example.starter.incident.dto.Requests.PlanCancelRequest;
import com.example.starter.incident.dto.Requests.PlanCompensateRequest;
import com.example.starter.incident.dto.Requests.PlanCreateRequest;
import com.example.starter.incident.dto.Requests.PlanStepDefinition;
import com.example.starter.incident.dto.Requests.PlanStepExecuteRequest;
import com.example.starter.incident.dto.Responses.CompensationAttemptView;
import com.example.starter.incident.dto.Responses.PlanStepView;
import com.example.starter.incident.dto.Responses.PlanView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 联动处置指令计划服务：只操作合成状态，不发起任何外部 HTTP 调用。
 *
 * <p>计划由当前指挥人创建，最多 5 个有序步骤，每步含计划内唯一 stepKey、
 * 合成效果说明与补偿说明。显式执行只允许当前下一步，原子记录 APPLIED，
 * 重复执行旧成功步骤不重复记效果；全部执行后 COMPLETED，否则 RUNNING。
 *
 * <p>取消 RUNNING/COMPLETED 计划进入 COMPENSATING，未执行步骤不再执行；
 * 按已执行步骤逆序逐项确认补偿，全部补偿后 CANCELLED。补偿失败仅保存失败诊断、
 * 不改变 APPLIED 状态，允许用新 commandKey 重试；跳步补偿 409。
 *
 * <p>所有写路径先锁定事件行，计划/步骤状态、合成效果与补偿证据同事务提交，
 * 执行与取消竞争下不会出现取消后新增效果。交接后由新指挥继续补偿，旧指挥 403。
 */
@Service
public class CommandPlanService {

    /** 每张指令计划的步骤上限。 */
    private static final int MAX_STEPS_PER_PLAN = 5;

    private static final String OP_CREATE = "plan_create";
    private static final String OP_EXECUTE = "plan_step_execute";
    private static final String OP_CANCEL = "plan_cancel";
    private static final String OP_COMPENSATE = "plan_step_compensate";

    private final IncidentRepository incidents;
    private final CommandPlanRepository plans;
    private final CommandKeySupport idempotency;
    private final Clock clock;

    public CommandPlanService(IncidentRepository incidents, CommandPlanRepository plans,
                              CommandKeySupport idempotency, Clock clock) {
        this.incidents = incidents;
        this.plans = plans;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    private Instant now() {
        return clock.instant();
    }

    /**
     * 创建指令计划：1~5 个有序步骤，stepKey 计划内唯一，初始 RUNNING。
     * 仅当前指挥人可创建；事件 REPORTED/RESOLVED/CLOSED 不允许。
     */
    @Transactional
    public PlanView createPlan(String incidentKey, String actor, String requestId,
                               PlanCreateRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        final String operator = requireText(actor, "X-Actor-Id");
        final String requestRef = requireText(requestId, "X-Request-Id");
        List<PlanStepDefinition> definitions = validateDefinitions(req.steps());
        Incident incident = lockIncident(incidentKey);
        requireCommander(incident, operator);
        requireWritable(incident);
        String hash = hash(OP_CREATE, incidentKey, operator, requestRef, canonicalSteps(definitions));
        return idempotency.run(commandKey, OP_CREATE, hash, PlanView.class, () -> {
            Instant now = now();
            long planId = plans.insertPlan(new CommandPlan(0L, incident.id(), PlanStatus.RUNNING,
                    0L, operator, now, now));
            int index = 1;
            for (PlanStepDefinition definition : definitions) {
                plans.insertStep(new CommandPlanStep(0L, planId, incident.id(), index++,
                        definition.stepKey(), definition.effectDesc(),
                        definition.compensationDesc(), PlanStepStatus.PENDING,
                        null, null, null, null, now, now));
            }
            return toView(incident.incidentKey(),
                    plans.findPlanById(planId).orElseThrow(),
                    plans.listSteps(planId));
        });
    }

    /**
     * 显式执行步骤：仅 RUNNING 计划的当前下一步（序号最小的 PENDING 步骤）可执行，
     * 原子置 APPLIED；再次执行旧成功步骤、跳步或取消后执行均 409。
     * 全部步骤 APPLIED 后计划转 COMPLETED，否则保持 RUNNING。
     */
    @Transactional
    public PlanView executeStep(String incidentKey, long planId, String stepKey, String actor,
                                String requestId, PlanStepExecuteRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        final String operator = requireText(actor, "X-Actor-Id");
        final String requestRef = requireText(requestId, "X-Request-Id");
        final String targetStepKey = requireText(stepKey, "stepKey");
        Incident incident = lockIncident(incidentKey);
        requireCommander(incident, operator);
        CommandPlan plan = requirePlan(incident, planId);
        List<CommandPlanStep> steps = plans.listSteps(planId);
        CommandPlanStep step = requireStep(plan, steps, targetStepKey);
        String hash = hash(OP_EXECUTE, String.valueOf(planId), targetStepKey, operator, requestRef,
                versionPart(req.expectedVersion()));
        return idempotency.run(commandKey, OP_EXECUTE, hash, PlanView.class, () -> {
            checkVersion(plan, req.expectedVersion());
            if (plan.status() != PlanStatus.RUNNING) {
                throw ApiException.conflict("计划状态为 " + plan.status() + "，不能再执行步骤");
            }
            String nextKey = firstByStatus(steps, PlanStepStatus.PENDING)
                    .map(CommandPlanStep::stepKey).orElse(null);
            if (nextKey == null || !nextKey.equals(targetStepKey)) {
                throw ApiException.conflict("只能执行当前下一步 " + nextKey
                        + "，不允许跳步或重复执行旧步骤: " + targetStepKey);
            }
            Instant now = now();
            if (plans.markApplied(step.id(), operator, now) == 0) {
                throw ApiException.conflict("步骤已被并发执行，不能重复记效果: " + targetStepKey);
            }
            List<CommandPlanStep> refreshed = plans.listSteps(planId);
            boolean allApplied = refreshed.stream()
                    .allMatch(s -> s.status() != PlanStepStatus.PENDING);
            long newVersion = plan.version() + 1;
            plans.updatePlanStatus(planId,
                    allApplied ? PlanStatus.COMPLETED : PlanStatus.RUNNING, newVersion, now);
            return toView(incident.incidentKey(),
                    plans.findPlanById(planId).orElseThrow(), refreshed);
        });
    }

    /**
     * 取消计划：仅 RUNNING/COMPLETED 可取消，原子进入 COMPENSATING；
     * 已 COMPENSATING/CANCELLED 重复取消返回 409。取消后未执行（PENDING）步骤不再执行，
     * 已执行（APPLIED）步骤等待逆序补偿；若没有任何已执行步骤，直接 CANCELLED。
     */
    @Transactional
    public PlanView cancelPlan(String incidentKey, long planId, String actor,
                               String requestId, PlanCancelRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        final String operator = requireText(actor, "X-Actor-Id");
        final String requestRef = requireText(requestId, "X-Request-Id");
        Incident incident = lockIncident(incidentKey);
        requireCommander(incident, operator);
        CommandPlan plan = requirePlan(incident, planId);
        String hash = hash(OP_CANCEL, String.valueOf(planId), operator, requestRef,
                versionPart(req.expectedVersion()));
        return idempotency.run(commandKey, OP_CANCEL, hash, PlanView.class, () -> {
            checkVersion(plan, req.expectedVersion());
            if (plan.status() != PlanStatus.RUNNING && plan.status() != PlanStatus.COMPLETED) {
                throw ApiException.conflict("仅 RUNNING/COMPLETED 计划可取消，当前状态: "
                        + plan.status());
            }
            List<CommandPlanStep> steps = plans.listSteps(planId);
            boolean anyApplied = steps.stream()
                    .anyMatch(s -> s.status() == PlanStepStatus.APPLIED);
            Instant now = now();
            PlanStatus target = anyApplied ? PlanStatus.COMPENSATING : PlanStatus.CANCELLED;
            plans.updatePlanStatus(planId, target, plan.version() + 1, now);
            return toView(incident.incidentKey(),
                    plans.findPlanById(planId).orElseThrow(), steps);
        });
    }

    /**
     * 逐项补偿：仅 COMPENSATING 计划可补偿，且 stepKey 必须是当前序号最大的 APPLIED 步骤
     * （严格逆序，跳步 409）。evidence 非空表示成功：记补偿证据、步骤转 COMPENSATED，
     * 全部已执行步骤补偿完后计划转 CANCELLED；failureDiagnostic 非空表示失败：
     * 只追加失败诊断、步骤保持 APPLIED 且计划仍 COMPENSATING，允许换新 commandKey 重试。
     * 交接后仅新指挥可补偿，旧指挥 403（由 requireCommander 先于幂等重放保证）。
     */
    @Transactional
    public PlanView compensateStep(String incidentKey, long planId, String stepKey, String actor,
                                   String requestId, PlanCompensateRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        final String operator = requireText(actor, "X-Actor-Id");
        final String requestRef = requireText(requestId, "X-Request-Id");
        final String targetStepKey = requireText(stepKey, "stepKey");
        boolean succeed = req.evidence() != null && !req.evidence().isBlank();
        boolean fail = req.failureDiagnostic() != null && !req.failureDiagnostic().isBlank();
        if (succeed == fail) {
            throw ApiException.badRequest("evidence 与 failureDiagnostic 必须且只能提供一个");
        }
        final String evidence = succeed ? req.evidence().strip() : null;
        final String diagnostic = fail ? req.failureDiagnostic().strip() : null;
        Incident incident = lockIncident(incidentKey);
        requireCommander(incident, operator);
        CommandPlan plan = requirePlan(incident, planId);
        List<CommandPlanStep> steps = plans.listSteps(planId);
        CommandPlanStep step = requireStep(plan, steps, targetStepKey);
        String hash = hash(OP_COMPENSATE, String.valueOf(planId), targetStepKey, operator, requestRef,
                versionPart(req.expectedVersion()), succeed ? "SUCCESS:" + evidence
                        : "FAILURE:" + diagnostic);
        return idempotency.run(commandKey, OP_COMPENSATE, hash, PlanView.class, () -> {
            checkVersion(plan, req.expectedVersion());
            if (plan.status() != PlanStatus.COMPENSATING) {
                throw ApiException.conflict("仅 COMPENSATING 计划可补偿，当前状态: "
                        + plan.status());
            }
            String compensatingKey = lastByStatus(steps, PlanStepStatus.APPLIED)
                    .map(CommandPlanStep::stepKey).orElse(null);
            if (compensatingKey == null || !compensatingKey.equals(targetStepKey)) {
                throw ApiException.conflict("只能补偿当前最后一个已执行步骤 " + compensatingKey
                        + "，不允许跳步: " + targetStepKey);
            }
            Instant now = now();
            int attemptNo = plans.countCompensationsByStep(step.id()) + 1;
            if (succeed) {
                if (plans.markCompensated(step.id(), operator, now) == 0) {
                    throw ApiException.conflict("步骤已被并发补偿: " + targetStepKey);
                }
                plans.insertCompensation(new PlanCompensation(0L, planId, step.id(),
                        step.stepIndex(), attemptNo, CompensationStatus.SUCCEEDED,
                        evidence, null, operator, requestRef, now));
                List<CommandPlanStep> refreshed = plans.listSteps(planId);
                boolean stillApplied = refreshed.stream()
                        .anyMatch(s -> s.status() == PlanStepStatus.APPLIED);
                plans.updatePlanStatus(planId,
                        stillApplied ? PlanStatus.COMPENSATING : PlanStatus.CANCELLED,
                        plan.version() + 1, now);
                return toView(incident.incidentKey(),
                        plans.findPlanById(planId).orElseThrow(), refreshed);
            }
            // 失败：仅保存失败诊断，不改变步骤 APPLIED 与计划 COMPENSATING，版本也不推进
            plans.insertCompensation(new PlanCompensation(0L, planId, step.id(),
                    step.stepIndex(), attemptNo, CompensationStatus.FAILED,
                    null, diagnostic, operator, requestRef, now));
            return toView(incident.incidentKey(), plan, plans.listSteps(planId));
        });
    }

    /** 查询单张计划（含步骤效果与每次补偿证据/诊断），只读。 */
    @Transactional(readOnly = true)
    public PlanView getPlan(String incidentKey, long planId) {
        Incident incident = incidents.findByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
        CommandPlan plan = plans.findPlanById(planId)
                .orElseThrow(() -> ApiException.notFound("指令计划不存在: " + planId));
        if (plan.incidentId() != incident.id()) {
            throw ApiException.notFound("指令计划不存在: " + planId);
        }
        return toView(incident.incidentKey(), plan, plans.listSteps(planId));
    }

    /** 查询事件全部指令计划（只读），按创建顺序返回。 */
    @Transactional(readOnly = true)
    public List<PlanView> listPlans(String incidentKey) {
        Incident incident = incidents.findByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
        List<PlanView> views = new ArrayList<>();
        for (CommandPlan plan : plans.listPlansByIncident(incident.id())) {
            views.add(toView(incident.incidentKey(), plan, plans.listSteps(plan.id())));
        }
        return views;
    }

    /** 校验步骤定义：1~5 个、字段非空、stepKey 计划内不重复，返回去空白后的有序定义。 */
    private List<PlanStepDefinition> validateDefinitions(List<PlanStepDefinition> raw) {
        if (raw == null || raw.isEmpty()) {
            throw ApiException.badRequest("steps 至少包含 1 个步骤");
        }
        if (raw.size() > MAX_STEPS_PER_PLAN) {
            throw ApiException.badRequest("每张指令计划最多 " + MAX_STEPS_PER_PLAN + " 个步骤");
        }
        List<PlanStepDefinition> definitions = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (PlanStepDefinition d : raw) {
            if (d == null) {
                throw ApiException.badRequest("步骤定义不能为空");
            }
            String stepKey = requireText(d.stepKey(), "stepKey");
            String effectDesc = requireText(d.effectDesc(), "effectDesc");
            String compensationDesc = requireText(d.compensationDesc(), "compensationDesc");
            if (!seen.add(stepKey)) {
                throw ApiException.badRequest("stepKey 在计划内重复: " + stepKey);
            }
            definitions.add(new PlanStepDefinition(stepKey, effectDesc, compensationDesc));
        }
        return definitions;
    }

    private Incident lockIncident(String incidentKey) {
        requireText(incidentKey, "incidentKey");
        return incidents.lockByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
    }

    /** 校验当前指挥权：非当前指挥人返回 403（先于幂等重放执行）。 */
    private static void requireCommander(Incident incident, String actor) {
        if (incident.commander() == null || !incident.commander().equals(actor)) {
            throw ApiException.forbidden("只有当前指挥人 "
                    + (incident.commander() == null ? "(无)" : incident.commander())
                    + " 能操作指令计划");
        }
    }

    /** 计划类写操作要求事件已接管且未进入终态 RESOLVED/CLOSED。 */
    private static void requireWritable(Incident incident) {
        if (incident.status() == IncidentStatus.REPORTED) {
            throw ApiException.illegalTransition("尚未接管的事件不能创建指令计划");
        }
        if (incident.status() == IncidentStatus.RESOLVED
                || incident.status() == IncidentStatus.CLOSED) {
            throw ApiException.illegalTransition(incident.status() + " 状态不允许操作指令计划");
        }
    }

    private CommandPlan requirePlan(Incident incident, long planId) {
        CommandPlan plan = plans.findPlanById(planId)
                .orElseThrow(() -> ApiException.notFound("指令计划不存在: " + planId));
        if (plan.incidentId() != incident.id()) {
            throw ApiException.notFound("指令计划不存在: " + planId);
        }
        return plan;
    }

    private CommandPlanStep requireStep(CommandPlan plan, List<CommandPlanStep> steps,
                                        String stepKey) {
        return steps.stream().filter(s -> s.stepKey().equals(stepKey)).findFirst()
                .orElseThrow(() -> ApiException.notFound("步骤不存在于计划 "
                        + plan.id() + ": " + stepKey));
    }

    /** 乐观版本校验：携带 expectedVersion 且与服务端版本不一致时 409；null 不校验。 */
    private static void checkVersion(CommandPlan plan, Long expectedVersion) {
        if (expectedVersion != null && expectedVersion != plan.version()) {
            throw ApiException.conflict("计划版本已变化，期望 " + expectedVersion
                    + "，实际 " + plan.version());
        }
    }

    private static java.util.Optional<CommandPlanStep> firstByStatus(List<CommandPlanStep> steps,
                                                                     PlanStepStatus status) {
        return steps.stream().filter(s -> s.status() == status).findFirst();
    }

    private static java.util.Optional<CommandPlanStep> lastByStatus(List<CommandPlanStep> steps,
                                                                    PlanStepStatus status) {
        return steps.stream().filter(s -> s.status() == status)
                .reduce((first, second) -> second);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest(field + " 不能为空");
        }
        return value.strip();
    }

    /** 期望版本参数片段：null 规范化为固定标记，十进制按值字符串。 */
    private static String versionPart(Long expectedVersion) {
        return expectedVersion == null ? "<any>" : expectedVersion.toString();
    }

    /** 步骤清单规范化片段：按数组顺序逐段编码序号、stepKey、效果、补偿说明。 */
    private static String canonicalSteps(List<PlanStepDefinition> definitions) {
        StringBuilder sb = new StringBuilder();
        int index = 1;
        for (PlanStepDefinition d : definitions) {
            sb.append('#').append(index++)
                    .append("[key=").append(d.stepKey())
                    .append(";effect=").append(d.effectDesc())
                    .append(";comp=").append(d.compensationDesc()).append(']');
        }
        return sb.toString();
    }

    /**
     * 规范化参数摘要：逐段以“长度:内容”编码后做 SHA-256，十进制按值字符串、文本原样参与，
     * 长度前缀避免跨段拼接歧义，结构性区分操作、资源与全参数。
     */
    private static String hash(String... parts) {
        StringBuilder canonical = new StringBuilder();
        for (String part : parts) {
            String value = part == null ? "" : part;
            canonical.append(value.length()).append(':').append(value).append(';');
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] out = digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(out);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private PlanView toView(String incidentKey, CommandPlan plan, List<CommandPlanStep> steps) {
        List<PlanStepView> stepViews = new ArrayList<>();
        String nextStepKey = null;
        String compensationStepKey = null;
        for (CommandPlanStep step : steps) {
            List<PlanCompensation> attempts = plans.listCompensationsByStep(step.id());
            List<CompensationAttemptView> attemptViews = attempts.stream()
                    .map(a -> new CompensationAttemptView(a.id(), a.stepIndex(), a.attemptNo(),
                            a.status().name(), a.evidence(), a.failureDiagnostic(), a.actor(),
                            a.requestId(), a.createdAt()))
                    .toList();
            stepViews.add(new PlanStepView(step.stepIndex(), step.stepKey(), step.effectDesc(),
                    step.compensationDesc(), step.status().name(), step.appliedBy(),
                    step.appliedAt(), step.compensatedBy(), step.compensatedAt(), attemptViews));
        }
        if (plan.status() == PlanStatus.RUNNING) {
            nextStepKey = firstByStatus(steps, PlanStepStatus.PENDING)
                    .map(CommandPlanStep::stepKey).orElse(null);
        }
        if (plan.status() == PlanStatus.COMPENSATING) {
            compensationStepKey = lastByStatus(steps, PlanStepStatus.APPLIED)
                    .map(CommandPlanStep::stepKey).orElse(null);
        }
        return new PlanView(plan.id(), incidentKey, plan.status().name(), plan.version(),
                plan.createdBy(), plan.createdAt(), plan.updatedAt(), stepViews, nextStepKey,
                compensationStepKey);
    }
}
