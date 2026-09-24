package com.example.starter.batch;

import com.example.starter.batch.dto.CreateSamplingPlanRequest;
import com.example.starter.batch.dto.RecordSampleRequest;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 抽样检验计划核心服务。
 *
 * <p>并发策略：创建计划在事务内对 batch 行 SELECT ... FOR UPDATE 串行化同批次创建；
 * 逐件登记先锁 batch 行（与祖先召回、批准互斥）再锁 sampling_plan 行，
 * 加权计数只在持锁事务内读取-累加-写回，按事务提交顺序生效，
 * 保证并发登记最多产生一条判定且计数不重不漏，召回先提交则登记返回 422。
 * commandKey 幂等复用 command_log：同类型同键同参重放首次响应快照，
 * 同键改参 409，失败不占键。
 */
@Service
public class SamplingService {

    static final String CMD_CREATE_PLAN = "CREATE_SAMPLING_PLAN";
    static final String CMD_RECORD_SAMPLE = "RECORD_SAMPLE";

    /**
     * 同一批次允许的 REJECTED 计划数上限，超出不得再新建计划。
     */
    static final int MAX_REJECTED_PLANS = 3;

    /**
     * 指纹拼接分隔符（NUL）：业务参数不可能包含该字符，避免拼接碰撞。
     */
    private static final String SEP = Character.toString(0);
    private static final int IDEMPOTENCY_MAX_ATTEMPTS = 3;

    private final BatchRepository batchRepo;
    private final SamplingRepository repo;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper;

    public SamplingService(BatchRepository batchRepo, SamplingRepository repo,
                           PlatformTransactionManager transactionManager, ObjectMapper objectMapper) {
        this.batchRepo = batchRepo;
        this.repo = repo;
        this.tx = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    /**
     * 创建抽样检验计划：OPEN；仅隔离中(QUARANTINED)批次可创建；
     * 同批次仅允许一个未终结计划（重复 409）；已召回或有召回祖先的批次禁止创建（422）；
     * 同批次累计最多三个 REJECTED 计划，超出 422；planKey 全局唯一（冲突 409）。
     */
    public StoredResponse createPlan(String batchKey, CreateSamplingPlanRequest req) {
        int ac = req.acceptNumber();
        int re = req.rejectNumber();
        int n = req.sampleSize();
        String fingerprint = fingerprint("createPlan", batchKey, req.planKey(),
                Integer.toString(n), Integer.toString(ac), Integer.toString(re), req.basis());
        return executeIdempotent(CMD_CREATE_PLAN, req.commandKey(), fingerprint, () -> {
            // 先锁批次行：串行化同批次并发建计划，并与祖先召回互斥
            BatchRepository.BatchRow batch = batchRepo.findBatchForUpdate(batchKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
            // 行锁后重查命令快照：并发同键请求在锁等待期间可能已由对方提交，
            // 此时须重放首次结果而不是把重复 OPEN 计划误判为 409
            Optional<StoredResponse> loggedAfterLock = loggedResponse(
                    CMD_CREATE_PLAN, req.commandKey(), fingerprint);
            if (loggedAfterLock.isPresent()) {
                return loggedAfterLock.get();
            }
            if (ac < 0 || !(ac < re) || re > n) {
                throw ApiException.badRequest("计划参数不合法，须满足 0≤Ac<Re≤样本量");
            }
            assertBatchNotRecalled(batch);
            if (!BatchStatus.QUARANTINED.name().equals(batch.status())) {
                throw ApiException.unprocessable("仅隔离中(QUARANTINED)批次可创建抽样计划，当前状态 "
                        + batch.status());
            }
            if (repo.findOpenPlanForUpdate(batchKey).isPresent()) {
                throw ApiException.conflict("该批次已存在未终结(OPEN)抽样计划，同一批次只能有一个");
            }
            if (repo.findPlan(req.planKey()).isPresent()) {
                throw ApiException.conflict("planKey 全局唯一，已存在: " + req.planKey());
            }
            if (repo.countRejectedPlans(batchKey) >= MAX_REJECTED_PLANS) {
                throw ApiException.unprocessable(
                        "同批次最多允许 " + MAX_REJECTED_PLANS + " 个 REJECTED 计划，不得再新建");
            }
            String now = now();
            repo.insertPlan(new SamplingRepository.PlanRow(0L, req.planKey(), batchKey, n, ac, re,
                    req.basis(), SamplingPlanStatus.OPEN.name(), 0, 0, now, null));
            SamplingPlanResponse body = new SamplingPlanResponse(req.planKey(), batchKey, n, ac, re,
                    req.basis(), SamplingPlanStatus.OPEN, 0, 0, Instant.parse(now), null);
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 逐件登记：合格件 grade 为 null；缺陷按 CRITICAL=3、MAJOR=1、MINOR=0 加权累计。
     * 累计达到 Re 在本登记同一事务内原子判定 REJECTED；样本登记完成且累计不大于 Ac 判定
     * ACCEPTED 并将隔离批次推进到 PENDING_RELEASE；其余保持 OPEN。
     * 计划终结后再登记 409；序号越界 400；序号重复 409；批次或祖先已召回 422。
     */
    public StoredResponse recordSample(String planKey, RecordSampleRequest req) {
        int sampleNo = req.sampleNo();
        boolean conforming = req.grade() == null;
        String gradeName = conforming ? "OK" : req.grade().name();
        String fingerprint = fingerprint("recordSample", planKey, Integer.toString(sampleNo),
                gradeName, req.description());
        return executeIdempotent(CMD_RECORD_SAMPLE, req.commandKey(), fingerprint, () -> {
            SamplingRepository.PlanRow plan = repo.findPlan(planKey)
                    .orElseThrow(() -> ApiException.notFound("抽样计划不存在: " + planKey));
            final String batchKey = plan.batchKey();
            // 先锁批次行再锁计划行：与祖先召回/批准互斥，锁顺序与创建保持一致（batch → plan）
            BatchRepository.BatchRow batch = batchRepo.findBatchForUpdate(batchKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
            assertBatchNotRecalled(batch);
            plan = repo.findPlanForUpdate(planKey)
                    .orElseThrow(() -> ApiException.notFound("抽样计划不存在: " + planKey));
            // 计划行锁后重查命令快照：并发同键登记在锁等待期间可能已由对方提交，
            // 此时须重放首次结果而不是把同序号登记误判为重复 409
            Optional<StoredResponse> loggedAfterLock = loggedResponse(
                    CMD_RECORD_SAMPLE, req.commandKey(), fingerprint);
            if (loggedAfterLock.isPresent()) {
                return loggedAfterLock.get();
            }
            if (!SamplingPlanStatus.OPEN.name().equals(plan.status())) {
                throw ApiException.conflict("抽样计划已终结(" + plan.status() + ")，不能再登记样本");
            }
            if (sampleNo < 1 || sampleNo > plan.sampleSize()) {
                throw ApiException.badRequest(
                        "样本序号必须在 1～" + plan.sampleSize() + " 之间: " + sampleNo);
            }
            boolean duplicated = repo.findSamples(planKey).stream()
                    .anyMatch(s -> s.sampleNo() == sampleNo);
            if (duplicated) {
                throw ApiException.conflict("样本序号已登记，不能重复: " + sampleNo);
            }

            int weightAdded = conforming ? 0 : req.grade().weight();
            int recorded = plan.recordedCount() + 1;
            int weighted = plan.weightedDefects() + weightAdded;
            SamplingPlanStatus decision = SamplingPlanStatus.OPEN;
            if (weighted >= plan.rejectNumber()) {
                decision = SamplingPlanStatus.REJECTED;
            } else if (recorded == plan.sampleSize() && weighted <= plan.acceptNumber()) {
                decision = SamplingPlanStatus.ACCEPTED;
            }
            String now = now();
            String decidedAt = decision == SamplingPlanStatus.OPEN ? null : now;
            // 登记明细与计划计数、判定在同一事务内原子落定
            repo.insertSample(new SamplingRepository.SampleRow(0L, planKey, sampleNo, conforming,
                    conforming ? null : req.grade().name(), req.description(), weightAdded,
                    weighted, decision.name(), now));
            repo.updatePlanProgress(planKey, recorded, weighted, decision.name(), decidedAt);
            if (decision == SamplingPlanStatus.ACCEPTED
                    && BatchStatus.QUARANTINED.name().equals(batch.status())) {
                // 抽样合格判定驱动隔离批次进入待放行，作为双角色批准的前置
                batchRepo.updateStatus(plan.batchKey(), BatchStatus.PENDING_RELEASE.name());
            }
            SampleRecordResponse body = new SampleRecordResponse(planKey, sampleNo, conforming,
                    conforming ? null : req.grade(), req.description(), weighted, decision,
                    Instant.parse(now), decidedAt == null ? null : Instant.parse(decidedAt));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 计划明细：参数、累计计数与判定时刻；计划不存在 404。
     */
    public SamplingPlanResponse getPlan(String planKey) {
        return toPlanResponse(repo.findPlan(planKey)
                .orElseThrow(() -> ApiException.notFound("抽样计划不存在: " + planKey)));
    }

    /**
     * 某批次全部计划（按创建顺序），即判定历史；批次不存在 404。
     */
    public List<SamplingPlanResponse> listPlans(String batchKey) {
        batchRepo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        return repo.findPlansByBatch(batchKey).stream().map(this::toPlanResponse).toList();
    }

    /**
     * 某计划全部逐件登记结果（按样本序号）；planStatus/decidedAt 为各件登记落定当时的快照，
     * 终结之后不被计划最终状态回填改写。计划不存在 404。
     */
    public List<SampleRecordResponse> listSamples(String planKey) {
        repo.findPlan(planKey)
                .orElseThrow(() -> ApiException.notFound("抽样计划不存在: " + planKey));
        return repo.findSamples(planKey).stream()
                .map(s -> {
                    SamplingPlanStatus statusAfter = SamplingPlanStatus.valueOf(s.planStatusAfter());
                    boolean terminal = statusAfter != SamplingPlanStatus.OPEN;
                    return new SampleRecordResponse(planKey, s.sampleNo(), s.conforming(),
                            s.grade() == null ? null : DefectGrade.valueOf(s.grade()),
                            s.description(), s.weightedDefectsAfter(), statusAfter,
                            Instant.parse(s.createdAt()),
                            terminal ? Instant.parse(s.createdAt()) : null);
                })
                .toList();
    }

    /**
     * 批次自身已召回或任一级祖先已召回时抛 422；调用方须已持有该批次行锁。
     */
    private void assertBatchNotRecalled(BatchRepository.BatchRow batch) {
        if (BatchStatus.RECALLED.name().equals(batch.status())) {
            throw ApiException.unprocessable("批次已召回，不得创建计划或登记样本: " + batch.batchKey());
        }
        recalledAncestor(batch.batchKey()).ifPresent(ancestor -> {
            throw ApiException.unprocessable(
                    "祖先批次 " + ancestor + " 已召回，不得创建计划或登记样本");
        });
    }

    /**
     * 沿父链向上查找最近的被直接召回（RECALLED）祖先；批次自身召回由调用方另行判断。
     */
    private Optional<String> recalledAncestor(String batchKey) {
        Map<String, String> parentOf = new HashMap<>();
        for (BatchRepository.LineageRow row : batchRepo.findAllLineage()) {
            parentOf.put(row.childKey(), row.parentKey());
        }
        Set<String> recalled = new HashSet<>(batchRepo.findRecalledKeys());
        String current = batchKey;
        while (parentOf.containsKey(current)) {
            current = parentOf.get(current);
            if (recalled.contains(current)) {
                return Optional.of(current);
            }
        }
        return Optional.empty();
    }

    private SamplingPlanResponse toPlanResponse(SamplingRepository.PlanRow row) {
        return new SamplingPlanResponse(row.planKey(), row.batchKey(), row.sampleSize(),
                row.acceptNumber(), row.rejectNumber(), row.basis(),
                SamplingPlanStatus.valueOf(row.status()), row.recordedCount(),
                row.weightedDefects(), Instant.parse(row.createdAt()),
                row.decidedAt() == null ? null : Instant.parse(row.decidedAt()));
    }

    /**
     * 幂等执行：同事务内先查 command_log，命中则按指纹返回快照或 409；
     * 未命中执行业务动作并写入快照。并发同键插入冲突时重试，读取已提交结果。
     */
    StoredResponse executeIdempotent(String type, String commandKey, String fingerprint,
                                     Supplier<StoredResponse> action) {
        for (int attempt = 0; attempt < IDEMPOTENCY_MAX_ATTEMPTS; attempt++) {
            try {
                return tx.execute(status -> {
                    Optional<StoredResponse> logged = loggedResponse(type, commandKey, fingerprint);
                    if (logged.isPresent()) {
                        return logged.get();
                    }
                    StoredResponse response = action.get();
                    batchRepo.insertCommand(new BatchRepository.CommandRow(type, commandKey, fingerprint,
                            response.status(), response.body()), now());
                    return response;
                });
            } catch (DuplicateKeyException e) {
                // 并发同键或 planKey/(planKey,sampleNo) 唯一冲突：回滚后重试，
                // 读取对方已提交的命令快照或由指纹差异转 409
            }
        }
        throw ApiException.conflict("命令并发冲突，请重试: " + commandKey);
    }

    /**
     * 查询命令快照：命中且指纹一致返回首次响应；指纹不一致抛 409；未命中返回空。
     */
    private Optional<StoredResponse> loggedResponse(String type, String commandKey, String fingerprint) {
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

    String toJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    String now() {
        return Instant.now().toString();
    }

    String fingerprint(String... parts) {
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
