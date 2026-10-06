package com.example.starter.incident;

import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.example.starter.incident.dto.Requests.BarrierRegisterRequest;
import com.example.starter.incident.dto.Requests.BarrierRevokeRequest;
import com.example.starter.incident.dto.Requests.BarrierUpdateRequest;
import com.example.starter.incident.dto.Requests.RiskModelSetupRequest;
import com.example.starter.incident.dto.Responses.BarrierView;
import com.example.starter.incident.dto.Responses.ContainmentSnapshotView;
import com.example.starter.incident.dto.Responses.RiskModelView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 合成残余风险模型服务（合成数据，不连接真实安全设备）。
 * 残余 = R × Π(1 - p/q)，仅计任务 DONE 且未撤销的屏障，以 BigInteger 精确有理数
 * 比较，不输出舍入小数值。并发约定：所有写接口先 SELECT ... FOR UPDATE 锁定事件行，
 * 模型版本检查、屏障变更与幂等键占位在同一事务提交，并发写按事务提交顺序生效。
 * 幂等约定：commandKey 全局唯一，同键同参重放首次响应，同键改参 409，失败不占键；
 * 当前指挥人权限校验先于幂等重放。任务完成/取消、屏障配置、遏制及交接共享同一
 * 模型视图（同一事件行锁保护，交接后由新指挥人接管同一模型）。
 */
@Service
public class IncidentRiskService {

    /** 每事件未撤销屏障上限。 */
    private static final int MAX_BARRIERS_PER_INCIDENT = 5;

    /** 初始风险 R 与削减率分子/分母的上限（均不超 1000）。 */
    private static final int MAX_RISK_VALUE = 1000;

    private final IncidentRepository incidents;
    private final IncidentTaskRepository tasks;
    private final RiskModelRepository riskModels;
    private final IdempotentCommands idempotent;
    private final Clock clock;

    public IncidentRiskService(IncidentRepository incidents, IncidentTaskRepository tasks,
                               RiskModelRepository riskModels, IdempotentCommands idempotent,
                               Clock clock) {
        this.incidents = incidents;
        this.tasks = tasks;
        this.riskModels = riskModels;
        this.idempotent = idempotent;
        this.clock = clock;
    }

    private Instant now() {
        return clock.instant();
    }

    /**
     * 首次设置残余风险模型：仅当前指挥人；R 为 1~1000 整数，L 为非负整数且小于 R；
     * 仅可设置一次（重复设置 409），CLOSED 后禁止写入。
     */
    @Transactional
    public RiskModelView setupModel(String incidentKey, String actor, RiskModelSetupRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        requireActor(actor);
        int initialRisk = requireRange(req.initialRisk(), "initialRisk", 1, MAX_RISK_VALUE);
        int residualLimit = requireRange(req.residualLimit(), "residualLimit", 0, MAX_RISK_VALUE);
        if (residualLimit >= initialRisk) {
            throw ApiException.badRequest("residualLimit 必须小于 initialRisk");
        }
        Incident incident = lockIncident(incidentKey);
        requireCommander(incident, actor);
        return idempotent.run(commandKey, "risk_setup",
                IdempotentCommands.hash(incidentKey, actor, String.valueOf(initialRisk),
                        String.valueOf(residualLimit)),
                RiskModelView.class, () -> {
                    requireNotClosed(incident);
                    if (riskModels.findModel(incident.id()).isPresent()) {
                        throw ApiException.conflict("风险模型已设置，不可重复设置");
                    }
                    Instant now = now();
                    riskModels.insertModel(new RiskModel(0L, incident.id(), initialRisk,
                            residualLimit, 0L, now, now));
                    return toView(incident);
                });
    }

    /**
     * 登记屏障：仅当前指挥人；须先设置模型；barrierKey 事件内唯一；
     * 绑定任务须为本事件 OPEN 任务；未撤销屏障至多 5 个；CLOSED 后禁止写入。
     */
    @Transactional
    public RiskModelView registerBarrier(String incidentKey, String actor, BarrierRegisterRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        requireActor(actor);
        String barrierKey = requireText(req.barrierKey(), "barrierKey");
        String taskKey = requireText(req.taskKey(), "taskKey");
        int numerator = requireRange(req.numerator(), "numerator", 0, MAX_RISK_VALUE);
        int denominator = requireRange(req.denominator(), "denominator", 1, MAX_RISK_VALUE);
        requireRatio(numerator, denominator);
        Incident incident = lockIncident(incidentKey);
        requireCommander(incident, actor);
        return idempotent.run(commandKey, "barrier_register",
                IdempotentCommands.hash(incidentKey, actor, barrierKey, taskKey,
                        String.valueOf(numerator), String.valueOf(denominator)),
                RiskModelView.class, () -> {
                    requireNotClosed(incident);
                    requireModel(incident.id());
                    if (riskModels.findBarrier(incident.id(), barrierKey).isPresent()) {
                        throw ApiException.conflict("barrierKey 已存在: " + barrierKey);
                    }
                    if (riskModels.countActiveBarriers(incident.id()) >= MAX_BARRIERS_PER_INCIDENT) {
                        throw ApiException.conflict(
                                "每事件最多登记 " + MAX_BARRIERS_PER_INCIDENT + " 个未撤销屏障");
                    }
                    requireOpenTask(incident.id(), taskKey);
                    Instant now = now();
                    riskModels.insertBarrier(new RiskBarrier(0L, incident.id(), barrierKey, taskKey,
                            numerator, denominator, false, now, now));
                    return toView(incident);
                });
    }

    /**
     * 修改屏障：仅当前指挥人；屏障须存在且未撤销；expectedVersion 须等于模型当前版本，
     * 成功后版本加一；换绑任务须为本事件 OPEN 任务；若事件已 CONTAINED 且变更后
     * 残余超限则 409（不能偷偷失去防护）；CLOSED 后禁止写入。
     */
    @Transactional
    public RiskModelView updateBarrier(String incidentKey, String barrierKey, String actor,
                                       BarrierUpdateRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        requireActor(actor);
        String key = requireText(barrierKey, "barrierKey");
        String taskKey = requireText(req.taskKey(), "taskKey");
        int numerator = requireRange(req.numerator(), "numerator", 0, MAX_RISK_VALUE);
        int denominator = requireRange(req.denominator(), "denominator", 1, MAX_RISK_VALUE);
        requireRatio(numerator, denominator);
        long expectedVersion = requireVersion(req.expectedVersion());
        Incident incident = lockIncident(incidentKey);
        requireCommander(incident, actor);
        return idempotent.run(commandKey, "barrier_update",
                IdempotentCommands.hash(incidentKey, actor, key, taskKey, String.valueOf(numerator),
                        String.valueOf(denominator), String.valueOf(expectedVersion)),
                RiskModelView.class, () -> {
                    requireNotClosed(incident);
                    RiskModel model = requireModel(incident.id());
                    RiskBarrier barrier = requireActiveBarrier(incident.id(), key);
                    checkVersion(model, expectedVersion);
                    if (!barrier.taskKey().equals(taskKey)) {
                        requireOpenTask(incident.id(), taskKey);
                    }
                    assertContainedStillSafe(incident, model, key, taskKey, numerator,
                            denominator, false);
                    Instant now = now();
                    riskModels.updateBarrier(barrier.id(), taskKey, numerator, denominator, now);
                    riskModels.bumpVersion(model.id(), now);
                    return toView(incident);
                });
    }

    /**
     * 撤销屏障：仅当前指挥人；屏障须存在且未撤销；expectedVersion 须等于模型当前版本，
     * 成功后版本加一；若事件已 CONTAINED 且撤销后残余超限则 409；CLOSED 后禁止写入。
     */
    @Transactional
    public RiskModelView revokeBarrier(String incidentKey, String barrierKey, String actor,
                                       BarrierRevokeRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        requireActor(actor);
        String key = requireText(barrierKey, "barrierKey");
        long expectedVersion = requireVersion(req.expectedVersion());
        Incident incident = lockIncident(incidentKey);
        requireCommander(incident, actor);
        return idempotent.run(commandKey, "barrier_revoke",
                IdempotentCommands.hash(incidentKey, actor, key, String.valueOf(expectedVersion)),
                RiskModelView.class, () -> {
                    requireNotClosed(incident);
                    RiskModel model = requireModel(incident.id());
                    RiskBarrier barrier = requireActiveBarrier(incident.id(), key);
                    checkVersion(model, expectedVersion);
                    assertContainedStillSafe(incident, model, key, null, 0, 1, true);
                    Instant now = now();
                    riskModels.revokeBarrier(barrier.id(), now);
                    riskModels.bumpVersion(model.id(), now);
                    return toView(incident);
                });
    }

    /**
     * 查询残余风险模型视图（含当前残余精确分数、可遏制判定与遏制冻结快照）。
     * 只读，不隐式写入。
     */
    @Transactional(readOnly = true)
    public RiskModelView getModel(String incidentKey) {
        Incident incident = incidents.findByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
        if (riskModels.findModel(incident.id()).isEmpty()) {
            throw ApiException.notFound("风险模型尚未设置: " + incidentKey);
        }
        return toView(incident);
    }

    /**
     * 遏制门禁与快照冻结：由状态流转同事务调用（事件行已锁定）。
     * 未建立模型的事件按原逻辑放行；已建立模型时残余须不大于 L，否则 422；
     * 通过后将当时未撤销屏障的身份、比率与任务状态写入冻结快照（只写一次，
     * 后续合法加强不改旧依据）。
     */
    public void assertContainableAndFreeze(Incident incident) {
        var modelOpt = riskModels.findModel(incident.id());
        if (modelOpt.isEmpty()) {
            return;
        }
        RiskModel model = modelOpt.get();
        List<RiskBarrier> barriers = riskModels.listBarriers(incident.id());
        Map<String, TaskStatus> statuses = taskStatuses(incident.id());
        BigInteger[] residual = residualFraction(model.initialRisk(), barriers, statuses);
        if (!withinLimit(model.residualLimit(), residual[0], residual[1])) {
            throw ApiException.illegalTransition("残余风险 " + residual[0] + "/" + residual[1]
                    + " 超过允许上限 " + model.residualLimit() + "，不能进入 CONTAINED");
        }
        Instant now = now();
        for (RiskBarrier barrier : barriers) {
            if (barrier.revoked()) {
                continue;
            }
            TaskStatus status = statuses.getOrDefault(barrier.taskKey(), TaskStatus.OPEN);
            riskModels.insertSnapshot(new ContainmentSnapshot(0L, incident.id(),
                    barrier.barrierKey(), barrier.taskKey(), status.name(),
                    barrier.numerator(), barrier.denominator(), now));
        }
    }

    /**
     * CONTAINED 后屏障变更的后态校验：按假设变更后的屏障集合与当前任务状态
     * 重算残余，超过 L 则 409（不能偷偷失去防护）。事件未处于 CONTAINED 时不校验。
     */
    private void assertContainedStillSafe(Incident incident, RiskModel model, String barrierKey,
                                          String taskKey, int numerator, int denominator,
                                          boolean revoke) {
        if (incident.status() != IncidentStatus.CONTAINED) {
            return;
        }
        Map<String, TaskStatus> statuses = taskStatuses(incident.id());
        BigInteger num = BigInteger.valueOf(model.initialRisk());
        BigInteger den = BigInteger.ONE;
        for (RiskBarrier barrier : riskModels.listBarriers(incident.id())) {
            if (barrier.revoked()) {
                continue;
            }
            String effectiveTaskKey = barrier.taskKey();
            int p = barrier.numerator();
            int q = barrier.denominator();
            if (barrier.barrierKey().equals(barrierKey)) {
                if (revoke) {
                    continue;
                }
                effectiveTaskKey = taskKey;
                p = numerator;
                q = denominator;
            }
            if (statuses.get(effectiveTaskKey) == TaskStatus.DONE) {
                num = num.multiply(BigInteger.valueOf(q - p));
                den = den.multiply(BigInteger.valueOf(q));
            }
        }
        if (!withinLimit(model.residualLimit(), num, den)) {
            throw ApiException.conflict("事件已处于 CONTAINED，变更后残余风险将超过允许上限 "
                    + model.residualLimit());
        }
    }

    /**
     * 精确残余分数：R × Π(1 - p/q)，仅计任务 DONE 且未撤销的屏障，
     * 返回约分后的 [分子, 分母]，不做任何舍入。
     */
    private static BigInteger[] residualFraction(int initialRisk, List<RiskBarrier> barriers,
                                                 Map<String, TaskStatus> statuses) {
        BigInteger num = BigInteger.valueOf(initialRisk);
        BigInteger den = BigInteger.ONE;
        for (RiskBarrier barrier : barriers) {
            if (barrier.revoked() || statuses.get(barrier.taskKey()) != TaskStatus.DONE) {
                continue;
            }
            num = num.multiply(BigInteger.valueOf(barrier.denominator() - barrier.numerator()));
            den = den.multiply(BigInteger.valueOf(barrier.denominator()));
        }
        BigInteger gcd = num.gcd(den);
        return new BigInteger[]{num.divide(gcd), den.divide(gcd)};
    }

    /**
     * 精确有理数比较：num/den ≤ limit ⟺ num ≤ limit × den。
     */
    private static boolean withinLimit(int limit, BigInteger num, BigInteger den) {
        return num.compareTo(BigInteger.valueOf(limit).multiply(den)) <= 0;
    }

    private Map<String, TaskStatus> taskStatuses(long incidentId) {
        Map<String, TaskStatus> statuses = new HashMap<>();
        for (IncidentTask task : tasks.listByIncident(incidentId)) {
            statuses.put(task.taskKey(), task.status());
        }
        return statuses;
    }

    /**
     * 组装模型视图：残余按查询时任务状态精确核算；附遏制冻结快照（未遏制为空列表）。
     */
    private RiskModelView toView(Incident incident) {
        RiskModel model = riskModels.findModel(incident.id()).orElseThrow();
        List<RiskBarrier> barriers = riskModels.listBarriers(incident.id());
        Map<String, TaskStatus> statuses = taskStatuses(incident.id());
        BigInteger[] residual = residualFraction(model.initialRisk(), barriers, statuses);
        List<BarrierView> barrierViews = barriers.stream()
                .map(b -> {
                    TaskStatus status = statuses.get(b.taskKey());
                    boolean effective = !b.revoked() && status == TaskStatus.DONE;
                    return new BarrierView(b.barrierKey(), b.taskKey(),
                            status == null ? null : status.name(), b.numerator(), b.denominator(),
                            b.revoked(), effective, b.createdAt(), b.updatedAt());
                })
                .toList();
        List<ContainmentSnapshotView> snapshots = riskModels.listSnapshots(incident.id()).stream()
                .map(s -> new ContainmentSnapshotView(s.barrierKey(), s.taskKey(), s.taskStatus(),
                        s.numerator(), s.denominator(), s.frozenAt()))
                .toList();
        return new RiskModelView(incident.incidentKey(), model.initialRisk(), model.residualLimit(),
                model.version(), barrierViews, residual[0].longValueExact(),
                residual[1].longValueExact(),
                withinLimit(model.residualLimit(), residual[0], residual[1]), snapshots,
                model.createdAt(), model.updatedAt());
    }

    private Incident lockIncident(String incidentKey) {
        requireText(incidentKey, "incidentKey");
        return incidents.lockByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
    }

    private RiskModel requireModel(long incidentId) {
        return riskModels.findModel(incidentId)
                .orElseThrow(() -> ApiException.conflict("风险模型尚未设置，请先设置初始风险与残余上限"));
    }

    private RiskBarrier requireActiveBarrier(long incidentId, String barrierKey) {
        RiskBarrier barrier = riskModels.findBarrier(incidentId, barrierKey)
                .orElseThrow(() -> ApiException.notFound("屏障不存在: " + barrierKey));
        if (barrier.revoked()) {
            throw ApiException.conflict("屏障已撤销，不能再修改: " + barrierKey);
        }
        return barrier;
    }

    private IncidentTask requireOpenTask(long incidentId, String taskKey) {
        IncidentTask task = tasks.findByKey(incidentId, taskKey)
                .orElseThrow(() -> ApiException.notFound("任务不存在: " + taskKey));
        if (task.status() != TaskStatus.OPEN) {
            throw ApiException.conflict("只能为 OPEN 任务绑定屏障，当前状态: " + task.status());
        }
        return task;
    }

    private static void requireCommander(Incident incident, String actor) {
        if (incident.commander() == null || !incident.commander().equals(actor)) {
            throw ApiException.conflict("只有当前指挥人 "
                    + (incident.commander() == null ? "(无)" : incident.commander()) + " 能执行该操作");
        }
    }

    private static void requireNotClosed(Incident incident) {
        if (incident.status() == IncidentStatus.CLOSED) {
            throw ApiException.illegalTransition("事件已关闭，不能再配置残余风险模型");
        }
    }

    private static void checkVersion(RiskModel model, long expectedVersion) {
        if (model.version() != expectedVersion) {
            throw ApiException.conflict("expectedVersion 与模型当前版本不一致: 期望 "
                    + expectedVersion + "，当前 " + model.version());
        }
    }

    private static void requireActor(String actor) {
        requireText(actor, "X-Actor-Id");
    }

    private static void requireRatio(int numerator, int denominator) {
        if (numerator > denominator) {
            throw ApiException.badRequest("numerator 不能大于 denominator");
        }
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

    private static long requireVersion(Long expectedVersion) {
        if (expectedVersion == null) {
            throw ApiException.badRequest("expectedVersion 不能为空");
        }
        if (expectedVersion < 0) {
            throw ApiException.badRequest("expectedVersion 不能为负数");
        }
        return expectedVersion;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest(field + " 不能为空");
        }
        return value.strip();
    }
}

