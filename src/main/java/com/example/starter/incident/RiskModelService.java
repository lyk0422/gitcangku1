package com.example.starter.incident;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.example.starter.incident.ResidualRisk.Fraction;
import com.example.starter.incident.ResidualRisk.Ratio;
import com.example.starter.incident.dto.Requests.BarrierRegisterRequest;
import com.example.starter.incident.dto.Requests.BarrierRevokeRequest;
import com.example.starter.incident.dto.Requests.BarrierUpdateRequest;
import com.example.starter.incident.dto.Requests.RiskModelInitRequest;
import com.example.starter.incident.dto.Responses.BarrierView;
import com.example.starter.incident.dto.Responses.ContainmentBarrierView;
import com.example.starter.incident.dto.Responses.ContainmentSnapshotView;
import com.example.starter.incident.dto.Responses.RiskModelView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 残余风险模型服务（合成模型，不连接真实安全设备）。
 * 残余 = R × ∏(1 - p/q)，仅计任务 DONE 且未撤销的屏障，精确有理数比较，不输出舍入值。
 * 并发约定：所有写接口先 SELECT ... FOR UPDATE 锁定事件行，同事务内完成
 * 幂等键占位、业务校验与写入；权限（当前指挥人）校验先于 requestId 重放。
 * 版本约定：初始化版本置 1，屏障登记/修改/撤销每次成功加一；修改与撤销须携带
 * 期望版本；事件已 CONTAINED 时，修改/撤销的后态残余超限返回 409；CLOSED 不接受新配置。
 */
@Service
public class RiskModelService {

    /** 每事件未撤销屏障上限。 */
    private static final int MAX_BARRIERS = 5;

    /** 削减率分子/分母上限（p、q 均不超 1000）。 */
    private static final int MAX_RATIO_PART = 1000;

    /** 初始风险 R 上限。 */
    private static final int MAX_INITIAL_RISK = 1000;

    private final IncidentRepository incidents;
    private final IncidentTaskRepository tasks;
    private final RiskModelRepository riskModels;
    private final IdempotentExecutor idempotency;
    private final Clock clock;

    public RiskModelService(IncidentRepository incidents, IncidentTaskRepository tasks,
                            RiskModelRepository riskModels, IdempotentExecutor idempotency,
                            Clock clock) {
        this.incidents = incidents;
        this.tasks = tasks;
        this.riskModels = riskModels;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    private Instant now() {
        return clock.instant();
    }

    /**
     * 初始化风险模型：仅当前指挥人、仅首次；R 为 1~1000 整数，L 非负且小于 R。
     */
    @Transactional
    public RiskModelView initModel(String incidentKey, String actor, RiskModelInitRequest req) {
        String requestId = requireText(req.requestId(), "requestId");
        String operator = requireText(actor, "X-Actor-Id");
        int initialRisk = requireRange(req.initialRisk(), "initialRisk", 1, MAX_INITIAL_RISK);
        int residualLimit = requireRange(req.residualLimit(), "residualLimit", 0, MAX_INITIAL_RISK);
        if (residualLimit >= initialRisk) {
            throw ApiException.badRequest("residualLimit 必须小于 initialRisk");
        }
        Incident incident = lockIncident(incidentKey);
        requireCommander(incident, operator);
        return idempotency.execute(requestId, "risk_init",
                IdempotentExecutor.hash(incidentKey, actor, String.valueOf(initialRisk),
                        String.valueOf(residualLimit)),
                RiskModelView.class, () -> {
                    requireNotClosed(incident);
                    if (riskModels.findByIncident(incident.id()).isPresent()) {
                        throw ApiException.conflict("风险模型已初始化: " + incidentKey);
                    }
                    Instant now = now();
                    riskModels.insertModel(new RiskModel(0L, incident.id(), initialRisk,
                            residualLimit, 1L, null, null, null, now, now));
                    return toView(incident);
                });
    }

    /**
     * 登记屏障：绑定本事件 OPEN 任务，削减率 p/q（0&lt;=p&lt;=q&lt;=1000，q&gt;=1），
     * 未撤销屏障至多 5 个；barrierKey 模型内唯一，成功版本加一。
     */
    @Transactional
    public RiskModelView registerBarrier(String incidentKey, String actor, BarrierRegisterRequest req) {
        String requestId = requireText(req.requestId(), "requestId");
        String operator = requireText(actor, "X-Actor-Id");
        String barrierKey = requireText(req.barrierKey(), "barrierKey");
        String taskKey = requireText(req.taskKey(), "taskKey");
        int reduceP = requireRange(req.reduceP(), "reduceP", 0, MAX_RATIO_PART);
        int reduceQ = requireRange(req.reduceQ(), "reduceQ", 1, MAX_RATIO_PART);
        if (reduceP > reduceQ) {
            throw ApiException.badRequest("reduceP 不能大于 reduceQ");
        }
        Incident incident = lockIncident(incidentKey);
        requireCommander(incident, operator);
        return idempotency.execute(requestId, "barrier_register",
                IdempotentExecutor.hash(incidentKey, actor, req.barrierKey(), req.taskKey(),
                        String.valueOf(reduceP), String.valueOf(reduceQ)),
                RiskModelView.class, () -> {
                    requireNotClosed(incident);
                    RiskModel model = requireModel(incident.id());
                    if (riskModels.findBarrier(model.id(), barrierKey).isPresent()) {
                        throw ApiException.conflict("barrierKey 已存在: " + barrierKey);
                    }
                    IncidentTask task = tasks.findByKey(incident.id(), taskKey)
                            .orElseThrow(() -> ApiException.notFound("任务不存在: " + taskKey));
                    if (task.status() != TaskStatus.OPEN) {
                        throw ApiException.conflict("仅 OPEN 任务可绑定屏障，任务 " + taskKey
                                + " 当前状态: " + task.status());
                    }
                    if (riskModels.countActiveBarriers(model.id()) >= MAX_BARRIERS) {
                        throw ApiException.conflict("每事件最多登记 " + MAX_BARRIERS + " 个未撤销屏障");
                    }
                    Instant now = now();
                    riskModels.insertBarrier(new RiskBarrier(0L, model.id(), barrierKey, taskKey,
                            reduceP, reduceQ, false, now, now));
                    riskModels.updateVersion(model.id(), model.version() + 1, now);
                    return toView(incident);
                });
    }

    private RiskModel requireModel(long incidentId) {
        return riskModels.findByIncident(incidentId)
                .orElseThrow(() -> ApiException.conflict("风险模型未初始化，请先设置初始风险与残余上限"));
    }

    private static void requireNotClosed(Incident incident) {
        if (incident.status() == IncidentStatus.CLOSED) {
            throw ApiException.illegalTransition("事件已关闭，不接受新配置");
        }
    }

    private static void requireVersion(RiskModel model, long expectedVersion) {
        if (model.version() != expectedVersion) {
            throw ApiException.conflict("模型版本不匹配：期望 " + expectedVersion
                    + "，当前 " + model.version());
        }
    }

    private boolean isTaskDone(long incidentId, String taskKey) {
        IncidentTask task = tasks.findByKey(incidentId, taskKey)
                .orElseThrow(() -> new IllegalStateException("屏障绑定任务不存在: " + taskKey));
        return task.status() == TaskStatus.DONE;
    }

    /**
     * 当前有效屏障（任务 DONE 且未撤销）的削减率列表。
     */
    private List<Ratio> effectiveRatios(long incidentId, long modelId) {
        List<Ratio> ratios = new ArrayList<>();
        for (RiskBarrier barrier : riskModels.listBarriers(modelId)) {
            if (!barrier.revoked() && isTaskDone(incidentId, barrier.taskKey())) {
                ratios.add(new Ratio(barrier.reduceP(), barrier.reduceQ()));
            }
        }
        return ratios;
    }

    private Fraction currentResidual(long incidentId, RiskModel model) {
        return ResidualRisk.residual(model.initialRisk(), effectiveRatios(incidentId, model.id()));
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

    private static int requireRange(Integer value, String field, int min, int max) {
        if (value == null) {
            throw ApiException.badRequest(field + " 不能为空");
        }
        if (value < min || value > max) {
            throw ApiException.badRequest(field + " 必须在 " + min + "~" + max + " 之间");
        }
        return value;
    }

    private static long requireExpectedVersion(Long value) {
        if (value == null) {
            throw ApiException.badRequest("expectedVersion 不能为空");
        }
        return value;
    }

    /**
     * 组装模型视图：屏障任务状态按查询时当前状态计算，残余为精确分数（不舍入）。
     */
    private RiskModelView toView(Incident incident) {
        RiskModel model = requireModel(incident.id());
        List<BarrierView> barriers = new ArrayList<>();
        List<Ratio> effective = new ArrayList<>();
        for (RiskBarrier barrier : riskModels.listBarriers(model.id())) {
            IncidentTask task = tasks.findByKey(incident.id(), barrier.taskKey())
                    .orElseThrow(() -> new IllegalStateException(
                            "屏障绑定任务不存在: " + barrier.taskKey()));
            boolean counts = !barrier.revoked() && task.status() == TaskStatus.DONE;
            if (counts) {
                effective.add(new Ratio(barrier.reduceP(), barrier.reduceQ()));
            }
            barriers.add(new BarrierView(barrier.barrierKey(), barrier.taskKey(),
                    task.status().name(), barrier.reduceP(), barrier.reduceQ(), barrier.revoked(),
                    counts));
        }
        Fraction residual = ResidualRisk.residual(model.initialRisk(), effective);
        ContainmentSnapshotView containment = null;
        if (model.containedAt() != null) {
            List<ContainmentBarrierView> frozen = riskModels.listContainmentBarriers(model.id())
                    .stream()
                    .map(b -> new ContainmentBarrierView(b.barrierKey(), b.taskKey(), b.taskStatus(),
                            b.reduceP(), b.reduceQ()))
                    .toList();
            containment = new ContainmentSnapshotView(model.containedAt(),
                    model.frozenResidualNum() + "/" + model.frozenResidualDen(), frozen);
        }
        return new RiskModelView(incident.incidentKey(), model.initialRisk(), model.residualLimit(),
                model.version(), residual.asString(),
                ResidualRisk.withinLimit(residual, model.residualLimit()), barriers, containment);
    }

    /**
     * 修改屏障削减率：携带期望版本，成功版本加一；
     * 事件已 CONTAINED 时后态残余超限返回 409（不能偷偷失去防护）。
     */
    @Transactional
    public RiskModelView updateBarrier(String incidentKey, String barrierKey, String actor,
                                       BarrierUpdateRequest req) {
        String requestId = requireText(req.requestId(), "requestId");
        String operator = requireText(actor, "X-Actor-Id");
        String barrierKeyText = requireText(barrierKey, "barrierKey");
        int reduceP = requireRange(req.reduceP(), "reduceP", 0, MAX_RATIO_PART);
        int reduceQ = requireRange(req.reduceQ(), "reduceQ", 1, MAX_RATIO_PART);
        if (reduceP > reduceQ) {
            throw ApiException.badRequest("reduceP 不能大于 reduceQ");
        }
        long expectedVersion = requireExpectedVersion(req.expectedVersion());
        Incident incident = lockIncident(incidentKey);
        requireCommander(incident, operator);
        return idempotency.execute(requestId, "barrier_update",
                IdempotentExecutor.hash(incidentKey, actor, barrierKey, String.valueOf(reduceP),
                        String.valueOf(reduceQ), String.valueOf(expectedVersion)),
                RiskModelView.class, () -> {
                    requireNotClosed(incident);
                    RiskModel model = requireModel(incident.id());
                    RiskBarrier barrier = riskModels.findBarrier(model.id(), barrierKeyText)
                            .orElseThrow(() -> ApiException.notFound("屏障不存在: " + barrierKeyText));
                    if (barrier.revoked()) {
                        throw ApiException.conflict("屏障已撤销，不能修改: " + barrierKeyText);
                    }
                    requireVersion(model, expectedVersion);
                    assertContainedLimit(incident, model, barrierKeyText,
                            new Ratio(reduceP, reduceQ), false);
                    Instant now = now();
                    riskModels.updateBarrierRatio(barrier.id(), reduceP, reduceQ, now);
                    riskModels.updateVersion(model.id(), model.version() + 1, now);
                    return toView(incident);
                });
    }

    /**
     * 撤销屏障：携带期望版本，成功版本加一；
     * 事件已 CONTAINED 时后态残余超限返回 409。
     */
    @Transactional
    public RiskModelView revokeBarrier(String incidentKey, String barrierKey, String actor,
                                       BarrierRevokeRequest req) {
        String requestId = requireText(req.requestId(), "requestId");
        String operator = requireText(actor, "X-Actor-Id");
        String barrierKeyText = requireText(barrierKey, "barrierKey");
        long expectedVersion = requireExpectedVersion(req.expectedVersion());
        Incident incident = lockIncident(incidentKey);
        requireCommander(incident, operator);
        return idempotency.execute(requestId, "barrier_revoke",
                IdempotentExecutor.hash(incidentKey, actor, barrierKey,
                        String.valueOf(expectedVersion)),
                RiskModelView.class, () -> {
                    requireNotClosed(incident);
                    RiskModel model = requireModel(incident.id());
                    RiskBarrier barrier = riskModels.findBarrier(model.id(), barrierKeyText)
                            .orElseThrow(() -> ApiException.notFound("屏障不存在: " + barrierKeyText));
                    if (barrier.revoked()) {
                        throw ApiException.conflict("屏障已撤销: " + barrierKeyText);
                    }
                    requireVersion(model, expectedVersion);
                    assertContainedLimit(incident, model, barrierKeyText, null, true);
                    Instant now = now();
                    riskModels.revokeBarrier(barrier.id(), now);
                    riskModels.updateVersion(model.id(), model.version() + 1, now);
                    return toView(incident);
                });
    }

    /**
     * 查询风险模型共享视图（任务完成取消、屏障配置、遏制及交接后均反映当前状态）。
     * 只读，不隐式写入。
     */
    @Transactional(readOnly = true)
    public RiskModelView getView(String incidentKey) {
        requireText(incidentKey, "incidentKey");
        Incident incident = incidents.findByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
        if (riskModels.findByIncident(incident.id()).isEmpty()) {
            throw ApiException.notFound("风险模型未初始化: " + incidentKey);
        }
        return toView(incident);
    }

    /**
     * 遏制门禁：模型存在时当前残余须不大于 L（精确有理数比较），否则 422；
     * 模型未初始化时不设门禁。须在锁定事件行的写事务内调用。
     */
    public void assertContainable(long incidentId) {
        var model = riskModels.findByIncident(incidentId);
        if (model.isEmpty()) {
            return;
        }
        Fraction residual = currentResidual(incidentId, model.get());
        if (!ResidualRisk.withinLimit(residual, model.get().residualLimit())) {
            throw ApiException.unprocessable("RESIDUAL_RISK_EXCEEDED",
                    "残余风险 " + residual.asString() + " 超过允许上限 "
                            + model.get().residualLimit());
        }
    }

    /**
     * 遏制冻结：记录遏制 UTC 时刻、冻结残余精确分数与当时有效屏障快照；
     * 之后的合法加强不回改该依据。须在锁定事件行的写事务内调用。
     */
    public void freezeContainment(long incidentId, Instant at) {
        var modelOpt = riskModels.findByIncident(incidentId);
        if (modelOpt.isEmpty()) {
            return;
        }
        RiskModel model = modelOpt.get();
        Fraction residual = currentResidual(incidentId, model);
        riskModels.freeze(model.id(), at, residual.num().longValueExact(),
                residual.den().longValueExact(), at);
        for (RiskBarrier barrier : riskModels.listBarriers(model.id())) {
            if (!barrier.revoked() && isTaskDone(incidentId, barrier.taskKey())) {
                riskModels.insertContainmentBarrier(new ContainmentBarrier(0L, model.id(),
                        barrier.barrierKey(), barrier.taskKey(), TaskStatus.DONE.name(),
                        barrier.reduceP(), barrier.reduceQ(), at));
            }
        }
    }

    /**
     * CONTAINED 门禁：事件已遏制时，屏障修改/撤销的后态残余仍须不大于 L，否则 409。
     * replacement 非空表示该屏障修改后的新比率；revoking 为 true 表示撤销该屏障。
     */
    private void assertContainedLimit(Incident incident, RiskModel model, String barrierKey,
                                      Ratio replacement, boolean revoking) {
        if (incident.status() != IncidentStatus.CONTAINED) {
            return;
        }
        List<Ratio> ratios = new ArrayList<>();
        for (RiskBarrier barrier : riskModels.listBarriers(model.id())) {
            boolean revoked = barrier.revoked()
                    || (revoking && barrier.barrierKey().equals(barrierKey));
            if (revoked) {
                continue;
            }
            Ratio ratio = replacement != null && barrier.barrierKey().equals(barrierKey)
                    ? replacement : new Ratio(barrier.reduceP(), barrier.reduceQ());
            if (isTaskDone(incident.id(), barrier.taskKey())) {
                ratios.add(ratio);
            }
        }
        Fraction residual = ResidualRisk.residual(model.initialRisk(), ratios);
        if (!ResidualRisk.withinLimit(residual, model.residualLimit())) {
            throw ApiException.conflict("事件已遏制，该变更会使残余风险 " + residual.asString()
                    + " 超过允许上限 " + model.residualLimit());
        }
    }
}
