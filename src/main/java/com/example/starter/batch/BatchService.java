package com.example.starter.batch;

import com.example.starter.batch.dto.ApprovalResponse;
import com.example.starter.batch.dto.ApproveRequest;
import com.example.starter.batch.dto.BatchHistoryResponse;
import com.example.starter.batch.dto.BatchResponse;
import com.example.starter.batch.dto.CreateBatchRequest;
import com.example.starter.batch.dto.LineageEntryResponse;
import com.example.starter.batch.dto.RecallRequest;
import com.example.starter.batch.dto.RecallResponse;
import com.example.starter.batch.dto.SplitRequest;
import com.example.starter.batch.dto.SplitResponse;
import com.example.starter.batch.dto.SubmitTestRequest;
import com.example.starter.batch.dto.CorrectWeighingRequest;
import com.example.starter.batch.dto.CreateRecipeRequest;
import com.example.starter.batch.dto.IngredientDifference;
import com.example.starter.batch.dto.RecipeResponse;
import com.example.starter.batch.dto.TestResultResponse;
import com.example.starter.batch.dto.WeighingChainResponse;
import com.example.starter.batch.dto.WeighingRequest;
import com.example.starter.batch.dto.WeighingResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 批次隔离与放行核心服务。
 *
 * <p>并发策略：同一批次的检验/批准/召回/拆分/称量/更正在事务内对 batch 行 SELECT ... FOR UPDATE
 * 串行化，按事务提交顺序生效；commandKey/requestId 幂等通过 command_log 主键 + 结构化参数指纹实现，
 * 同键同参重放返回首次响应快照，同键改参返回 409；仅成功命令写入 command_log，失败不占键。
 */
@Service
public class BatchService {

    private static final String CMD_CREATE = "CREATE_BATCH";
    private static final String CMD_TEST = "SUBMIT_TEST";
    private static final String CMD_APPROVE = "APPROVE";
    private static final String CMD_RECALL = "RECALL";
    private static final String CMD_SPLIT = "SPLIT";
    private static final String CMD_CREATE_RECIPE = "CREATE_RECIPE";
    private static final String CMD_WEIGH = "WEIGH";
    private static final String CMD_CORRECT = "CORRECT_WEIGHING";

    /**
     * 配方占比合计基准值 100。
     */
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /**
     * 放行占比偏差上限：0.1 个百分点。
     */
    private static final BigDecimal MAX_DEVIATION = new BigDecimal("0.1");

    /**
     * 质量上限：DECIMAL(19,6) 可表示的最大值。
     */
    private static final BigDecimal MAX_MASS = new BigDecimal("9999999999999.999999");

    /**
     * 拆分继承称量行的操作人标识。
     */
    private static final String SPLIT_ACTOR = "system:split";

    private static final int IDEMPOTENCY_MAX_ATTEMPTS = 3;

    private final BatchRepository repo;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper;

    public BatchService(BatchRepository repo,
                        PlatformTransactionManager transactionManager,
                        ObjectMapper objectMapper) {
        this.repo = repo;
        this.tx = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    /**
     * 创建批次：初始状态 QUARANTINED；batchKey 全局唯一，重复返回 409。
     */
    public StoredResponse createBatch(CreateBatchRequest req) {
        List<String> items = req.requiredTests().stream().map(String::trim).toList();
        if (new HashSet<>(items).size() != items.size()) {
            throw ApiException.badRequest("requiredTests 存在重复检验项");
        }
        boolean bindRecipe = req.recipeKey() != null || req.targetMassGrams() != null;
        if (bindRecipe && (req.recipeKey() == null || req.recipeKey().isBlank()
                || req.targetMassGrams() == null)) {
            throw ApiException.badRequest("绑定配方时 recipeKey 与 targetMassGrams 必须同时提供");
        }
        if (bindRecipe) {
            requireMass(req.targetMassGrams(), "targetMassGrams");
        }
        List<String> parts = new ArrayList<>(List.of("create", req.batchKey(), req.productCode(),
                req.batchNo(), req.producedAt().toString(),
                req.recipeKey() == null ? "" : req.recipeKey().trim(),
                req.targetMassGrams() == null ? "" : req.targetMassGrams().toPlainString()));
        parts.addAll(items);
        String fingerprint = fingerprint(parts.toArray(new String[0]));
        return executeIdempotent(CMD_CREATE, req.commandKey(), fingerprint, () -> {
            repo.findBatch(req.batchKey()).ifPresent(b -> {
                throw ApiException.conflict("batchKey 已存在: " + req.batchKey());
            });
            String recipeKey = null;
            if (bindRecipe) {
                recipeKey = req.recipeKey().trim();
                if (repo.findRecipe(recipeKey).isEmpty()) {
                    throw ApiException.unprocessable("配方版本不存在: " + recipeKey);
                }
            }
            String now = now();
            repo.insertBatch(new BatchRepository.BatchRow(0L, req.batchKey(), req.productCode(),
                    req.batchNo(), req.producedAt().toString(), BatchStatus.QUARANTINED.name(), now,
                    recipeKey, req.targetMassGrams(), 0));
            for (int i = 0; i < items.size(); i++) {
                repo.insertRequiredTest(req.batchKey(), items.get(i), i + 1);
            }
            BatchResponse body = new BatchResponse(req.batchKey(), req.productCode(), req.batchNo(),
                    req.producedAt(), BatchStatus.QUARANTINED, items, Instant.parse(now),
                    recipeKey, req.targetMassGrams());
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 提交检验结果：testKey 批次内幂等；任一 FAIL 立即 REJECTED；全部必做项 PASS 后 PENDING_RELEASE。
     */
    public StoredResponse submitTest(String batchKey, SubmitTestRequest req) {
        String fingerprint = fingerprint("test", batchKey, req.testKey(), req.testItem(),
                req.result().name(), req.inspector());
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
     */
    public StoredResponse approve(String batchKey, String actorId, String roleHeader, ApproveRequest req) {
        if (actorId == null || actorId.isBlank()) {
            throw ApiException.badRequest("X-Actor-Id 不能为空");
        }
        ApprovalRole role = parseRole(roleHeader);
        String actor = actorId.trim();
        String fingerprint = fingerprint("approve", batchKey, actor, role.name());
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
            String now = now();
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
            if (newStatus == BatchStatus.RELEASED && batch.recipeKey() != null) {
                assertRecipeMetForRelease(batch);
            }
            repo.insertApproval(new BatchRepository.ApprovalRow(0L, batchKey, req.commandKey(),
                    actor, role.name(), seq, now));
            repo.updateStatus(batchKey, newStatus.name());
            ApprovalResponse body = new ApprovalResponse(batchKey, actor, role, seq, newStatus,
                    Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 召回：RELEASED 或 SPLIT 批次可召回，召回后进入 RECALLED 并不再出现在可用批次查询中。
     * 召回提交后其全部后代立即不可用（排除出可用查询、禁止新增检验/批准/拆分），
     * 但后代自身状态与既有检验、批准记录不改写，也不为后代补写召回记录。
     */
    public StoredResponse recall(String batchKey, String actorId, RecallRequest req) {
        if (actorId == null || actorId.isBlank()) {
            throw ApiException.badRequest("X-Actor-Id 不能为空");
        }
        String actor = actorId.trim();
        String fingerprint = fingerprint("recall", batchKey, actor, req.reason());
        return executeIdempotent(CMD_RECALL, req.commandKey(), fingerprint, () -> {
            BatchRepository.BatchRow batch = repo.findBatchForUpdate(batchKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
            BatchStatus status = BatchStatus.valueOf(batch.status());
            if (status != BatchStatus.RELEASED && status != BatchStatus.SPLIT) {
                throw ApiException.conflict("批次状态 " + status + " 不允许召回，仅 RELEASED 或 SPLIT 可召回");
            }
            // 逐行锁定全部后代（按业务键排序保证锁顺序确定）：与后代上的检验/批准/拆分
            // 互斥，按事务提交顺序裁决——召回先提交则后代新操作看到召回并返回 422。
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
     * 当前可用批次：排除已召回（RECALLED）、已拆分（SPLIT）批次，
     * 以及任一祖先被召回的后代批次；后代自身状态不改写。
     */
    public List<BatchResponse> listAvailable() {
        Map<String, String> parentOf = childToParent();
        Set<String> recalled = new HashSet<>(repo.findRecalledKeys());
        return repo.findAvailableBatches().stream()
                .filter(b -> !BatchStatus.SPLIT.name().equals(b.status()))
                .filter(b -> recalledAncestor(b.batchKey(), parentOf, recalled).isEmpty())
                .map(this::toBatchResponse)
                .toList();
    }

    /**
     * 批次完整历史：批次概要 + 全部检验 + 全部批准 + 召回记录，历史不因召回而删除或改写。
     */
    public BatchHistoryResponse history(String batchKey) {
        BatchRepository.BatchRow batch = repo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        List<String> required = repo.findRequiredTests(batchKey);
        List<BatchRepository.TestRow> testRows = repo.findTests(batchKey);
        // 按提交顺序推导每条检验结果落定后的批次状态快照：FAIL→REJECTED；
        // 必做项全部 PASS 时该条→PENDING_RELEASE；其余→QUARANTINED。
        List<TestResultResponse> tests = new java.util.ArrayList<>(testRows.size());
        for (BatchRepository.TestRow t : testRows) {
            tests.add(toTestResponse(t, snapshotAfter(t, testRows, required).name()));
        }
        List<ApprovalResponse> approvals = repo.findApprovals(batchKey).stream()
                .map(a -> {
                    // 历史快照：第一笔批准落定 RELEASE_REVIEW，第二笔落定 RELEASED；不随后续召回改写
                    BatchStatus snapshot = a.seq() == 1
                            ? BatchStatus.RELEASE_REVIEW
                            : BatchStatus.RELEASED;
                    return new ApprovalResponse(a.batchKey(), a.actorId(), ApprovalRole.valueOf(a.role()),
                            a.seq(), snapshot, Instant.parse(a.createdAt()));
                })
                .toList();
        RecallResponse recall = repo.findRecall(batchKey)
                .map(r -> new RecallResponse(r.batchKey(), r.actorId(), r.reason(),
                        BatchStatus.RECALLED, Instant.parse(r.createdAt())))
                .orElse(null);
        return new BatchHistoryResponse(toBatchResponse(batch), tests, approvals, recall);
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
                row.recipeKey(), row.targetMass());
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
        // 结构化拼接：每段带长度前缀，避免分隔符碰撞；null 归一为空串
        StringBuilder canonical = new StringBuilder();
        for (String part : parts) {
            String value = part == null ? "" : part;
            canonical.append(value.length()).append(':').append(value);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
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

    /**
     * 拆分：仅当前可用的 RELEASED 批次可一次拆成 2～5 个全新子批。
     * 子批继承产品编码、生产 UTC 时间与必做检验项，初始 QUARANTINED，
     * 不继承检验或批准记录；父批置为 SPLIT 不再可用。
     * 创建全部子批、血缘关系与父批状态在同一事务提交；任一子批键已存在或重复，整次 409 且父批不变。
     */
    public StoredResponse split(String parentKey, SplitRequest req) {
        List<SplitRequest.ChildSpec> children = req.children();
        List<String> childKeys = children.stream().map(SplitRequest.ChildSpec::batchKey).toList();
        if (new HashSet<>(childKeys).size() != childKeys.size()) {
            throw ApiException.conflict("子批 batchKey 在请求内重复");
        }
        List<String> parts = new ArrayList<>();
        parts.add("split");
        parts.add(parentKey);
        for (SplitRequest.ChildSpec child : children) {
            parts.add(child.batchKey());
            parts.add(child.batchNo());
            parts.add(child.targetMassGrams() == null ? "" : child.targetMassGrams().toPlainString());
        }
        String fingerprint = fingerprint(parts.toArray(new String[0]));
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
            // 配方继承：父批绑定配方时，子批按目标质量比例精确继承各成分有效称量合计，
            // 无法在6位小数内守恒则整次 422，父批不变
            Map<String, Map<String, BigDecimal>> inheritedByChild = null;
            if (parent.recipeKey() != null) {
                inheritedByChild = planSplitInheritance(parent, children, effectiveTotals(parentKey));
            }
            String now = now();
            List<String> required = repo.findRequiredTests(parentKey);
            List<SplitResponse.SplitChild> childBodies = new ArrayList<>(children.size());
            for (int i = 0; i < children.size(); i++) {
                SplitRequest.ChildSpec spec = children.get(i);
                BigDecimal childTarget = parent.recipeKey() == null ? null : spec.targetMassGrams();
                repo.insertBatch(new BatchRepository.BatchRow(0L, spec.batchKey(), parent.productCode(),
                        spec.batchNo(), parent.producedAt(), BatchStatus.QUARANTINED.name(), now,
                        parent.recipeKey(), childTarget, 0));
                for (int j = 0; j < required.size(); j++) {
                    repo.insertRequiredTest(spec.batchKey(), required.get(j), j + 1);
                }
                repo.insertLineage(new BatchRepository.LineageRow(0L, parentKey, spec.batchKey(),
                        i + 1, now));
                if (inheritedByChild != null) {
                    int childVersion = 0;
                    for (Map.Entry<String, BigDecimal> inherited
                            : inheritedByChild.get(spec.batchKey()).entrySet()) {
                        repo.insertWeighing(new BatchRepository.WeighingRow(0L, spec.batchKey(),
                                inherited.getKey(), inherited.getValue(), null, null,
                                SPLIT_ACTOR, now));
                        childVersion++;
                    }
                    repo.updateWeighingVersion(spec.batchKey(), childVersion);
                }
                childBodies.add(new SplitResponse.SplitChild(spec.batchKey(), spec.batchNo(),
                        parent.productCode(), Instant.parse(parent.producedAt()),
                        BatchStatus.QUARANTINED, required, Instant.parse(now), childTarget));
            }
            repo.updateStatus(parentKey, BatchStatus.SPLIT.name());
            SplitResponse body = new SplitResponse(parentKey, BatchStatus.SPLIT, childBodies,
                    Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 祖先查询：从直接父批逐级向上到根，每项含批次自身状态及导致其不可用的召回祖先。
     */
    public List<LineageEntryResponse> listAncestors(String batchKey) {
        repo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        Map<String, String> parentOf = childToParent();
        Set<String> recalled = new HashSet<>(repo.findRecalledKeys());
        List<LineageEntryResponse> result = new ArrayList<>();
        String current = batchKey;
        while (parentOf.containsKey(current)) {
            current = parentOf.get(current);
            result.add(toLineageEntry(current, parentOf, recalled));
        }
        return result;
    }

    /**
     * 后代查询：按血缘关系创建顺序广度优先展开，每项含批次自身状态及导致其不可用的召回祖先。
     */
    public List<LineageEntryResponse> listDescendants(String batchKey) {
        repo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        Map<String, String> parentOf = childToParent();
        Set<String> recalled = new HashSet<>(repo.findRecalledKeys());
        List<LineageEntryResponse> result = new ArrayList<>();
        for (String key : descendantKeys(batchKey)) {
            result.add(toLineageEntry(key, parentOf, recalled));
        }
        return result;
    }

    /**
     * 若任一级祖先已被召回则抛 422：后代批次禁止新增检验、批准和拆分。
     */
    private void assertNoRecalledAncestor(String batchKey) {
        Optional<String> recalled = recalledAncestor(batchKey, childToParent(),
                new HashSet<>(repo.findRecalledKeys()));
        if (recalled.isPresent()) {
            throw ApiException.unprocessable(
                    "祖先批次 " + recalled.get() + " 已召回，禁止新增检验、批准和拆分");
        }
    }

    /**
     * 沿父链向上查找最近的被直接召回（RECALLED）祖先；批次自身召回不算祖先召回。
     */
    private Optional<String> recalledAncestor(String batchKey, Map<String, String> parentOf,
                                              Set<String> recalled) {
        String current = batchKey;
        while (parentOf.containsKey(current)) {
            current = parentOf.get(current);
            if (recalled.contains(current)) {
                return Optional.of(current);
            }
        }
        return Optional.empty();
    }

    /**
     * 全部血缘边的 子批→父批 映射；关系不可改写，只增不改。
     */
    private Map<String, String> childToParent() {
        Map<String, String> parentOf = new HashMap<>();
        for (BatchRepository.LineageRow row : repo.findAllLineage()) {
            parentOf.put(row.childKey(), row.parentKey());
        }
        return parentOf;
    }

    /**
     * 某批次的全部后代业务键，按血缘关系创建顺序广度优先展开（不含自身）。
     */
    private List<String> descendantKeys(String rootKey) {
        Map<String, List<String>> childrenOf = new HashMap<>();
        for (BatchRepository.LineageRow row : repo.findAllLineage()) {
            childrenOf.computeIfAbsent(row.parentKey(), k -> new ArrayList<>()).add(row.childKey());
        }
        List<String> result = new ArrayList<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(rootKey);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (String child : childrenOf.getOrDefault(current, List.of())) {
                result.add(child);
                queue.add(child);
            }
        }
        return result;
    }

    private LineageEntryResponse toLineageEntry(String batchKey, Map<String, String> parentOf,
                                                Set<String> recalled) {
        BatchRepository.BatchRow row = repo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        return new LineageEntryResponse(row.batchKey(), row.batchNo(),
                BatchStatus.valueOf(row.status()),
                recalledAncestor(batchKey, parentOf, recalled).orElse(null));
    }

    /**
     * 创建不可变配方版本：2至6种成分，质量百分比每项正值、最多4位小数，合计精确等于100。
     * requestId 幂等：同键同参重放首次响应，同键异参 409，失败不占键。
     */
    public StoredResponse createRecipe(String actorId, CreateRecipeRequest req) {
        String actor = requireActor(actorId);
        List<CreateRecipeRequest.IngredientSpec> specs = req.ingredients();
        List<String> names = specs.stream().map(s -> s.name().trim()).toList();
        if (new HashSet<>(names).size() != names.size()) {
            throw ApiException.badRequest("ingredients 存在重复成分");
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < specs.size(); i++) {
            BigDecimal pct = specs.get(i).percentage();
            if (pct.signum() <= 0) {
                throw ApiException.badRequest("percentage 必须为正值: " + names.get(i));
            }
            if (pct.stripTrailingZeros().scale() > 4) {
                throw ApiException.badRequest("percentage 最多4位小数: " + names.get(i));
            }
            sum = sum.add(pct);
        }
        if (sum.compareTo(HUNDRED) != 0) {
            throw ApiException.badRequest("各成分 percentage 合计必须精确等于100，当前为 "
                    + sum.stripTrailingZeros().toPlainString());
        }
        List<String> parts = new ArrayList<>(List.of("recipe", actor, req.recipeKey().trim()));
        for (int i = 0; i < specs.size(); i++) {
            parts.add(names.get(i));
            parts.add(specs.get(i).percentage().toPlainString());
        }
        String fingerprint = fingerprint(parts.toArray(new String[0]));
        return executeIdempotent(CMD_CREATE_RECIPE, req.requestId(), fingerprint, () -> {
            String recipeKey = req.recipeKey().trim();
            repo.findRecipe(recipeKey).ifPresent(r -> {
                throw ApiException.conflict("recipeKey 已存在: " + recipeKey);
            });
            String now = now();
            repo.insertRecipe(recipeKey, now);
            List<RecipeResponse.Ingredient> ingredients = new ArrayList<>(specs.size());
            for (int i = 0; i < specs.size(); i++) {
                repo.insertRecipeIngredient(recipeKey, names.get(i), specs.get(i).percentage(), i + 1);
                ingredients.add(new RecipeResponse.Ingredient(names.get(i),
                        specs.get(i).percentage()));
            }
            RecipeResponse body = new RecipeResponse(recipeKey, ingredients, Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 追加称量：同成分多次称量累加；负量、未知成分或超精度输入 400；
     * expectedVersion 与称量链版本不一致 409；批次状态不允许时 409。
     */
    public StoredResponse weigh(String batchKey, String actorId, WeighingRequest req) {
        String actor = requireActor(actorId);
        requireMass(req.amountGrams(), "amountGrams");
        String fingerprint = fingerprint("weigh", actor, batchKey, req.ingredient().trim(),
                req.amountGrams().toPlainString(), req.expectedVersion().toString());
        return executeIdempotent(CMD_WEIGH, req.requestId(), fingerprint, () -> {
            BatchRepository.BatchRow batch = repo.findBatchForUpdate(batchKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
            // 行锁后重查命令快照：并发同键请求在锁等待期间可能已由对方提交
            var logged = loggedResponse(CMD_WEIGH, req.requestId(), fingerprint);
            if (logged.isPresent()) {
                return logged.get();
            }
            assertWeighingAllowed(batch);
            String ingredient = req.ingredient().trim();
            boolean known = repo.findRecipeIngredients(batch.recipeKey()).stream()
                    .anyMatch(i -> i.ingredient().equals(ingredient));
            if (!known) {
                throw ApiException.badRequest("成分不属于批次绑定配方: " + ingredient);
            }
            if (req.expectedVersion() != batch.weighingVersion()) {
                throw ApiException.conflict("称量链版本不匹配: 期望 " + req.expectedVersion()
                        + "，当前 " + batch.weighingVersion());
            }
            String now = now();
            long weighingId = repo.insertWeighing(new BatchRepository.WeighingRow(0L, batchKey,
                    ingredient, req.amountGrams(), null, null, actor, now));
            int newVersion = batch.weighingVersion() + 1;
            repo.updateWeighingVersion(batchKey, newVersion);
            WeighingResponse body = new WeighingResponse(weighingId, batchKey, ingredient,
                    req.amountGrams(), null, newVersion, Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 称量更正：指定最近有效称量ID，追加替代行重算而不是重复累加；被更正行失效。
     * 已失效或非本批次的称量 409；批次已放行/已拆分等状态 409（与拆分、放行互斥）。
     */
    public StoredResponse correctWeighing(String batchKey, String actorId,
                                          CorrectWeighingRequest req) {
        String actor = requireActor(actorId);
        requireMass(req.amountGrams(), "amountGrams");
        String fingerprint = fingerprint("correct", actor, batchKey, req.weighingId().toString(),
                req.amountGrams().toPlainString(), req.expectedVersion().toString());
        return executeIdempotent(CMD_CORRECT, req.requestId(), fingerprint, () -> {
            BatchRepository.BatchRow batch = repo.findBatchForUpdate(batchKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
            var logged = loggedResponse(CMD_CORRECT, req.requestId(), fingerprint);
            if (logged.isPresent()) {
                return logged.get();
            }
            assertWeighingAllowed(batch);
            BatchRepository.WeighingRow target = repo.findWeighing(req.weighingId())
                    .orElseThrow(() -> ApiException.notFound("称量记录不存在: " + req.weighingId()));
            if (!target.batchKey().equals(batchKey)) {
                throw ApiException.conflict("称量记录不属于该批次: " + req.weighingId());
            }
            if (target.supersededBy() != null) {
                throw ApiException.conflict("只能更正最近有效称量，该记录已被替代: "
                        + req.weighingId());
            }
            if (req.expectedVersion() != batch.weighingVersion()) {
                throw ApiException.conflict("称量链版本不匹配: 期望 " + req.expectedVersion()
                        + "，当前 " + batch.weighingVersion());
            }
            String now = now();
            long newId = repo.insertWeighing(new BatchRepository.WeighingRow(0L, batchKey,
                    target.ingredient(), req.amountGrams(), target.id(), null, actor, now));
            repo.markWeighingSuperseded(target.id(), newId);
            int newVersion = batch.weighingVersion() + 1;
            repo.updateWeighingVersion(batchKey, newVersion);
            WeighingResponse body = new WeighingResponse(newId, batchKey, target.ingredient(),
                    req.amountGrams(), target.id(), newVersion, Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 批次当前有效称量链：有效称量行、按成分汇总（单位克）与称量链版本。
     */
    public WeighingChainResponse weighingChain(String batchKey) {
        BatchRepository.BatchRow batch = repo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        List<WeighingChainResponse.Entry> entries = repo.findEffectiveWeighings(batchKey).stream()
                .map(w -> new WeighingChainResponse.Entry(w.id(), w.ingredient(), w.amount(),
                        w.replacesId(), Instant.parse(w.createdAt())))
                .toList();
        Map<String, BigDecimal> totals = effectiveTotals(batchKey);
        List<WeighingChainResponse.Total> totalList = new ArrayList<>();
        if (batch.recipeKey() != null) {
            for (BatchRepository.IngredientRow row : repo.findRecipeIngredients(batch.recipeKey())) {
                totalList.add(new WeighingChainResponse.Total(row.ingredient(),
                        totals.getOrDefault(row.ingredient(), BigDecimal.ZERO), row.percentage()));
            }
        }
        return new WeighingChainResponse(batchKey, batch.recipeKey(), batch.targetMass(),
                batch.weighingVersion(), entries, totalList);
    }

    /**
     * 拆分继承规划：各成分有效称量合计按子批目标质量比例精确折算，
     * 全部子批目标质量之和须等于父批目标质量，任一成分无法在6位小数内精确继承则 422。
     */
    private Map<String, Map<String, BigDecimal>> planSplitInheritance(
            BatchRepository.BatchRow parent, List<SplitRequest.ChildSpec> children,
            Map<String, BigDecimal> parentTotals) {
        BigDecimal parentTarget = parent.targetMass();
        BigDecimal sum = BigDecimal.ZERO;
        for (SplitRequest.ChildSpec child : children) {
            if (child.targetMassGrams() == null) {
                throw ApiException.badRequest("父批绑定配方时每个子批必须提供 targetMassGrams");
            }
            requireMass(child.targetMassGrams(), "targetMassGrams");
            sum = sum.add(child.targetMassGrams());
        }
        if (sum.compareTo(parentTarget) != 0) {
            throw ApiException.unprocessable("子批目标质量之和 "
                    + sum.stripTrailingZeros().toPlainString() + " 不等于父批目标质量 "
                    + parentTarget.stripTrailingZeros().toPlainString() + "，无法守恒拆分");
        }
        Map<String, Map<String, BigDecimal>> result = new LinkedHashMap<>();
        for (SplitRequest.ChildSpec child : children) {
            Map<String, BigDecimal> inherited = new LinkedHashMap<>();
            for (Map.Entry<String, BigDecimal> entry : parentTotals.entrySet()) {
                BigDecimal share;
                try {
                    share = entry.getValue().multiply(child.targetMassGrams())
                            .divide(parentTarget, 6, RoundingMode.UNNECESSARY);
                } catch (ArithmeticException ex) {
                    throw ApiException.unprocessable("成分 " + entry.getKey()
                            + " 按子批质量比例无法在6位小数内精确继承，拆分不守恒");
                }
                inherited.put(entry.getKey(), share);
            }
            result.put(child.batchKey(), inherited);
        }
        return result;
    }

    /**
     * 放行配方门禁：每种成分齐全、有效称量总量精确等于目标总质量、
     * 每项实际占比与声明占比偏差不超过0.1个百分点（判定不提前舍入）。
     * 不达标抛 422 并携带全部成分差额，不允许只批准合格成分。
     */
    private void assertRecipeMetForRelease(BatchRepository.BatchRow batch) {
        List<BatchRepository.IngredientRow> ingredients =
                repo.findRecipeIngredients(batch.recipeKey());
        Map<String, BigDecimal> totals = effectiveTotals(batch.batchKey());
        BigDecimal target = batch.targetMass();
        BigDecimal weighed = totals.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean met = weighed.compareTo(target) == 0;
        List<IngredientDifference> diffs = new ArrayList<>(ingredients.size());
        for (BatchRepository.IngredientRow ingredient : ingredients) {
            BigDecimal actual = totals.get(ingredient.ingredient());
            BigDecimal actualMass = actual == null ? BigDecimal.ZERO : actual;
            BigDecimal expected = ingredient.percentage().multiply(target).movePointLeft(2);
            BigDecimal actualPct = weighed.signum() == 0 ? BigDecimal.ZERO
                    : actualMass.movePointRight(2).divide(weighed, MathContext.DECIMAL128);
            BigDecimal deviation = actualPct.subtract(ingredient.percentage()).abs();
            if (actual == null || deviation.compareTo(MAX_DEVIATION) > 0) {
                met = false;
            }
            diffs.add(new IngredientDifference(ingredient.ingredient(), ingredient.percentage(),
                    expected, actualMass, actualMass.subtract(expected),
                    actualPct.setScale(6, RoundingMode.HALF_UP)));
        }
        if (!met) {
            throw new RecipeDeviationException("批次 " + batch.batchKey() + " 称量不满足配方 "
                    + batch.recipeKey() + "，禁止放行", diffs);
        }
    }

    /**
     * 批次当前有效称量按成分汇总（单位克）。
     */
    private Map<String, BigDecimal> effectiveTotals(String batchKey) {
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        for (BatchRepository.WeighingRow w : repo.findEffectiveWeighings(batchKey)) {
            totals.merge(w.ingredient(), w.amount(), BigDecimal::add);
        }
        return totals;
    }

    /**
     * 称量/更正前置条件：批次已绑定配方、无召回祖先、状态允许变更称量链
     * （放行后冻结，已拆分父批不能再更正）。
     */
    private void assertWeighingAllowed(BatchRepository.BatchRow batch) {
        if (batch.recipeKey() == null) {
            throw ApiException.unprocessable("批次未绑定配方，不能称量: " + batch.batchKey());
        }
        assertNoRecalledAncestor(batch.batchKey());
        BatchStatus status = BatchStatus.valueOf(batch.status());
        if (status != BatchStatus.QUARANTINED && status != BatchStatus.PENDING_RELEASE
                && status != BatchStatus.RELEASE_REVIEW) {
            throw ApiException.conflict("批次状态 " + status + " 不允许称量或更正");
        }
    }

    /**
     * 质量输入校验：正值、最多6位小数、不超出 DECIMAL(19,6) 可表示范围，违反返回 400。
     */
    private void requireMass(BigDecimal value, String field) {
        if (value.signum() <= 0) {
            throw ApiException.badRequest(field + " 必须为正值");
        }
        if (value.stripTrailingZeros().scale() > 6) {
            throw ApiException.badRequest(field + " 最多6位小数");
        }
        if (value.compareTo(MAX_MASS) > 0) {
            throw ApiException.badRequest(field + " 超出可表示范围");
        }
    }

    /**
     * 操作人校验：X-Actor-Id 必填，缺失返回 400。
     */
    private String requireActor(String actorId) {
        if (actorId == null || actorId.isBlank()) {
            throw ApiException.badRequest("X-Actor-Id 不能为空");
        }
        return actorId.trim();
    }
}
