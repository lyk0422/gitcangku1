package com.example.starter.batch;

import com.example.starter.batch.dto.CreatePlanRequest;
import com.example.starter.batch.dto.PlanDetailResponse;
import com.example.starter.batch.dto.RegisterSampleRequest;
import com.example.starter.batch.dto.SampleRecordResponse;
import com.example.starter.batch.dto.SamplingPlanResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 抽样检验计划核心服务：计划创建、逐件登记与原子判定、计划明细与判定历史查询。
 *
 * <p>并发策略：登记事务先对所属批次 batch 行 SELECT ... FOR UPDATE（与批准/召回/拆分互斥，
 * 按提交顺序裁决），再锁定 sampling_plan 行串行化同计划登记；
 * 判定与登记在同一事务内完成，并发登记最多产生一条判定，加权计数不重不累。
 * planKey 全局唯一；commandKey 幂等同既有命令机制（同参重放首次结果、异参 409、失败不占键）。
 */
@Service
public class SamplingPlanService {

    private static final String CMD_CREATE_PLAN = "CREATE_SAMPLING_PLAN";
    private static final String CMD_REGISTER_SAMPLE = "REGISTER_SAMPLE";

    /**
     * 同批次允许的 REJECTED 计划数上限。
     */
    private static final int MAX_REJECTED_PLANS = 3;

    /**
     * 指纹拼接分隔符（NUL）：业务参数不可能包含该字符，避免拼接碰撞。
     */
    private static final String SEP = "\u0000";

    private static final int IDEMPOTENCY_MAX_ATTEMPTS = 3;

    private final SamplingPlanRepository planRepo;
    private final BatchRepository batchRepo;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper;

    public SamplingPlanService(SamplingPlanRepository planRepo,
                               BatchRepository batchRepo,
                               PlatformTransactionManager transactionManager,
                               ObjectMapper objectMapper) {
        this.planRepo = planRepo;
        this.batchRepo = batchRepo;
        this.tx = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    /**
     * 创建抽样检验计划：初始 OPEN。仅隔离中（QUARANTINED）批次可创建——抽样须在提交流放检验
     * 与批准之前完成；REJECTED 后可在仍隔离阶段新建下一计划。
     * 同批次只能有一个未终结计划（重复 409）；批次已召回或有召回祖先不得创建（422）；
     * planKey 全局唯一（409）；同批次最多三个 REJECTED 计划，超出 422。
     */
    public StoredResponse createPlan(String batchKey, CreatePlanRequest req) {
        int sampleSize = req.sampleSize();
        int ac = req.acceptNumber();
        int re = req.rejectNumber();
        if (ac < 0 || ac >= re || re > sampleSize) {
            throw ApiException.badRequest("抽样参数须满足 0≤Ac<Re≤样本量: Ac=" + ac + ", Re=" + re
                    + ", 样本量=" + sampleSize);
        }
        String fingerprint = fingerprint("createPlan", batchKey, req.planKey(),
                Integer.toString(sampleSize), Integer.toString(ac), Integer.toString(re), req.basis());
        return executeIdempotent(CMD_CREATE_PLAN, req.commandKey(), fingerprint, () -> {
            // 批次行锁串行化同批次并发创建（与批准/召回/拆分互斥）
            BatchRepository.BatchRow batch = batchRepo.findBatchForUpdate(batchKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
            // 锁等待期间并发同键请求可能已由对方提交，须重放首次结果而不是误判 planKey 冲突
            var logged = loggedResponse(CMD_CREATE_PLAN, req.commandKey(), fingerprint);
            if (logged.isPresent()) {
                return logged.get();
            }
            if (BatchStatus.RECALLED.name().equals(batch.status())) {
                throw ApiException.unprocessable("批次已召回，不得创建抽样计划: " + batchKey);
            }
            assertNoRecalledAncestor(batchKey);
            if (!BatchStatus.QUARANTINED.name().equals(batch.status())) {
                throw ApiException.conflict(
                        "批次状态 " + batch.status() + " 不允许创建抽样计划，仅隔离中批次可创建");
            }
            if (planRepo.findPlan(req.planKey()).isPresent()) {
                throw ApiException.conflict("planKey 已存在: " + req.planKey());
            }
            if (planRepo.findOpenPlanByBatchForUpdate(batchKey).isPresent()) {
                throw ApiException.conflict("该批次已存在未终结（OPEN）抽样计划，不能重复创建");
            }
            int rejectedCount = planRepo.countPlansByBatchWithStatus(batchKey,
                    PlanStatus.REJECTED.name());
            if (rejectedCount >= MAX_REJECTED_PLANS) {
                throw ApiException.unprocessable(
                        "同批次最多 " + MAX_REJECTED_PLANS + " 个 REJECTED 计划，不能再创建新计划");
            }
            int seq = planRepo.findPlansByBatch(batchKey).size() + 1;
            String now = now();
            planRepo.insertPlan(new SamplingPlanRepository.PlanRow(0L, req.planKey(), batchKey, seq,
                    sampleSize, ac, re, req.basis(), PlanStatus.OPEN.name(), 0, 0, now, null));
            SamplingPlanResponse body = new SamplingPlanResponse(req.planKey(), batchKey, seq,
                    sampleSize, ac, re, req.basis(), PlanStatus.OPEN.name(), 0, 0,
                    Instant.parse(now), null);
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 逐件登记：样本序号 1～样本量且同计划不重复（重复 409）；
     * 累计加权缺陷达到 Re 的当件在同一事务内判定 REJECTED；
     * 全部样本登记完成且累计不大于 Ac 判定 ACCEPTED；登记完成但累计大于 Ac（未达 Re）按不满足
     * 接收数 fail-closed 判定 REJECTED；其余保持 OPEN。计划终结后再登记 409；
     * 批次被召回或有召回祖先时登记与判定 422；计划不存在 404。
     */
    public StoredResponse registerSample(String planKey, RegisterSampleRequest req) {
        String fingerprint = fingerprint("registerSample", planKey,
                Integer.toString(req.sampleIndex()), req.result().name(), req.description());
        return executeIdempotent(CMD_REGISTER_SAMPLE, req.commandKey(), fingerprint, () -> {
            SamplingPlanRepository.PlanRow existingPlan = planRepo.findPlan(planKey)
                    .orElseThrow(() -> ApiException.notFound("抽样计划不存在: " + planKey));
            // 先锁批次行：与召回/批准/拆分按提交顺序互斥裁决——召回先提交则本事务看到 RECALLED 返回 422
            BatchRepository.BatchRow batch = batchRepo.findBatchForUpdate(existingPlan.batchKey())
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + existingPlan.batchKey()));
            // 锁等待期间并发同键请求可能已提交，重放首次结果
            var logged = loggedResponse(CMD_REGISTER_SAMPLE, req.commandKey(), fingerprint);
            if (logged.isPresent()) {
                return logged.get();
            }
            if (BatchStatus.RECALLED.name().equals(batch.status())) {
                throw ApiException.unprocessable("批次已召回，登记与判定被拒绝: " + batch.batchKey());
            }
            assertNoRecalledAncestor(existingPlan.batchKey());
            // 再锁计划行：串行化同计划并发登记，保证加权计数不重不累且最多产生一条判定
            SamplingPlanRepository.PlanRow plan = planRepo.findPlanForUpdate(planKey)
                    .orElseThrow(() -> ApiException.notFound("抽样计划不存在: " + planKey));
            if (!PlanStatus.OPEN.name().equals(plan.status())) {
                throw ApiException.conflict("抽样计划已终结（" + plan.status()
                        + "），不能再登记样本");
            }
            int index = req.sampleIndex();
            if (index < 1 || index > plan.sampleSize()) {
                throw ApiException.badRequest(
                        "样本序号须为 1～样本量(" + plan.sampleSize() + "): " + index);
            }
            if (planRepo.findSampleRecord(planKey, index).isPresent()) {
                throw ApiException.conflict("样本序号 " + index + " 已登记，不能重复登记");
            }

            int weight = req.result().weight();
            int weighted = plan.weightedDefects() + weight;
            int registered = plan.registeredCount() + 1;
            String now = now();
            planRepo.insertSampleRecord(new SamplingPlanRepository.SampleRecordRow(0L, planKey,
                    index, req.result().name(), req.description(), weight, now));

            String newStatus;
            String decidedAt = null;
            if (weighted >= plan.rejectNumber()) {
                newStatus = PlanStatus.REJECTED.name();
                decidedAt = now;
            } else if (registered == plan.sampleSize()) {
                // 样本登记完成：累计不大于 Ac 判定 ACCEPTED；不满足接收数则不能接收，fail-closed REJECTED
                newStatus = weighted <= plan.acceptNumber()
                        ? PlanStatus.ACCEPTED.name()
                        : PlanStatus.REJECTED.name();
                decidedAt = now;
            } else {
                newStatus = PlanStatus.OPEN.name();
            }
            planRepo.applyRegistration(planKey, weighted, registered, newStatus, decidedAt);
            SampleRecordResponse body = new SampleRecordResponse(planKey, index, req.result(),
                    req.description(), weight, weighted, newStatus, Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 计划明细：计划概要 + 按样本序号排序的全部逐件结果；计划不存在 404。
     */
    public PlanDetailResponse planDetail(String planKey) {
        SamplingPlanRepository.PlanRow plan = planRepo.findPlan(planKey)
                .orElseThrow(() -> ApiException.notFound("抽样计划不存在: " + planKey));
        return new PlanDetailResponse(toPlanResponse(plan),
                planRepo.findSampleRecords(planKey).stream().map(this::toSampleResponse).toList());
    }

    /**
     * 批次的全部计划概要与判定历史，按计划序号升序；批次不存在 404。
     */
    public List<SamplingPlanResponse> batchPlans(String batchKey) {
        batchRepo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        return planRepo.findPlansByBatch(batchKey).stream().map(this::toPlanResponse).toList();
    }

    /**
     * 某计划的全部逐件结果，按样本序号升序；计划不存在 404。
     */
    public List<SampleRecordResponse> planSamples(String planKey) {
        planRepo.findPlan(planKey)
                .orElseThrow(() -> ApiException.notFound("抽样计划不存在: " + planKey));
        return planRepo.findSampleRecords(planKey).stream().map(this::toSampleResponse).toList();
    }

    /**
     * 批准门禁：批次存在 ACCEPTED 计划才放行；否则 422 并指明当前计划状态
     * （OPEN / REJECTED / 无计划）。供 BatchService 在双角色批准前置校验中调用。
     */
    public void assertBatchHasAcceptedPlan(String batchKey) {
        List<SamplingPlanRepository.PlanRow> plans = planRepo.findPlansByBatch(batchKey);
        boolean accepted = plans.stream()
                .anyMatch(p -> PlanStatus.ACCEPTED.name().equals(p.status()));
        if (accepted) {
            return;
        }
        String current = plans.stream()
                .filter(p -> PlanStatus.OPEN.name().equals(p.status()))
                .findFirst()
                .map(p -> PlanStatus.OPEN.name())
                .orElseGet(() -> plans.isEmpty()
                        ? "无抽样计划"
                        : plans.get(plans.size() - 1).status());
        throw ApiException.unprocessable(
                "只有存在 ACCEPTED 抽样计划的批次才可批准放行，当前计划状态: " + current);
    }

    /**
     * 幂等执行：同事务内先查 command_log，命中则按指纹返回快照或 409；
     * 未命中执行业务动作并写入快照。并发同键插入冲突时重试，读取已提交结果。
     */
    private StoredResponse executeIdempotent(String type, String commandKey, String fingerprint,
                                             Supplier<StoredResponse> action) {
        for (int attempt = 0; attempt < IDEMPOTENCY_MAX_ATTEMPTS; attempt++) {
            try {
                return tx.execute(status -> {
                    var logged = loggedResponse(type, commandKey, fingerprint);
                    if (logged.isPresent()) {
                        return logged.get();
                    }
                    StoredResponse response = action.get();
                    batchRepo.insertCommand(new BatchRepository.CommandRow(type, commandKey,
                            fingerprint, response.status(), response.body()), now());
                    return response;
                });
            } catch (DuplicateKeyException e) {
                // 并发同 commandKey 主键冲突：回滚后重试，读取对方已提交的命令快照
            }
        }
        throw ApiException.conflict("命令并发冲突，请重试: " + commandKey);
    }

    /**
     * 查询命令快照：命中且指纹一致返回首次响应；指纹不一致抛 409；未命中返回空。
     */
    private Optional<StoredResponse> loggedResponse(String type, String commandKey,
                                                    String fingerprint) {
        var existing = batchRepo.findCommand(type, commandKey);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        BatchRepository.CommandRow row = existing.get();
        if (!row.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("commandKey 已以不同参数使用: " + commandKey);
        }
        return Optional.of(new StoredResponse(row.responseStatus(), row.responseBody()));
    }

    /**
     * 若任一级祖先已被召回则抛 422：批次禁止创建计划、登记与判定。
     */
    private void assertNoRecalledAncestor(String batchKey) {
        Map<String, String> parentOf = childToParent();
        Set<String> recalled = Set.copyOf(batchRepo.findRecalledKeys());
        String current = batchKey;
        while (parentOf.containsKey(current)) {
            current = parentOf.get(current);
            if (recalled.contains(current)) {
                throw ApiException.unprocessable(
                        "祖先批次 " + current + " 已召回，禁止抽样计划登记与判定");
            }
        }
    }

    private Map<String, String> childToParent() {
        Map<String, String> parentOf = new HashMap<>();
        for (BatchRepository.LineageRow row : batchRepo.findAllLineage()) {
            parentOf.put(row.childKey(), row.parentKey());
        }
        return parentOf;
    }

    private SamplingPlanResponse toPlanResponse(SamplingPlanRepository.PlanRow row) {
        return new SamplingPlanResponse(row.planKey(), row.batchKey(), row.seq(),
                row.sampleSize(), row.acceptNumber(), row.rejectNumber(), row.basis(),
                row.status(), row.weightedDefects(), row.registeredCount(),
                Instant.parse(row.createdAt()),
                row.decidedAt() == null ? null : Instant.parse(row.decidedAt()));
    }

    private SampleRecordResponse toSampleResponse(SamplingPlanRepository.SampleRecordRow row) {
        // 计划当前累计值是全部登记后的结果，不能直接当作该件落定时刻的快照；
        // 故按登记提交顺序（id）重放，推导该件落定瞬间的累计加权缺陷与计划状态。
        SamplingPlanRepository.PlanRow plan = planRepo.findPlan(row.planKey()).orElseThrow();
        List<SamplingPlanRepository.SampleRecordRow> chronology =
                planRepo.findSampleRecordsInCommitOrder(row.planKey());
        int cumulative = 0;
        int seen = 0;
        String statusSnapshot = PlanStatus.OPEN.name();
        for (SamplingPlanRepository.SampleRecordRow r : chronology) {
            cumulative += r.weight();
            seen++;
            if (cumulative >= plan.rejectNumber()) {
                statusSnapshot = PlanStatus.REJECTED.name();
            } else if (seen == plan.sampleSize()) {
                // 登记完成：累计不大于 Ac 为 ACCEPTED，否则 fail-closed 为 REJECTED
                statusSnapshot = cumulative <= plan.acceptNumber()
                        ? PlanStatus.ACCEPTED.name()
                        : PlanStatus.REJECTED.name();
            } else {
                statusSnapshot = PlanStatus.OPEN.name();
            }
            if (r.id() == row.id()) {
                break;
            }
        }
        return new SampleRecordResponse(row.planKey(), row.sampleIndex(),
                SampleResult.valueOf(row.result()), row.description(), row.weight(),
                cumulative, statusSnapshot, Instant.parse(row.createdAt()));
    }

    private String toJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    private String now() {
        return Instant.now().toString();
    }

    private String fingerprint(String... parts) {
        String canonical = String.join(SEP, parts);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
