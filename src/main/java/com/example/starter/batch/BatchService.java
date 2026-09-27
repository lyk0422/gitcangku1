package com.example.starter.batch;

import com.example.starter.batch.dto.ApprovalResponse;
import com.example.starter.batch.dto.ApproveRequest;
import com.example.starter.batch.dto.BatchHistoryResponse;
import com.example.starter.batch.dto.BatchResponse;
import com.example.starter.batch.dto.CreateBatchRequest;
import com.example.starter.batch.dto.LineageEntryResponse;
import com.example.starter.batch.dto.MergeRequest;
import com.example.starter.batch.dto.MergeResponse;
import com.example.starter.batch.dto.PotencyCalculationView;
import com.example.starter.batch.dto.PotencyDiagnosticsResponse;
import com.example.starter.batch.dto.PotencyRejectionResponse;
import com.example.starter.batch.dto.PotencyRevisionRequest;
import com.example.starter.batch.dto.PotencyRevisionResponse;
import com.example.starter.batch.dto.PotencyView;
import com.example.starter.batch.dto.RecallRequest;
import com.example.starter.batch.dto.RecallResponse;
import com.example.starter.batch.dto.SplitRequest;
import com.example.starter.batch.dto.SplitResponse;
import com.example.starter.batch.dto.SubmitTestRequest;
import com.example.starter.batch.dto.TestResultResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * 批次隔离、检验、血缘、放行与效价衰减核心服务。
 *
 * <p>并发策略：同一批次的模型修订/检验/批准/召回/拆分在事务内对 batch 行
 * SELECT ... FOR UPDATE 串行化，按事务提交顺序生效；合批按业务键排序锁定全部父批。
 * commandKey 幂等通过 command_log 主键 + 规范化 JSON 参数指纹实现，
 * 同键同参重放返回首次响应快照，同键异参返回 409，失败不占键；仅成功命令写 command_log。
 *
 * <p>效价：线性模型 {@code p(t)=initial-rate*ΔUTC日}，放行（第二批准）在锁内读取最新模型版本
 * 计算当前效价，低于阈值 422 回滚；拆分以父批操作时刻效价建立子批 v1 模型并按分摊数量守恒；
 * 合批以全部父批操作时刻效价按投入数量加权建立新批 v1 模型，任一父批不合格或权重不守恒整次回滚。
 * 放行/拆分/合批固化计算时刻、模型版本、输入效价与舍入结果，后续模型修订不改写历史结论。
 */
@Service
public class BatchService {

    private static final String CMD_CREATE = "CREATE_BATCH";
    private static final String CMD_TEST = "SUBMIT_TEST";
    private static final String CMD_APPROVE = "APPROVE";
    private static final String CMD_RECALL = "RECALL";
    private static final String CMD_SPLIT = "SPLIT";
    private static final String CMD_MERGE = "MERGE";
    private static final String CMD_POTENCY_REVISION = "POTENCY_REVISION";

    private static final String OP_APPROVE = "APPROVE";
    private static final String OP_SPLIT = "SPLIT";
    private static final String OP_MERGE = "MERGE";

    /**
     * 合批固化计算无单一模型版本（各父批版本见 inputs），model_version 记 0。
     */
    private static final int MULTI_PARENT_MODEL_VERSION = 0;

    private static final int IDEMPOTENCY_MAX_ATTEMPTS = 3;

    private final BatchRepository repo;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper;
    private final Fingerprints fingerprints;
    private final TimeSource timeSource;

    public BatchService(BatchRepository repo,
                        PlatformTransactionManager transactionManager,
                        ObjectMapper objectMapper,
                        Fingerprints fingerprints,
                        TimeSource timeSource) {
        this.repo = repo;
        this.tx = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
        this.fingerprints = fingerprints;
        this.timeSource = timeSource;
    }

    /**
     * 创建批次：初始状态 QUARANTINED；batchKey 全局唯一，重复返回 409。
     * 数量与效价字段整组可选：整组缺省走旧路径；部分提供判 400；负值或数量非正判 422。
     * 登记效价时创建模型 v1，锚点为生产 UTC 时间。
     */
    public StoredResponse createBatch(CreateBatchRequest req) {
        List<String> items = req.requiredTests().stream().map(String::trim).toList();
        if (new HashSet<>(items).size() != items.size()) {
            throw ApiException.badRequest("requiredTests 存在重复检验项");
        }
        boolean potencyGroupPresent = req.quantity() != null || req.initialPotency() != null
                || req.minReleasePotency() != null || req.dailyDecayRate() != null;
        boolean potencyGroupComplete = req.quantity() != null && req.initialPotency() != null
                && req.minReleasePotency() != null && req.dailyDecayRate() != null;
        if (potencyGroupPresent && !potencyGroupComplete) {
            throw ApiException.badRequest(
                    "quantity/initialPotency/minReleasePotency/dailyDecayRate 必须同时提供或同时省略");
        }
        if (potencyGroupComplete) {
            assertPositiveQuantity(req.quantity());
            assertNonNegativePotency(req.initialPotency(), "initialPotency");
            assertNonNegativePotency(req.minReleasePotency(), "minReleasePotency");
            assertNonNegativePotency(req.dailyDecayRate(), "dailyDecayRate");
        }
        Map<String, Object> fp = canonical("create");
        fp.put("batchKey", req.batchKey());
        fp.put("productCode", req.productCode());
        fp.put("batchNo", req.batchNo());
        fp.put("producedAt", req.producedAt().toString());
        fp.put("requiredTests", items.stream().sorted().toList());
        fp.put("quantity", scale6(req.quantity()));
        fp.put("initialPotency", scale6(req.initialPotency()));
        fp.put("minReleasePotency", scale6(req.minReleasePotency()));
        fp.put("dailyDecayRate", scale6(req.dailyDecayRate()));
        String fingerprint = fingerprints.sha256(fp);
        return executeIdempotent(CMD_CREATE, req.commandKey(), fingerprint, () -> {
            repo.findBatch(req.batchKey()).ifPresent(b -> {
                throw ApiException.conflict("batchKey 已存在: " + req.batchKey());
            });
            String now = now();
            repo.insertBatch(new BatchRepository.BatchRow(0L, req.batchKey(), req.productCode(),
                    req.batchNo(), req.producedAt().toString(), BatchStatus.QUARANTINED.name(), now,
                    req.quantity(), req.initialPotency(), req.minReleasePotency(),
                    req.dailyDecayRate(),
                    potencyGroupComplete ? req.producedAt().toString() : null));
            for (int i = 0; i < items.size(); i++) {
                repo.insertRequiredTest(req.batchKey(), items.get(i), i + 1);
            }
            if (potencyGroupComplete) {
                repo.insertPotencyModel(new BatchRepository.PotencyModelRow(0L, req.batchKey(), 1,
                        scale6(req.initialPotency()), req.producedAt().toString(),
                        scale6(req.dailyDecayRate()), scale6(req.minReleasePotency()),
                        req.commandKey(), now));
            }
            BatchResponse body = new BatchResponse(req.batchKey(), req.productCode(), req.batchNo(),
                    req.producedAt(), BatchStatus.QUARANTINED, items, Instant.parse(now),
                    potencyView(req.batchKey(), scale6(req.quantity())));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 提交检验结果：testKey 批次内幂等；任一 FAIL 立即 REJECTED；全部必做项 PASS 后 PENDING_RELEASE。
     */
    public StoredResponse submitTest(String batchKey, SubmitTestRequest req) {
        Map<String, Object> fp = canonical("test");
        fp.put("batchKey", batchKey);
        fp.put("testKey", req.testKey());
        fp.put("testItem", req.testItem());
        fp.put("result", req.result().name());
        fp.put("inspector", req.inspector());
        String fingerprint = fingerprints.sha256(fp);
        return executeIdempotent(CMD_TEST, req.commandKey(), fingerprint, () -> {
            BatchRepository.BatchRow batch = repo.findBatchForUpdate(batchKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));

            var existing = repo.findTest(batchKey, req.testKey());
            if (existing.isPresent()) {
                BatchRepository.TestRow row = existing.get();
                boolean sameContent = row.testItem().equals(req.testItem())
                        && row.outcome().equals(req.result().name())
                        && row.inspector().equals(req.inspector());
                if (!sameContent) {
                    throw ApiException.conflict("testKey 已以不同内容提交: " + req.testKey());
                }
                List<String> required = repo.findRequiredTests(batchKey);
                BatchStatus snapshot = snapshotAfter(row, repo.findTests(batchKey), required);
                return new StoredResponse(200, toJson(toTestResponse(row, snapshot.name())));
            }

            BatchStatus status = BatchStatus.valueOf(batch.status());
            assertNoRecalledAncestor(batchKey);
            if (status == BatchStatus.REJECTED || status == BatchStatus.RELEASED
                    || status == BatchStatus.RECALLED || status == BatchStatus.SPLIT) {
                throw ApiException.conflict("批次状态 " + status + " 不允许提交检验");
            }
            List<String> required = repo.findRequiredTests(batchKey);
            if (!required.contains(req.testItem())) {
                throw ApiException.unprocessable("检验项不属于该批次必做项: " + req.testItem());
            }
            boolean itemAlreadyTested = repo.findTests(batchKey).stream()
                    .anyMatch(t -> t.testItem().equals(req.testItem()));
            if (itemAlreadyTested) {
                throw ApiException.conflict("该检验项已存在检验结果: " + req.testItem());
            }

            String now = now();
            repo.insertTest(new BatchRepository.TestRow(0L, batchKey, req.testKey(), req.testItem(),
                    req.result().name(), req.inspector(), now));

            BatchStatus newStatus = status;
            if (req.result() == TestOutcome.FAIL) {
                newStatus = BatchStatus.REJECTED;
            } else if (status == BatchStatus.QUARANTINED && allRequiredPassed(batchKey, required)) {
                newStatus = BatchStatus.PENDING_RELEASE;
            }
            if (newStatus != status) {
                repo.updateStatus(batchKey, newStatus.name());
            }
            TestResultResponse body = new TestResultResponse(batchKey, req.testKey(), req.testItem(),
                    req.result(), req.inspector(), newStatus, Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 批准：两步放行。首个有效批准进入 RELEASE_REVIEW，第二个不同角色、不同批准人批准后 RELEASED。
     * 第二批准时若批次登记了效价模型，在同一事务、同一行锁内按最新模型版本计算当前效价：
     * 低于最低放行阈值返回 422（含实际值/要求值/差额），整事务回滚不留批准与固化记录；
     * 合格则固化计算时刻、模型版本、输入效价与舍入结果后放行。
     */
    public StoredResponse approve(String batchKey, String actorId, String roleHeader, ApproveRequest req) {
        if (actorId == null || actorId.isBlank()) {
            throw ApiException.badRequest("X-Actor-Id 不能为空");
        }
        ApprovalRole role = parseRole(roleHeader);
        String actor = actorId.trim();
        Map<String, Object> fp = canonical("approve");
        fp.put("batchKey", batchKey);
        fp.put("actorId", actor);
        fp.put("role", role.name());
        String fingerprint = fingerprints.sha256(fp);
        return executeIdempotent(CMD_APPROVE, req.commandKey(), fingerprint, () -> {
            BatchRepository.BatchRow batch = repo.findBatchForUpdate(batchKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
            BatchStatus status = BatchStatus.valueOf(batch.status());
            assertNoRecalledAncestor(batchKey);
            if (status == BatchStatus.QUARANTINED) {
                throw ApiException.unprocessable("必做检验项未全部通过，不能批准");
            }
            if (status != BatchStatus.PENDING_RELEASE && status != BatchStatus.RELEASE_REVIEW) {
                throw ApiException.conflict("批次状态 " + status + " 不允许批准");
            }
            boolean actorInspected = repo.findTests(batchKey).stream()
                    .anyMatch(t -> t.inspector().equals(actor));
            if (actorInspected) {
                throw ApiException.unprocessable("批准人不得为该批次任一检验结果的检验人: " + actor);
            }

            List<BatchRepository.ApprovalRow> approvals = repo.findApprovals(batchKey);
            int seq;
            BatchStatus newStatus;
            if (approvals.isEmpty()) {
                seq = 1;
                newStatus = BatchStatus.RELEASE_REVIEW;
            } else {
                BatchRepository.ApprovalRow first = approvals.get(0);
                if (first.role().equals(role.name())) {
                    throw ApiException.conflict("角色 " + role + " 已批准过，需要另一种角色");
                }
                if (first.actorId().equals(actor)) {
                    throw ApiException.conflict("两个批准人必须不同: " + actor);
                }
                seq = 2;
                newStatus = BatchStatus.RELEASED;
            }
            String now = now();
            Instant at = Instant.parse(now);

            // 效价放行门仅在第二批准（真正放行）时执行，读取此刻最新已提交模型版本。
            PotencyCalculationView calc = null;
            if (seq == 2) {
                calc = evaluateReleasePotency(batch, req.commandKey(), at);
            }

            repo.insertApproval(new BatchRepository.ApprovalRow(0L, batchKey, req.commandKey(),
                    actor, role.name(), seq, now));
            repo.updateStatus(batchKey, newStatus.name());
            if (calc != null) {
                persistCalculation(new BatchRepository.PotencyCalculationRow(0L, batchKey, OP_APPROVE,
                        req.commandKey(), calc.modelVersion(), calc.calculatedAt().toString(),
                        toJson(calc.inputs()), calc.resultPotency(), calc.thresholdPotency(), true, now));
            }
            ApprovalResponse body = new ApprovalResponse(batchKey, actor, role, seq, newStatus,
                    at, calc);
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 召回：RELEASED 或 SPLIT 批次可召回，召回后进入 RECALLED 并不再出现在可用批次查询中。
     * 召回提交后其全部后代立即不可用，但后代自身状态与既有记录不改写，也不为后代补写召回记录。
     */
    public StoredResponse recall(String batchKey, String actorId, RecallRequest req) {
        if (actorId == null || actorId.isBlank()) {
            throw ApiException.badRequest("X-Actor-Id 不能为空");
        }
        String actor = actorId.trim();
        Map<String, Object> fp = canonical("recall");
        fp.put("batchKey", batchKey);
        fp.put("actorId", actor);
        fp.put("reason", req.reason());
        String fingerprint = fingerprints.sha256(fp);
        return executeIdempotent(CMD_RECALL, req.commandKey(), fingerprint, () -> {
            BatchRepository.BatchRow batch = repo.findBatchForUpdate(batchKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
            BatchStatus status = BatchStatus.valueOf(batch.status());
            if (status != BatchStatus.RELEASED && status != BatchStatus.SPLIT) {
                throw ApiException.conflict("批次状态 " + status + " 不允许召回，仅 RELEASED 或 SPLIT 可召回");
            }
            // 逐行锁定全部后代（按业务键排序保证锁顺序确定）：与后代上的操作互斥，
            // 按事务提交顺序裁决——召回先提交则后代新操作看到召回并返回 422。
            List<String> descendants = descendantKeys(batchKey);
            Collections.sort(descendants);
            for (String descendantKey : descendants) {
                repo.findBatchForUpdate(descendantKey);
            }
            String now = now();
            repo.insertRecall(new BatchRepository.RecallRow(0L, batchKey, req.commandKey(),
                    actor, req.reason(), now));
            repo.updateStatus(batchKey, BatchStatus.RECALLED.name());
            RecallResponse body = new RecallResponse(batchKey, actor, req.reason(),
                    BatchStatus.RECALLED, Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 效价模型修订：只追加新版本，锚点与数量不变；与检验/放行按 batch 行锁提交顺序裁决。
     * 负值判 422；终态批次（REJECTED/RECALLED/SPLIT）不允许修订。
     */
    public StoredResponse revisePotency(String batchKey, String actorId, PotencyRevisionRequest req) {
        if (actorId == null || actorId.isBlank()) {
            throw ApiException.badRequest("X-Actor-Id 不能为空");
        }
        String actor = actorId.trim();
        assertNonNegativePotency(req.initialPotency(), "initialPotency");
        assertNonNegativePotency(req.dailyDecayRate(), "dailyDecayRate");
        assertNonNegativePotency(req.minReleasePotency(), "minReleasePotency");
        Map<String, Object> fp = canonical("potency-revision");
        fp.put("batchKey", batchKey);
        fp.put("actorId", actor);
        fp.put("initialPotency", scale6(req.initialPotency()));
        fp.put("dailyDecayRate", scale6(req.dailyDecayRate()));
        fp.put("minReleasePotency", scale6(req.minReleasePotency()));
        String fingerprint = fingerprints.sha256(fp);
        return executeIdempotent(CMD_POTENCY_REVISION, req.commandKey(), fingerprint, () -> {
            BatchRepository.BatchRow batch = repo.findBatchForUpdate(batchKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
            BatchRepository.PotencyModelRow current = repo.findLatestPotencyModel(batchKey)
                    .orElseThrow(() -> ApiException.unprocessable("批次未登记效价模型，不能修订: " + batchKey));
            BatchStatus status = BatchStatus.valueOf(batch.status());
            if (status == BatchStatus.REJECTED || status == BatchStatus.RECALLED
                    || status == BatchStatus.SPLIT) {
                throw ApiException.conflict("批次状态 " + status + " 不允许修订效价模型");
            }
            int nextVersion = current.version() + 1;
            String now = now();
            repo.insertPotencyModel(new BatchRepository.PotencyModelRow(0L, batchKey, nextVersion,
                    scale6(req.initialPotency()), current.anchorAt(), scale6(req.dailyDecayRate()),
                    scale6(req.minReleasePotency()), req.commandKey(), now));
            PotencyRevisionResponse body = new PotencyRevisionResponse(batchKey,
                    potencyView(batchKey, batch.quantity()), Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 当前可用批次：排除已召回（RECALLED）、已拆分（SPLIT）批次，
     * 以及任一祖先被召回的后代批次；后代自身状态不改写。
     */
    public List<BatchResponse> listAvailable() {
        Map<String, List<String>> parentsOf = childToParents();
        Set<String> recalled = new HashSet<>(repo.findRecalledKeys());
        return repo.findAvailableBatches().stream()
                .filter(b -> !BatchStatus.SPLIT.name().equals(b.status()))
                .filter(b -> recalledAncestors(b.batchKey(), parentsOf, recalled).isEmpty())
                .map(this::toBatchResponse)
                .toList();
    }

    /**
     * 批次完整历史：批次概要 + 全部检验 + 全部批准 + 召回记录 + 效价固化计算，
     * 历史不因召回或模型修订而删除或改写；字段仅末尾追加，排序与 null 语义稳定。
     */
    public BatchHistoryResponse history(String batchKey) {
        BatchRepository.BatchRow batch = repo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        List<String> required = repo.findRequiredTests(batchKey);
        List<BatchRepository.TestRow> testRows = repo.findTests(batchKey);
        // 按提交顺序推导每条检验结果落定后的批次状态快照：FAIL→REJECTED；
        // 必做项全部 PASS 时该条→PENDING_RELEASE；其余→QUARANTINED。
        List<TestResultResponse> tests = new ArrayList<>(testRows.size());
        for (BatchRepository.TestRow t : testRows) {
            tests.add(toTestResponse(t, snapshotAfter(t, testRows, required).name()));
        }
        Map<String, PotencyCalculationView> approveCalcsByCommand = new HashMap<>();
        for (BatchRepository.PotencyCalculationRow row : repo.findPotencyCalculations(batchKey)) {
            if (OP_APPROVE.equals(row.operationType())) {
                approveCalcsByCommand.put(row.commandKey(), toCalculationView(row));
            }
        }
        List<ApprovalResponse> approvals = repo.findApprovals(batchKey).stream()
                .map(a -> {
                    // 历史快照：第一笔批准落定 RELEASE_REVIEW，第二笔落定 RELEASED；不随后续召回/修订改写
                    BatchStatus snapshot = a.seq() == 1
                            ? BatchStatus.RELEASE_REVIEW
                            : BatchStatus.RELEASED;
                    return new ApprovalResponse(a.batchKey(), a.actorId(), ApprovalRole.valueOf(a.role()),
                            a.seq(), snapshot, Instant.parse(a.createdAt()),
                            approveCalcsByCommand.get(a.commandKey()));
                })
                .toList();
        RecallResponse recall = repo.findRecall(batchKey)
                .map(r -> new RecallResponse(r.batchKey(), r.actorId(), r.reason(),
                        BatchStatus.RECALLED, Instant.parse(r.createdAt())))
                .orElse(null);
        List<PotencyCalculationView> calculations = repo.findPotencyCalculations(batchKey).stream()
                .map(this::toCalculationView)
                .toList();
        return new BatchHistoryResponse(toBatchResponse(batch), tests, approvals, recall, calculations);
    }

    /**
     * 效价只读诊断：当前模型版本、诊断时刻当前效价/阈值/余量、全部历史版本与固化记录；不修改状态。
     */
    public PotencyDiagnosticsResponse potencyDiagnostics(String batchKey) {
        BatchRepository.BatchRow batch = repo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        Instant at = timeSource.now();
        List<PotencyView> versions = repo.findPotencyModels(batchKey).stream()
                .map(m -> toPotencyView(m, batch.quantity()))
                .toList();
        PotencyDiagnosticsResponse.CurrentPotency current = repo.findLatestPotencyModel(batchKey)
                .map(m -> {
                    PotencyModel model = toModel(m);
                    BigDecimal actual = PotencyCalculator.currentPotency(model, at);
                    BigDecimal margin = PotencyCalculator.margin(actual, model.minReleasePotency());
                    return new PotencyDiagnosticsResponse.CurrentPotency(model.version(),
                            model.anchor(), model.dailyDecayRate(), actual,
                            model.minReleasePotency(), margin,
                            PotencyCalculator.eligible(actual, model.minReleasePotency()));
                })
                .orElse(null);
        List<PotencyCalculationView> calculations = repo.findPotencyCalculations(batchKey).stream()
                .map(this::toCalculationView)
                .toList();
        return new PotencyDiagnosticsResponse(batchKey, at, current, versions, calculations);
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
                    repo.insertCommand(new BatchRepository.CommandRow(type, commandKey, fingerprint,
                            response.status(), response.body()), now());
                    return response;
                });
            } catch (DuplicateKeyException e) {
                // 并发同事务键冲突：回滚后重试，读取对方已提交的命令快照或业务结果
            }
        }
        throw ApiException.conflict("命令并发冲突，请重试: " + commandKey);
    }

    /**
     * 查询命令快照：命中且指纹一致返回首次响应；指纹不一致抛 409；未命中返回空。
     */
    private Optional<StoredResponse> loggedResponse(String type, String commandKey,
                                                    String fingerprint) {
        var existing = repo.findCommand(type, commandKey);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        BatchRepository.CommandRow row = existing.get();
        if (!row.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("commandKey 已以不同参数使用: " + commandKey);
        }
        return Optional.of(new StoredResponse(row.responseStatus(), row.responseBody()));
    }

    private boolean allRequiredPassed(String batchKey, List<String> required) {
        Set<String> passed = new HashSet<>();
        for (BatchRepository.TestRow t : repo.findTests(batchKey)) {
            if (TestOutcome.PASS.name().equals(t.outcome())) {
                passed.add(t.testItem());
            }
        }
        return passed.containsAll(required);
    }

    private BatchResponse toBatchResponse(BatchRepository.BatchRow row) {
        return new BatchResponse(row.batchKey(), row.productCode(), row.batchNo(),
                Instant.parse(row.producedAt()), BatchStatus.valueOf(row.status()),
                repo.findRequiredTests(row.batchKey()), Instant.parse(row.createdAt()),
                potencyView(row.batchKey(), row.quantity()));
    }

    private TestResultResponse toTestResponse(BatchRepository.TestRow row, String batchStatus) {
        return new TestResultResponse(row.batchKey(), row.testKey(), row.testItem(),
                TestOutcome.valueOf(row.outcome()), row.inspector(),
                BatchStatus.valueOf(batchStatus), Instant.parse(row.createdAt()));
    }

    /**
     * 推导某条检验结果提交落定后的批次状态快照（按检验结果提交顺序）：
     * FAIL→REJECTED；此前已有 PASS 已覆盖全部必做项→PENDING_RELEASE；其余→QUARANTINED。
     */
    private BatchStatus snapshotAfter(BatchRepository.TestRow target,
                                      List<BatchRepository.TestRow> ordered,
                                      List<String> required) {
        if (TestOutcome.FAIL.name().equals(target.outcome())) {
            return BatchStatus.REJECTED;
        }
        Set<String> passed = new HashSet<>();
        for (BatchRepository.TestRow t : ordered) {
            if (TestOutcome.PASS.name().equals(t.outcome())) {
                passed.add(t.testItem());
            }
            if (t.id() == target.id()) {
                break;
            }
        }
        return passed.containsAll(required)
                ? BatchStatus.PENDING_RELEASE
                : BatchStatus.QUARANTINED;
    }

    private ApprovalRole parseRole(String roleHeader) {
        if (roleHeader == null || roleHeader.isBlank()) {
            throw ApiException.badRequest("X-Approval-Role 不能为空");
        }
        try {
            return ApprovalRole.valueOf(roleHeader.trim());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("X-Approval-Role 必须为 QUALITY 或 OPERATIONS");
        }
    }

    // ---------- 效价计算与固化 ----------

    /**
     * 放行效价门：读取调用方已持有行锁的批次最新模型，按 at 计算当前效价；
     * 低于阈值抛 422（携带实际值/要求值/差额，事务随之回滚）；合格返回单输入固化视图。
     */
    private PotencyCalculationView evaluateReleasePotency(BatchRepository.BatchRow batch,
                                                          String commandKey, Instant at) {
        BatchRepository.PotencyModelRow modelRow = repo.findLatestPotencyModel(batch.batchKey())
                .orElse(null);
        if (modelRow == null) {
            return null;
        }
        PotencyModel model = toModel(modelRow);
        BigDecimal actual = PotencyCalculator.currentPotency(model, at);
        if (!PotencyCalculator.eligible(actual, model.minReleasePotency())) {
            throw ApiException.unprocessable(
                    "当前效价低于最低放行效价，不能放行: " + batch.batchKey(),
                    new PotencyRejectionResponse("PRECONDITION_FAILED",
                            "当前效价低于最低放行效价，不能放行",
                            batch.batchKey(), model.version(), at, actual,
                            model.minReleasePotency(),
                            PotencyCalculator.margin(actual, model.minReleasePotency())));
        }
        PotencyCalculationView.CalculationInput input = new PotencyCalculationView.CalculationInput(
                batch.batchKey(), scale6(batch.quantity()), model.version(), model.anchor(),
                actual, PotencyCalculator.elapsedDays(model.anchor(), at));
        return new PotencyCalculationView(batch.batchKey(), OP_APPROVE, commandKey, model.version(),
                at, List.of(input), actual, model.minReleasePotency(), true);
    }

    private void persistCalculation(BatchRepository.PotencyCalculationRow row) {
        repo.insertPotencyCalculation(row);
    }

    private PotencyModel toModel(BatchRepository.PotencyModelRow row) {
        return new PotencyModel(row.version(), row.initialPotency(), Instant.parse(row.anchorAt()),
                row.dailyDecayRate(), row.minReleasePotency(), row.commandKey(),
                Instant.parse(row.createdAt()));
    }

    private PotencyView toPotencyView(BatchRepository.PotencyModelRow row, BigDecimal quantity) {
        return new PotencyView(row.version(), scale6(quantity), row.initialPotency(),
                Instant.parse(row.anchorAt()), row.dailyDecayRate(), row.minReleasePotency());
    }

    private PotencyView potencyView(String batchKey, BigDecimal quantity) {
        return repo.findLatestPotencyModel(batchKey)
                .map(row -> toPotencyView(row, quantity))
                .orElse(null);
    }

    private PotencyCalculationView toCalculationView(BatchRepository.PotencyCalculationRow row) {
        List<PotencyCalculationView.CalculationInput> inputs;
        try {
            JsonNode array = objectMapper.readTree(row.inputs());
            inputs = new ArrayList<>(array.size());
            for (JsonNode node : array) {
                inputs.add(new PotencyCalculationView.CalculationInput(
                        node.path("batchKey").asText(),
                        new BigDecimal(node.path("weight").asText()),
                        node.path("modelVersion").asInt(),
                        Instant.parse(node.path("anchorAt").asText()),
                        new BigDecimal(node.path("inputPotency").asText()),
                        new BigDecimal(node.path("elapsedDays").asText())));
            }
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("固化输入解析失败", e);
        }
        return new PotencyCalculationView(row.batchKey(), row.operationType(), row.commandKey(),
                row.modelVersion(), Instant.parse(row.calculatedAt()), List.copyOf(inputs),
                row.resultPotency(), row.thresholdPotency(), row.eligible());
    }

    private void assertPositiveQuantity(BigDecimal quantity) {
        if (quantity.signum() <= 0) {
            throw ApiException.unprocessable("数量必须为正数");
        }
    }

    private void assertNonNegativePotency(BigDecimal value, String field) {
        if (value.signum() < 0) {
            throw ApiException.unprocessable(field + " 不能为负");
        }
    }

    private static BigDecimal scale6(BigDecimal value) {
        return value == null ? null : value.setScale(PotencyCalculator.SCALE, java.math.RoundingMode.HALF_UP);
    }

    // ---------- 拆分 ----------

    /**
     * 拆分：仅当前可用的 RELEASED 批次可一次拆成 2～5 个全新子批。
     * 子批继承产品编码、生产 UTC 时间与必做检验项，初始 QUARANTINED，不继承检验或批准记录；
     * 父批置为 SPLIT。登记数量/效价的父批：子批数量必填为正且之和严格守恒，
     * 每个子批以父批在操作时刻的当前效价为初始效价建立 v1 模型（锚点=拆分时刻），衰减率与阈值继承。
     */
    public StoredResponse split(String parentKey, SplitRequest req) {
        List<SplitRequest.ChildSpec> children = req.children();
        List<String> childKeys = children.stream().map(SplitRequest.ChildSpec::batchKey).toList();
        if (new HashSet<>(childKeys).size() != childKeys.size()) {
            throw ApiException.conflict("子批 batchKey 在请求内重复");
        }
        Map<String, Object> fp = canonical("split");
        fp.put("parentKey", parentKey);
        List<Map<String, Object>> childParams = new ArrayList<>();
        for (SplitRequest.ChildSpec child : children) {
            Map<String, Object> c = new TreeMap<>();
            c.put("batchKey", child.batchKey());
            c.put("batchNo", child.batchNo());
            c.put("quantity", child.quantity() == null ? null : scale6(child.quantity()));
            childParams.add(c);
        }
        fp.put("children", childParams);
        String fingerprint = fingerprints.sha256(fp);
        return executeIdempotent(CMD_SPLIT, req.commandKey(), fingerprint, () -> {
            BatchRepository.BatchRow parent = repo.findBatchForUpdate(parentKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + parentKey));
            // 父批行锁后重查命令快照：并发同键请求在锁等待期间可能已由对方提交，
            // 此时须重放首次结果而不是因父批已 SPLIT 误判冲突
            var logged = loggedResponse(CMD_SPLIT, req.commandKey(), fingerprint);
            if (logged.isPresent()) {
                return logged.get();
            }
            assertNoRecalledAncestor(parentKey);
            if (!BatchStatus.RELEASED.name().equals(parent.status())) {
                throw ApiException.conflict(
                        "批次状态 " + parent.status() + " 不允许拆分，仅当前可用的 RELEASED 批次可拆分");
            }
            for (String childKey : childKeys) {
                if (repo.findBatch(childKey).isPresent()) {
                    throw ApiException.conflict("子批 batchKey 已存在: " + childKey);
                }
            }

            boolean tracked = parent.quantity() != null;
            if (tracked) {
                for (SplitRequest.ChildSpec child : children) {
                    if (child.quantity() == null) {
                        throw ApiException.badRequest("父批已登记数量，子批 quantity 必填");
                    }
                    assertPositiveQuantity(child.quantity());
                }
                BigDecimal allocated = children.stream()
                        .map(c -> scale6(c.quantity()))
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                if (allocated.compareTo(scale6(parent.quantity())) != 0) {
                    throw ApiException.unprocessable("子批分摊数量之和必须等于父批数量",
                            new QuantityMismatch("PRECONDITION_FAILED",
                                    "子批分摊数量之和必须等于父批数量", parentKey,
                                    allocated, scale6(parent.quantity()),
                                    allocated.subtract(scale6(parent.quantity()))));
                }
            } else {
                for (SplitRequest.ChildSpec child : children) {
                    if (child.quantity() != null) {
                        throw ApiException.badRequest("父批未登记数量，子批不得提供 quantity");
                    }
                }
            }

            String now = now();
            Instant at = Instant.parse(now);
            List<String> required = repo.findRequiredTests(parentKey);

            // 父批操作时刻效价与模型（单父加权即自身），作为子批 v1 初始效价；
            // 拆分不另设效价阈值门，只要求以父批操作时刻效价固化，门控已在放行时完成。
            PotencyModel parentModel = null;
            BigDecimal parentPotency = null;
            BigDecimal elapsedDays = null;
            if (tracked) {
                BatchRepository.PotencyModelRow modelRow = repo.findLatestPotencyModel(parentKey)
                        .orElseThrow(() -> new IllegalStateException("已登记数量的批次必须有效价模型"));
                parentModel = toModel(modelRow);
                parentPotency = PotencyCalculator.currentPotency(parentModel, at);
                elapsedDays = PotencyCalculator.elapsedDays(parentModel.anchor(), at);
            }

            // 全部前置条件验证通过后在同一事务内提交完整后态。
            List<SplitResponse.SplitChild> childBodies = new ArrayList<>(children.size());
            for (int i = 0; i < children.size(); i++) {
                SplitRequest.ChildSpec spec = children.get(i);
                BigDecimal childQuantity = tracked ? scale6(spec.quantity()) : null;
                BigDecimal childInitial = tracked ? parentPotency : null;
                BigDecimal childRate = tracked ? parentModel.dailyDecayRate() : null;
                BigDecimal childThreshold = tracked ? parentModel.minReleasePotency() : null;
                String childAnchor = tracked ? now : null;
                repo.insertBatch(new BatchRepository.BatchRow(0L, spec.batchKey(),
                        parent.productCode(), spec.batchNo(), parent.producedAt(),
                        BatchStatus.QUARANTINED.name(), now, childQuantity, childInitial,
                        childThreshold, childRate, childAnchor));
                for (int j = 0; j < required.size(); j++) {
                    repo.insertRequiredTest(spec.batchKey(), required.get(j), j + 1);
                }
                if (tracked) {
                    repo.insertPotencyModel(new BatchRepository.PotencyModelRow(0L, spec.batchKey(), 1,
                            childInitial, childAnchor, childRate, childThreshold,
                            req.commandKey(), now));
                    PotencyCalculationView.CalculationInput input =
                            new PotencyCalculationView.CalculationInput(parentKey, childQuantity,
                                    parentModel.version(), parentModel.anchor(), parentPotency,
                                    elapsedDays);
                    PotencyCalculationView calc = new PotencyCalculationView(spec.batchKey(),
                            OP_SPLIT, req.commandKey(), parentModel.version(), at, List.of(input),
                            parentPotency, childThreshold, true);
                    repo.insertPotencyCalculation(new BatchRepository.PotencyCalculationRow(0L,
                            spec.batchKey(), OP_SPLIT, req.commandKey(), parentModel.version(), now,
                            toJson(calc.inputs()), parentPotency, childThreshold, true, now));
                }
                repo.insertLineage(new BatchRepository.LineageRow(0L, parentKey, spec.batchKey(),
                        i + 1, childQuantity, now));
                childBodies.add(new SplitResponse.SplitChild(spec.batchKey(), spec.batchNo(),
                        parent.productCode(), Instant.parse(parent.producedAt()),
                        BatchStatus.QUARANTINED, required, childQuantity,
                        tracked ? new PotencyView(1, childQuantity, childInitial, at, childRate,
                                childThreshold) : null,
                        Instant.parse(now)));
            }
            repo.updateStatus(parentKey, BatchStatus.SPLIT.name());
            SplitResponse body = new SplitResponse(parentKey, BatchStatus.SPLIT, childBodies, at);
            return new StoredResponse(201, toJson(body));
        });
    }

    // ---------- 合批 ----------

    /**
     * 合批：2～5 个当前可用 RELEASED 父批按投入数量加权产生一个全新批次，父批整体消耗并置为 SPLIT。
     * 先在锁内验证完整后态（父批存在/已放行/无召回祖先/整体投入/新批数量守恒/子批键唯一/
     * 各父批操作时刻效价均合格），再在一个事务提交全部行；任一不满足整次回滚，不留部分结果。
     * 新批初始效价=Σ(投入数量×父批当时效价)/Σ投入数量（HALF_UP 6 位），
     * 衰减率与最低阈值同样按投入数量加权继承，锚点=合批时刻，必做检验项取父批并集，初始 QUARANTINED。
     */
    public StoredResponse merge(MergeRequest req) {
        List<MergeRequest.ParentInput> parents = req.parents();
        MergeRequest.ChildSpec child = req.child();
        List<String> parentKeys = parents.stream().map(MergeRequest.ParentInput::batchKey).toList();
        if (new HashSet<>(parentKeys).size() != parentKeys.size()) {
            throw ApiException.unprocessable("合批父批 batchKey 在请求内重复");
        }
        assertPositiveQuantity(child.quantity());
        if (parentKeys.contains(child.batchKey())) {
            throw ApiException.unprocessable("新批 batchKey 不得与任一父批相同");
        }
        Map<String, Object> fp = canonical("merge");
        List<Map<String, Object>> parentParams = new ArrayList<>();
        for (MergeRequest.ParentInput p : parents) {
            Map<String, Object> m = new TreeMap<>();
            m.put("batchKey", p.batchKey());
            m.put("quantity", scale6(p.quantity()));
            parentParams.add(m);
        }
        fp.put("parents", parentParams);
        Map<String, Object> childParams = new TreeMap<>();
        childParams.put("batchKey", child.batchKey());
        childParams.put("batchNo", child.batchNo());
        childParams.put("quantity", scale6(child.quantity()));
        fp.put("child", childParams);
        String fingerprint = fingerprints.sha256(fp);
        return executeIdempotent(CMD_MERGE, req.commandKey(), fingerprint, () -> {
            // 按业务键排序锁定全部父批，避免多父事务死锁；快照在锁内重读。
            List<String> orderedKeys = new ArrayList<>(parentKeys);
            Collections.sort(orderedKeys);
            Map<String, BatchRepository.BatchRow> locked = new HashMap<>();
            for (BatchRepository.BatchRow row : repo.findBatchesForUpdateOrdered(orderedKeys)) {
                locked.put(row.batchKey(), row);
            }
            var logged = loggedResponse(CMD_MERGE, req.commandKey(), fingerprint);
            if (logged.isPresent()) {
                return logged.get();
            }
            if (repo.findBatch(child.batchKey()).isPresent()) {
                throw ApiException.conflict("新批 batchKey 已存在: " + child.batchKey());
            }
            for (MergeRequest.ParentInput input : parents) {
                BatchRepository.BatchRow parent = locked.get(input.batchKey());
                if (parent == null) {
                    throw ApiException.notFound("父批不存在: " + input.batchKey());
                }
                assertPositiveQuantity(input.quantity());
                if (parent.quantity() == null) {
                    throw ApiException.unprocessable(
                            "父批未登记数量与效价模型，不能合批: " + input.batchKey());
                }
                BatchStatus status = BatchStatus.valueOf(parent.status());
                if (status == BatchStatus.QUARANTINED || status == BatchStatus.PENDING_RELEASE
                        || status == BatchStatus.RELEASE_REVIEW) {
                    throw ApiException.unprocessable(
                            "父批未完成检验放行，不能合批: " + input.batchKey());
                }
                if (status != BatchStatus.RELEASED) {
                    throw ApiException.conflict(
                            "父批状态 " + status + " 不允许合批: " + input.batchKey());
                }
                assertNoRecalledAncestor(input.batchKey());
                if (input.quantity().compareTo(parent.quantity()) != 0) {
                    throw ApiException.unprocessable(
                            "合批必须整体投入父批全部数量: " + input.batchKey(),
                            new QuantityMismatch("PRECONDITION_FAILED",
                                    "合批必须整体投入父批全部数量", input.batchKey(),
                                    scale6(input.quantity()), scale6(parent.quantity()),
                                    scale6(input.quantity()).subtract(scale6(parent.quantity()))));
                }
            }

            BigDecimal invested = parents.stream()
                    .map(p -> scale6(p.quantity()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (invested.compareTo(scale6(child.quantity())) != 0) {
                throw ApiException.unprocessable("新批数量必须等于全部父批投入数量之和",
                        new QuantityMismatch("PRECONDITION_FAILED",
                                "新批数量必须等于全部父批投入数量之和", child.batchKey(),
                                scale6(child.quantity()), invested,
                                scale6(child.quantity()).subtract(invested)));
            }

            String now = now();
            Instant at = Instant.parse(now);

            // 各父批操作时刻模型与效价；任一不合格立即整次回滚。
            List<ParentSnapshot> snapshots = new ArrayList<>(parents.size());
            List<BigDecimal> weights = new ArrayList<>();
            List<BigDecimal> potencies = new ArrayList<>();
            List<BigDecimal> rates = new ArrayList<>();
            List<BigDecimal> thresholds = new ArrayList<>();
            for (MergeRequest.ParentInput input : parents) {
                BatchRepository.BatchRow parent = locked.get(input.batchKey());
                PotencyModel model = toModel(repo.findLatestPotencyModel(input.batchKey())
                        .orElseThrow(() -> new IllegalStateException(
                                "已登记数量的批次必须有效价模型: " + input.batchKey())));
                BigDecimal current = PotencyCalculator.currentPotency(model, at);
                if (!PotencyCalculator.eligible(current, model.minReleasePotency())) {
                    throw ApiException.unprocessable(
                            "父批当前效价不合格，整次合批回滚: " + input.batchKey(),
                            new PotencyRejectionResponse("PRECONDITION_FAILED",
                                    "父批当前效价不合格，整次合批回滚",
                                    input.batchKey(), model.version(), at, current,
                                    model.minReleasePotency(),
                                    PotencyCalculator.margin(current, model.minReleasePotency())));
                }
                BigDecimal weight = scale6(input.quantity());
                weights.add(weight);
                potencies.add(current);
                rates.add(model.dailyDecayRate());
                thresholds.add(model.minReleasePotency());
                snapshots.add(new ParentSnapshot(parent, model, weight, current,
                        PotencyCalculator.elapsedDays(model.anchor(), at)));
            }

            BigDecimal weightedInitial = PotencyCalculator.weightedAverage(weights, potencies);
            BigDecimal weightedRate = PotencyCalculator.weightedAverage(weights, rates);
            BigDecimal weightedThreshold = PotencyCalculator.weightedAverage(weights, thresholds);

            // 完整后态验证通过：一个事务内创建新批、模型、血缘、固化记录并置父批 SPLIT。
            BatchRepository.BatchRow firstParent = locked.get(parents.get(0).batchKey());
            Set<String> unionedTests = new java.util.TreeSet<>();
            for (MergeRequest.ParentInput input : parents) {
                unionedTests.addAll(repo.findRequiredTests(input.batchKey()));
            }
            List<String> required = List.copyOf(unionedTests);
            repo.insertBatch(new BatchRepository.BatchRow(0L, child.batchKey(),
                    firstParent.productCode(), child.batchNo(), now,
                    BatchStatus.QUARANTINED.name(), now, scale6(child.quantity()), weightedInitial,
                    weightedThreshold, weightedRate, now));
            int seq = 1;
            List<MergeResponse.MergedParent> parentBodies = new ArrayList<>();
            List<PotencyCalculationView.CalculationInput> calcInputs = new ArrayList<>();
            for (ParentSnapshot snapshot : snapshots) {
                repo.insertLineage(new BatchRepository.LineageRow(0L,
                        snapshot.parent().batchKey(), child.batchKey(), seq++,
                        snapshot.weight(), now));
                repo.updateStatus(snapshot.parent().batchKey(), BatchStatus.SPLIT.name());
                parentBodies.add(new MergeResponse.MergedParent(snapshot.parent().batchKey(),
                        snapshot.weight(), snapshot.model().version(), snapshot.inputPotency()));
                calcInputs.add(new PotencyCalculationView.CalculationInput(
                        snapshot.parent().batchKey(), snapshot.weight(), snapshot.model().version(),
                        snapshot.model().anchor(), snapshot.inputPotency(), snapshot.elapsedDays()));
            }
            for (int i = 0; i < required.size(); i++) {
                repo.insertRequiredTest(child.batchKey(), required.get(i), i + 1);
            }
            repo.insertPotencyModel(new BatchRepository.PotencyModelRow(0L, child.batchKey(), 1,
                    weightedInitial, now, weightedRate, weightedThreshold, req.commandKey(), now));
            PotencyCalculationView calc = new PotencyCalculationView(child.batchKey(), OP_MERGE,
                    req.commandKey(), MULTI_PARENT_MODEL_VERSION, at, calcInputs, weightedInitial,
                    weightedThreshold, true);
            repo.insertPotencyCalculation(new BatchRepository.PotencyCalculationRow(0L,
                    child.batchKey(), OP_MERGE, req.commandKey(), MULTI_PARENT_MODEL_VERSION, now,
                    toJson(calcInputs), weightedInitial, weightedThreshold, true, now));
            MergeResponse body = new MergeResponse(child.batchKey(), child.batchNo(),
                    firstParent.productCode(), at, BatchStatus.QUARANTINED, required,
                    scale6(child.quantity()),
                    new PotencyView(1, scale6(child.quantity()), weightedInitial, at, weightedRate,
                            weightedThreshold),
                    parentBodies, at, calc);
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 合批内部：父批行、模型版本、投入权重、操作时刻效价与经过 UTC 日数。
     */
    private record ParentSnapshot(BatchRepository.BatchRow parent, PotencyModel model,
                                  BigDecimal weight, BigDecimal inputPotency,
                                  BigDecimal elapsedDays) {
    }

    /**
     * 数量不守恒（422）诊断体：actual 为实际值，required 为要求值，margin 为差额。
     */
    public record QuantityMismatch(String code, String message, String batchKey,
                                   BigDecimal actualQuantity, BigDecimal requiredQuantity,
                                   BigDecimal margin) {
    }

    // ---------- 血缘查询（DAG：拆分一父多子，合批多父一子） ----------

    /**
     * 祖先查询：按血缘关系创建顺序广度优先展开（每个祖先首次出现的位置），
     * 每项含批次自身状态及导致其不可用的召回祖先。
     */
    public List<LineageEntryResponse> listAncestors(String batchKey) {
        repo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        Map<String, List<String>> parentsOf = childToParents();
        Set<String> recalled = new HashSet<>(repo.findRecalledKeys());
        List<LineageEntryResponse> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(batchKey);
        seen.add(batchKey);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (String parent : parentsOf.getOrDefault(current, List.of())) {
                if (seen.add(parent)) {
                    result.add(toLineageEntry(parent, parentsOf, recalled));
                    queue.add(parent);
                }
            }
        }
        return result;
    }

    /**
     * 后代查询：按血缘关系创建顺序广度优先展开（每个后代首次出现的位置），
     * 每项含批次自身状态及导致其不可用的召回祖先。
     */
    public List<LineageEntryResponse> listDescendants(String batchKey) {
        repo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        Map<String, List<String>> parentsOf = childToParents();
        Set<String> recalled = new HashSet<>(repo.findRecalledKeys());
        List<LineageEntryResponse> result = new ArrayList<>();
        for (String key : descendantKeys(batchKey)) {
            result.add(toLineageEntry(key, parentsOf, recalled));
        }
        return result;
    }

    /**
     * 若任一级祖先已被召回则抛 422：后代批次禁止新增检验、批准和拆分/合批。
     */
    private void assertNoRecalledAncestor(String batchKey) {
        List<String> recalled = recalledAncestors(batchKey, childToParents(),
                new HashSet<>(repo.findRecalledKeys()));
        if (!recalled.isEmpty()) {
            throw ApiException.unprocessable(
                    "祖先批次 " + recalled.get(0) + " 已召回，禁止新增检验、批准和血缘操作");
        }
    }

    /**
     * 沿父 DAG 向上查找全部被直接召回（RECALLED）的祖先业务键（BFS 首次发现顺序）；
     * 批次自身召回不算祖先召回。
     */
    private List<String> recalledAncestors(String batchKey, Map<String, List<String>> parentsOf,
                                           Set<String> recalled) {
        List<String> hits = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(batchKey);
        seen.add(batchKey);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (String parent : parentsOf.getOrDefault(current, List.of())) {
                if (seen.add(parent)) {
                    if (recalled.contains(parent)) {
                        hits.add(parent);
                    }
                    queue.add(parent);
                }
            }
        }
        return hits;
    }

    /**
     * 全部血缘边的 子批→父批列表 映射（DAG）；关系不可改写，只增不改。
     */
    private Map<String, List<String>> childToParents() {
        Map<String, List<String>> parentsOf = new HashMap<>();
        for (BatchRepository.LineageRow row : repo.findAllLineage()) {
            parentsOf.computeIfAbsent(row.childKey(), k -> new ArrayList<>()).add(row.parentKey());
        }
        return parentsOf;
    }

    /**
     * 某批次的全部后代业务键，按血缘关系创建顺序广度优先展开（不含自身，去重首次出现）。
     */
    private List<String> descendantKeys(String rootKey) {
        Map<String, List<String>> childrenOf = new HashMap<>();
        for (BatchRepository.LineageRow row : repo.findAllLineage()) {
            childrenOf.computeIfAbsent(row.parentKey(), k -> new ArrayList<>()).add(row.childKey());
        }
        List<String> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(rootKey);
        seen.add(rootKey);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (String child : childrenOf.getOrDefault(current, List.of())) {
                if (seen.add(child)) {
                    result.add(child);
                    queue.add(child);
                }
            }
        }
        return result;
    }

    private LineageEntryResponse toLineageEntry(String batchKey, Map<String, List<String>> parentsOf,
                                                Set<String> recalled) {
        BatchRepository.BatchRow row = repo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        List<String> recalledAncestors = recalledAncestors(batchKey, parentsOf, recalled);
        return new LineageEntryResponse(row.batchKey(), row.batchNo(),
                BatchStatus.valueOf(row.status()),
                recalledAncestors.isEmpty() ? null : recalledAncestors.get(0));
    }

    // ---------- 通用 ----------

    /**
     * 规范化指纹载体：键按字典序输出；集合/多对象由调用方排序后放入。
     */
    private Map<String, Object> canonical(String operation) {
        Map<String, Object> payload = new TreeMap<>();
        payload.put("operation", operation);
        return payload;
    }

    private String toJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    private String now() {
        return timeSource.now().toString();
    }
}
