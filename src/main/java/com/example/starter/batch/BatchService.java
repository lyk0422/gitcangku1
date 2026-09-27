package com.example.starter.batch;

import com.example.starter.batch.dto.ApprovalResponse;
import com.example.starter.batch.dto.ApproveRequest;
import com.example.starter.batch.dto.BatchHistoryResponse;
import com.example.starter.batch.dto.BatchResponse;
import com.example.starter.batch.dto.CreateBatchRequest;
import com.example.starter.batch.dto.LineageEntryResponse;
import com.example.starter.batch.dto.MergeRequest;
import com.example.starter.batch.dto.MergeResponse;
import com.example.starter.batch.dto.PotencyEvaluationResponse;
import com.example.starter.batch.dto.PotencyModelRequest;
import com.example.starter.batch.dto.PotencyModelResponse;
import com.example.starter.batch.dto.PotencyStatusResponse;
import com.example.starter.batch.dto.RecallRequest;
import com.example.starter.batch.dto.RecallResponse;
import com.example.starter.batch.dto.SplitRequest;
import com.example.starter.batch.dto.SplitResponse;
import com.example.starter.batch.dto.SubmitTestRequest;
import com.example.starter.batch.dto.TestResultResponse;
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
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 批次隔离与放行核心服务。
 *
 * <p>并发策略：同一批次的检验/批准/召回在事务内对 batch 行 SELECT ... FOR UPDATE 串行化，
 * 按事务提交顺序生效；commandKey 幂等通过 command_log 主键 + 参数指纹实现，
 * 同键同参重放返回首次响应快照，同键改参返回 409；仅成功命令写入 command_log。
 */
@Service
public class BatchService {

    private static final String CMD_CREATE = "CREATE_BATCH";
    private static final String CMD_TEST = "SUBMIT_TEST";
    private static final String CMD_APPROVE = "APPROVE";
    private static final String CMD_RECALL = "RECALL";
    private static final String CMD_SPLIT = "SPLIT";
    private static final String CMD_POTENCY_MODEL = "POTENCY_MODEL";
    private static final String CMD_MERGE = "MERGE";

    /**
     * 指纹字段编码分隔符：各字段以 "字节长度:内容" 长度前缀编码后拼接，
     * 不依赖任何未转义分隔符，集合参数逐项编码，避免拼接碰撞。
     */
    private static final String LEN_SEP = ":";

    private static final int IDEMPOTENCY_MAX_ATTEMPTS = 10;

    /**
     * 并发同键冲突重试退避基数（毫秒）：等待对方事务提交后读取其命令快照。
     */
    private static final long IDEMPOTENCY_RETRY_BACKOFF_MS = 20L;

    private final BatchRepository repo;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public BatchService(BatchRepository repo,
                        PlatformTransactionManager transactionManager,
                        ObjectMapper objectMapper,
                        Clock businessClock) {
        this.repo = repo;
        this.tx = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
        this.clock = businessClock;
    }

    /**
     * 创建批次：初始状态 QUARANTINED；batchKey 全局唯一，重复返回 409。
     */
    public StoredResponse createBatch(CreateBatchRequest req) {
        List<String> items = req.requiredTests().stream().map(String::trim).toList();
        if (new HashSet<>(items).size() != items.size()) {
            throw ApiException.badRequest("requiredTests 存在重复检验项");
        }
        List<String> parts = new ArrayList<>();
        parts.add("create");
        parts.add(req.batchKey());
        parts.add(req.productCode());
        parts.add(req.batchNo());
        parts.add(req.producedAt().toString());
        parts.addAll(items);
        String fingerprint = fingerprint(parts.toArray(new String[0]));
        return executeIdempotent(CMD_CREATE, req.commandKey(), fingerprint, () -> {
            if (repo.findBatch(req.batchKey()).isPresent()) {
                // 批次已存在时重查命令快照：并发同键请求可能在本事务读快照后、
                // 读批次前由对方提交，此时须重放首次结果而不是误判键冲突
                var logged = loggedResponse(CMD_CREATE, req.commandKey(), fingerprint);
                if (logged.isPresent()) {
                    return logged.get();
                }
                throw ApiException.conflict("batchKey 已存在: " + req.batchKey());
            }
            String now = now();
            repo.insertBatch(new BatchRepository.BatchRow(0L, req.batchKey(), req.productCode(),
                    req.batchNo(), req.producedAt().toString(), BatchStatus.QUARANTINED.name(), now));
            for (int i = 0; i < items.size(); i++) {
                repo.insertRequiredTest(req.batchKey(), items.get(i), i + 1);
            }
            BatchResponse body = new BatchResponse(req.batchKey(), req.productCode(), req.batchNo(),
                    req.producedAt(), BatchStatus.QUARANTINED, items, Instant.parse(now));
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
                    || status == BatchStatus.RECALLED || status == BatchStatus.SPLIT
                    || status == BatchStatus.MERGED) {
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
            if (newStatus == BatchStatus.RELEASED) {
                // 放行时点核验：以事务内读取的一致效价模型 + 可注入时钟计算当前效价，
                // 低于阈值整次 422 回滚；通过则固化计算时刻、模型版本、输入效价与舍入结果
                assertReleasePotency(batchKey);
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
            if (status != BatchStatus.RELEASED && status != BatchStatus.SPLIT
                    && status != BatchStatus.MERGED) {
                throw ApiException.conflict("批次状态 " + status
                        + " 不允许召回，仅 RELEASED、SPLIT 或 MERGED 可召回");
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
        Map<String, List<String>> parentsOf = childToParents();
        Set<String> recalled = new HashSet<>(repo.findRecalledKeys());
        return repo.findAvailableBatches().stream()
                .filter(b -> !BatchStatus.SPLIT.name().equals(b.status()))
                .filter(b -> !BatchStatus.MERGED.name().equals(b.status()))
                .filter(b -> recalledAncestor(b.batchKey(), parentsOf, recalled).isEmpty())
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
        PotencyModelResponse potencyModel = repo.findLatestPotencyModel(batchKey)
                .map(this::toPotencyModelResponse)
                .orElse(null);
        List<PotencyEvaluationResponse> evaluations = repo.findPotencyEvaluations(batchKey).stream()
                .map(this::toPotencyEvaluationResponse)
                .toList();
        return new BatchHistoryResponse(toBatchResponse(batch), tests, approvals, recall,
                potencyModel, evaluations);
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
                // 并发同事务键冲突：回滚后短暂退避重试，读取对方已提交的命令快照或业务结果
                try {
                    Thread.sleep(IDEMPOTENCY_RETRY_BACKOFF_MS * (attempt + 1));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw ApiException.conflict("命令执行被中断，请重试: " + commandKey);
                }
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
                repo.findRequiredTests(row.batchKey()), Instant.parse(row.createdAt()));
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
        return Instant.now(clock).toString();
    }

    /**
     * 幂等指纹：每个字段以 "UTF-8 字节长度:原始内容" 长度前缀编码后拼接再做 SHA-256，
     * 集合逐项编码，不使用任何未转义分隔符，避免不同参数组合拼出相同串。
     */
    private String fingerprint(String... parts) {
        StringBuilder canonical = new StringBuilder();
        for (String part : parts) {
            String value = part == null ? "" : part;
            canonical.append(value.getBytes(StandardCharsets.UTF_8).length)
                    .append(LEN_SEP).append(value);
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
     *
     * <p>父批已登记效价模型时：必须提供 parentWeight 与各子批 weight（正数）且权重守恒
     * （子批权重之和 = parentWeight），否则 422；每个子批登记效价模型 v1，
     * 初始效价为父批在拆分时刻的当前效价，阈值与衰减率继承父批，并固化父批评估记录。
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
        parts.add(req.parentWeight() == null ? "" : req.parentWeight().toPlainString());
        for (SplitRequest.ChildSpec child : children) {
            parts.add(child.batchKey());
            parts.add(child.batchNo());
            parts.add(child.weight() == null ? "" : child.weight().toPlainString());
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
            String now = now();
            // 父批登记效价模型时校验权重守恒并计算拆分时刻当前效价（全部校验先于任何写入）
            Optional<BatchRepository.PotencyModelRow> model = repo.findLatestPotencyModel(parentKey);
            BigDecimal parentCurrentPotency = null;
            if (model.isPresent()) {
                validateSplitWeights(req);
                parentCurrentPotency = evaluatePotency(model.get(), Instant.parse(now))
                        .currentPotency();
            }
            List<String> required = repo.findRequiredTests(parentKey);
            List<SplitResponse.SplitChild> childBodies = new ArrayList<>(children.size());
            for (int i = 0; i < children.size(); i++) {
                SplitRequest.ChildSpec spec = children.get(i);
                repo.insertBatch(new BatchRepository.BatchRow(0L, spec.batchKey(), parent.productCode(),
                        spec.batchNo(), parent.producedAt(), BatchStatus.QUARANTINED.name(), now));
                for (int j = 0; j < required.size(); j++) {
                    repo.insertRequiredTest(spec.batchKey(), required.get(j), j + 1);
                }
                repo.insertLineage(new BatchRepository.LineageRow(0L, parentKey, spec.batchKey(),
                        i + 1, now));
                if (model.isPresent()) {
                    BatchRepository.PotencyModelRow parentModel = model.get();
                    repo.insertPotencyModel(new BatchRepository.PotencyModelRow(0L, spec.batchKey(),
                            1, parentCurrentPotency, parentModel.minReleasePotency(),
                            parentModel.decayRatePerDay(), now, now));
                    repo.insertPotencyEvaluation(new BatchRepository.PotencyEvalRow(0L, parentKey,
                            "SPLIT", spec.batchKey(), now, parentModel.version(),
                            parentModel.initialPotency(), parentModel.decayRatePerDay(),
                            parentModel.minReleasePotency(),
                            PotencyCalculator.elapsedUtcDays(Instant.parse(parentModel.registeredAt()),
                                    Instant.parse(now)),
                            parentCurrentPotency, now));
                }
                childBodies.add(new SplitResponse.SplitChild(spec.batchKey(), spec.batchNo(),
                        parent.productCode(), Instant.parse(parent.producedAt()),
                        BatchStatus.QUARANTINED, required, Instant.parse(now),
                        parentCurrentPotency));
            }
            repo.updateStatus(parentKey, BatchStatus.SPLIT.name());
            SplitResponse body = new SplitResponse(parentKey, BatchStatus.SPLIT, childBodies,
                    Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 拆分权重校验：父批已登记效价模型时 parentWeight 与各子批 weight 必填且为正，
     * 子批权重之和必须等于 parentWeight（权重守恒），否则 422 并携带实际值/要求值/差额。
     */
    private void validateSplitWeights(SplitRequest req) {
        if (req.parentWeight() == null) {
            throw ApiException.unprocessable("父批已登记效价模型，拆分必须提供 parentWeight");
        }
        if (req.parentWeight().signum() <= 0) {
            throw ApiException.unprocessable("parentWeight 必须为正数: " + req.parentWeight());
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (SplitRequest.ChildSpec child : req.children()) {
            if (child.weight() == null) {
                throw ApiException.unprocessable(
                        "父批已登记效价模型，子批 " + child.batchKey() + " 必须提供 weight");
            }
            if (child.weight().signum() <= 0) {
                throw ApiException.unprocessable(
                        "子批 " + child.batchKey() + " weight 必须为正数: " + child.weight());
            }
            sum = sum.add(child.weight());
        }
        if (sum.compareTo(req.parentWeight()) != 0) {
            throw ApiException.unprocessable(
                    "拆分权重不守恒：子批权重之和 " + sum.toPlainString()
                            + " 不等于父批权重 " + req.parentWeight().toPlainString(),
                    Map.of("requiredWeight", req.parentWeight(), "actualWeight", sum,
                            "difference", sum.subtract(req.parentWeight())));
        }
    }

    /**
     * 祖先查询：从直接父批逐级向上到根（合批子批沿全部父批展开），按血缘创建顺序
     * 广度优先、按首次发现去重，每项含批次自身状态及导致其不可用的召回祖先。
     */
    public List<LineageEntryResponse> listAncestors(String batchKey) {
        repo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        Map<String, List<String>> parentsOf = childToParents();
        Set<String> recalled = new HashSet<>(repo.findRecalledKeys());
        List<LineageEntryResponse> result = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(batchKey);
        visited.add(batchKey);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (String parent : parentsOf.getOrDefault(current, List.of())) {
                if (visited.add(parent)) {
                    result.add(toLineageEntry(parent, parentsOf, recalled));
                    queue.add(parent);
                }
            }
        }
        return result;
    }

    /**
     * 后代查询：按血缘关系创建顺序广度优先展开，每项含批次自身状态及导致其不可用的召回祖先。
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
     * 若任一级祖先已被召回则抛 422：后代批次禁止新增检验、批准和拆分。
     */
    private void assertNoRecalledAncestor(String batchKey) {
        Optional<String> recalled = recalledAncestor(batchKey, childToParents(),
                new HashSet<>(repo.findRecalledKeys()));
        if (recalled.isPresent()) {
            throw ApiException.unprocessable(
                    "祖先批次 " + recalled.get() + " 已召回，禁止新增检验、批准和拆分");
        }
    }

    /**
     * 沿全部父链向上查找最近的被直接召回（RECALLED）祖先（按血缘创建顺序广度优先，
     * 命中即返回）；批次自身召回不算祖先召回。
     */
    private Optional<String> recalledAncestor(String batchKey, Map<String, List<String>> parentsOf,
                                              Set<String> recalled) {
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(batchKey);
        visited.add(batchKey);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (String parent : parentsOf.getOrDefault(current, List.of())) {
                if (recalled.contains(parent)) {
                    return Optional.of(parent);
                }
                if (visited.add(parent)) {
                    queue.add(parent);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * 全部血缘边的 子批→父批列表 映射（合批子批可有多个父批，按血缘创建顺序）；
     * 关系不可改写，只增不改。
     */
    private Map<String, List<String>> childToParents() {
        Map<String, List<String>> parentsOf = new HashMap<>();
        for (BatchRepository.LineageRow row : repo.findAllLineage()) {
            parentsOf.computeIfAbsent(row.childKey(), k -> new ArrayList<>()).add(row.parentKey());
        }
        return parentsOf;
    }

    /**
     * 某批次的全部后代业务键，按血缘关系创建顺序广度优先展开（不含自身）；
     * 合批形成的汇流路径按首次发现去重。
     */
    private List<String> descendantKeys(String rootKey) {
        Map<String, List<String>> childrenOf = new HashMap<>();
        for (BatchRepository.LineageRow row : repo.findAllLineage()) {
            childrenOf.computeIfAbsent(row.parentKey(), k -> new ArrayList<>()).add(row.childKey());
        }
        List<String> result = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(rootKey);
        visited.add(rootKey);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (String child : childrenOf.getOrDefault(current, List.of())) {
                if (visited.add(child)) {
                    result.add(child);
                    queue.add(child);
                }
            }
        }
        return result;
    }

    private LineageEntryResponse toLineageEntry(String batchKey,
                                                Map<String, List<String>> parentsOf,
                                                Set<String> recalled) {
        BatchRepository.BatchRow row = repo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        return new LineageEntryResponse(row.batchKey(), row.batchNo(),
                BatchStatus.valueOf(row.status()),
                recalledAncestor(batchKey, parentsOf, recalled).orElse(null));
    }

    /**
     * 登记或修订批次效价衰减模型：初始效价、最低放行效价与每 UTC 日线性衰减率。
     * 任一参数为负返回 422；每次成功登记追加新版本（历史版本不改写），
     * 新版本以登记时刻为衰减起算点。与放行共用批次行锁，按事务提交顺序裁决。
     */
    public StoredResponse registerPotencyModel(String batchKey, String actorId,
                                               PotencyModelRequest req) {
        if (actorId == null || actorId.isBlank()) {
            throw ApiException.badRequest("X-Actor-Id 不能为空");
        }
        String actor = actorId.trim();
        String fingerprint = fingerprint("potency-model", batchKey, actor,
                req.initialPotency().toPlainString(), req.minReleasePotency().toPlainString(),
                req.decayRatePerDay().toPlainString());
        return executeIdempotent(CMD_POTENCY_MODEL, req.commandKey(), fingerprint, () -> {
            BatchRepository.BatchRow batch = repo.findBatchForUpdate(batchKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
            var logged = loggedResponse(CMD_POTENCY_MODEL, req.commandKey(), fingerprint);
            if (logged.isPresent()) {
                return logged.get();
            }
            assertNoRecalledAncestor(batchKey);
            BatchStatus status = BatchStatus.valueOf(batch.status());
            if (status == BatchStatus.REJECTED || status == BatchStatus.RECALLED
                    || status == BatchStatus.SPLIT || status == BatchStatus.MERGED) {
                throw ApiException.conflict("批次状态 " + status + " 不允许登记或修订效价模型");
            }
            if (req.initialPotency().signum() < 0) {
                throw ApiException.unprocessable("初始效价不允许为负: " + req.initialPotency());
            }
            if (req.minReleasePotency().signum() < 0) {
                throw ApiException.unprocessable("最低放行效价不允许为负: " + req.minReleasePotency());
            }
            if (req.decayRatePerDay().signum() < 0) {
                throw ApiException.unprocessable("衰减率不允许为负: " + req.decayRatePerDay());
            }
            String now = now();
            int version = repo.findLatestPotencyModel(batchKey)
                    .map(m -> m.version() + 1)
                    .orElse(1);
            BatchRepository.PotencyModelRow row = new BatchRepository.PotencyModelRow(0L, batchKey,
                    version, req.initialPotency(), req.minReleasePotency(), req.decayRatePerDay(),
                    now, now);
            repo.insertPotencyModel(row);
            return new StoredResponse(201, toJson(toPotencyModelResponse(row)));
        });
    }

    /**
     * 合批：2～5 个当前可用的 RELEASED 父批按权重合并为一个全新子批。
     * 全部父批必须完成检验并放行、已登记效价模型、合批时刻当前效价不低于各自放行阈值，
     * 且父批权重之和等于子批权重（权重守恒）；任一不满足整次 422/409 回滚，查不到半成品。
     * 子批初始效价为父批当前效价的加权平均，阈值与衰减率继承规范化排序（batchKey 升序）
     * 的首个父批；父批置为 MERGED 不再可用。所有对象写入在同一事务提交。
     */
    public StoredResponse merge(String actorId, MergeRequest req) {
        if (actorId == null || actorId.isBlank()) {
            throw ApiException.badRequest("X-Actor-Id 不能为空");
        }
        String actor = actorId.trim();
        // 规范化父批集合：按 batchKey 升序排序去重，使指纹与继承规则不依赖请求顺序
        List<MergeRequest.ParentSpec> parents = req.parents().stream()
                .sorted(Comparator.comparing(MergeRequest.ParentSpec::batchKey))
                .toList();
        List<String> parentKeys = parents.stream().map(MergeRequest.ParentSpec::batchKey).toList();
        if (new HashSet<>(parentKeys).size() != parentKeys.size()) {
            throw ApiException.conflict("父批 batchKey 在请求内重复");
        }
        if (parentKeys.contains(req.child().batchKey())) {
            throw ApiException.conflict("子批 batchKey 不得与父批相同: " + req.child().batchKey());
        }
        List<String> parts = new ArrayList<>();
        parts.add("merge");
        parts.add(actor);
        for (MergeRequest.ParentSpec parent : parents) {
            parts.add(parent.batchKey());
            parts.add(parent.weight() == null ? "" : parent.weight().toPlainString());
        }
        parts.add(req.child().batchKey());
        parts.add(req.child().batchNo());
        parts.add(req.child().weight() == null ? "" : req.child().weight().toPlainString());
        String fingerprint = fingerprint(parts.toArray(new String[0]));
        return executeIdempotent(CMD_MERGE, req.commandKey(), fingerprint, () -> {
            // 按业务键升序逐行锁定全部父批，锁顺序确定避免死锁；
            // 与父批上的检验/批准/召回/模型修订互斥，按事务提交顺序裁决
            List<BatchRepository.BatchRow> parentRows = new ArrayList<>(parents.size());
            for (String parentKey : parentKeys) {
                parentRows.add(repo.findBatchForUpdate(parentKey)
                        .orElseThrow(() -> ApiException.notFound("父批次不存在: " + parentKey)));
            }
            var logged = loggedResponse(CMD_MERGE, req.commandKey(), fingerprint);
            if (logged.isPresent()) {
                return logged.get();
            }
            // 先校验完整最终状态，再做任何写入
            String now = now();
            Instant evaluatedAt = Instant.parse(now);
            List<BatchRepository.PotencyModelRow> models = new ArrayList<>(parents.size());
            List<BigDecimal> currentPotencies = new ArrayList<>(parents.size());
            List<BigDecimal> weights = new ArrayList<>(parents.size());
            for (int i = 0; i < parents.size(); i++) {
                MergeRequest.ParentSpec spec = parents.get(i);
                BatchRepository.BatchRow parent = parentRows.get(i);
                assertNoRecalledAncestor(spec.batchKey());
                BatchStatus status = BatchStatus.valueOf(parent.status());
                if (status == BatchStatus.QUARANTINED || status == BatchStatus.PENDING_RELEASE
                        || status == BatchStatus.RELEASE_REVIEW) {
                    throw ApiException.unprocessable(
                            "父批次 " + spec.batchKey() + " 未完成检验与放行（状态 " + status + "），不能合批");
                }
                if (status != BatchStatus.RELEASED) {
                    throw ApiException.conflict(
                            "父批次 " + spec.batchKey() + " 状态 " + status + " 不允许合批");
                }
                if (spec.weight() == null || spec.weight().signum() <= 0) {
                    throw ApiException.unprocessable(
                            "父批 " + spec.batchKey() + " weight 必须为正数: " + spec.weight());
                }
                BatchRepository.PotencyModelRow model = repo
                        .findLatestPotencyModel(spec.batchKey())
                        .orElseThrow(() -> ApiException.unprocessable(
                                "父批次 " + spec.batchKey() + " 未登记效价模型，不能合批"));
                PotencyStatusResponse evaluation = evaluatePotency(model, evaluatedAt);
                if (evaluation.currentPotency().compareTo(model.minReleasePotency()) < 0) {
                    throw ApiException.unprocessable(
                            "父批次 " + spec.batchKey() + " 当前效价 " + evaluation.currentPotency()
                                    + " 低于最低放行效价 " + model.minReleasePotency() + "，整次合批回滚",
                            Map.of("parentBatchKey", spec.batchKey(),
                                    "actualPotency", evaluation.currentPotency(),
                                    "requiredPotency", model.minReleasePotency(),
                                    "difference", evaluation.difference()));
                }
                models.add(model);
                currentPotencies.add(evaluation.currentPotency());
                weights.add(spec.weight());
            }
            if (req.child().weight() == null || req.child().weight().signum() <= 0) {
                throw ApiException.unprocessable("子批 weight 必须为正数: " + req.child().weight());
            }
            BigDecimal weightSum = weights.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            if (weightSum.compareTo(req.child().weight()) != 0) {
                throw ApiException.unprocessable(
                        "合批权重不守恒：父批权重之和 " + weightSum.toPlainString()
                                + " 不等于子批权重 " + req.child().weight().toPlainString(),
                        Map.of("requiredWeight", req.child().weight(), "actualWeight", weightSum,
                                "difference", weightSum.subtract(req.child().weight())));
            }
            if (repo.findBatch(req.child().batchKey()).isPresent()) {
                throw ApiException.conflict("子批 batchKey 已存在: " + req.child().batchKey());
            }
            // 校验全部通过：子批初始效价 = 父批当前效价加权平均，继承首个（规范化排序）父批
            BigDecimal childInitialPotency = PotencyCalculator.weightedAverage(currentPotencies,
                    weights);
            BatchRepository.BatchRow firstParent = parentRows.get(0);
            BatchRepository.PotencyModelRow firstModel = models.get(0);
            repo.insertBatch(new BatchRepository.BatchRow(0L, req.child().batchKey(),
                    firstParent.productCode(), req.child().batchNo(), firstParent.producedAt(),
                    BatchStatus.QUARANTINED.name(), now));
            List<String> required = repo.findRequiredTests(firstParent.batchKey());
            for (int i = 0; i < required.size(); i++) {
                repo.insertRequiredTest(req.child().batchKey(), required.get(i), i + 1);
            }
            repo.insertPotencyModel(new BatchRepository.PotencyModelRow(0L, req.child().batchKey(),
                    1, childInitialPotency, firstModel.minReleasePotency(),
                    firstModel.decayRatePerDay(), now, now));
            List<MergeResponse.MergeParent> parentBodies = new ArrayList<>(parents.size());
            for (int i = 0; i < parents.size(); i++) {
                BatchRepository.PotencyModelRow model = models.get(i);
                repo.insertLineage(new BatchRepository.LineageRow(0L, parents.get(i).batchKey(),
                        req.child().batchKey(), i + 1, now));
                repo.insertPotencyEvaluation(new BatchRepository.PotencyEvalRow(0L,
                        parents.get(i).batchKey(), "MERGE", req.child().batchKey(), now,
                        model.version(), model.initialPotency(), model.decayRatePerDay(),
                        model.minReleasePotency(),
                        PotencyCalculator.elapsedUtcDays(Instant.parse(model.registeredAt()),
                                evaluatedAt),
                        currentPotencies.get(i), now));
                repo.updateStatus(parents.get(i).batchKey(), BatchStatus.MERGED.name());
                parentBodies.add(new MergeResponse.MergeParent(parents.get(i).batchKey(),
                        BatchStatus.MERGED, weights.get(i), currentPotencies.get(i),
                        model.minReleasePotency(),
                        currentPotencies.get(i).subtract(model.minReleasePotency())));
            }
            MergeResponse body = new MergeResponse(req.child().batchKey(), BatchStatus.QUARANTINED,
                    req.child().weight(), childInitialPotency, parentBodies, evaluatedAt);
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 批次当前效价诊断：最新效价模型 + 以当前时钟计算的经过天数、当前效价、
     * 放行阈值、差额与是否满足放行；未登记模型时各计算字段为 null。只读，不改变状态。
     */
    public PotencyStatusResponse potencyStatus(String batchKey) {
        repo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        Instant evaluatedAt = Instant.now(clock);
        return repo.findLatestPotencyModel(batchKey)
                .map(model -> evaluatePotency(model, evaluatedAt))
                .orElse(new PotencyStatusResponse(batchKey, null, evaluatedAt, null, null, null,
                        null, null));
    }

    /**
     * 放行时点核验：批次已登记效价模型时，以当前时钟计算当前效价，
     * 低于最低放行效价抛 422（携带实际值/要求值/差额）；通过则固化评估记录。
     * 调用方已持有批次行锁，模型读取与模型修订按事务提交顺序裁决。
     */
    private void assertReleasePotency(String batchKey) {
        Optional<BatchRepository.PotencyModelRow> model = repo.findLatestPotencyModel(batchKey);
        if (model.isEmpty()) {
            return;
        }
        BatchRepository.PotencyModelRow m = model.get();
        String now = now();
        PotencyStatusResponse evaluation = evaluatePotency(m, Instant.parse(now));
        if (evaluation.currentPotency().compareTo(m.minReleasePotency()) < 0) {
            throw ApiException.unprocessable(
                    "当前效价 " + evaluation.currentPotency() + " 低于最低放行效价 "
                            + m.minReleasePotency() + "，不允许放行",
                    Map.of("actualPotency", evaluation.currentPotency(),
                            "requiredPotency", m.minReleasePotency(),
                            "difference", evaluation.difference()));
        }
        repo.insertPotencyEvaluation(new BatchRepository.PotencyEvalRow(0L, batchKey, "RELEASE",
                null, now, m.version(), m.initialPotency(), m.decayRatePerDay(),
                m.minReleasePotency(), evaluation.elapsedDays(), evaluation.currentPotency(), now));
    }

    /**
     * 按模型与计算时刻求当前效价：经过 UTC 整日数（左闭右开）、线性衰减、4 位小数 HALF_UP。
     */
    private PotencyStatusResponse evaluatePotency(BatchRepository.PotencyModelRow model,
                                                  Instant evaluatedAt) {
        long elapsedDays = PotencyCalculator.elapsedUtcDays(Instant.parse(model.registeredAt()),
                evaluatedAt);
        BigDecimal current = PotencyCalculator.currentPotency(model.initialPotency(),
                model.decayRatePerDay(), elapsedDays);
        BigDecimal difference = current.subtract(model.minReleasePotency());
        return new PotencyStatusResponse(model.batchKey(), toPotencyModelResponse(model),
                evaluatedAt, elapsedDays, current, model.minReleasePotency(), difference,
                difference.signum() >= 0);
    }

    private PotencyModelResponse toPotencyModelResponse(BatchRepository.PotencyModelRow row) {
        return new PotencyModelResponse(row.batchKey(), row.version(), row.initialPotency(),
                row.minReleasePotency(), row.decayRatePerDay(), Instant.parse(row.registeredAt()),
                Instant.parse(row.createdAt()));
    }

    private PotencyEvaluationResponse toPotencyEvaluationResponse(BatchRepository.PotencyEvalRow row) {
        return new PotencyEvaluationResponse(row.id(), row.batchKey(), row.operation(),
                row.relatedKey(), Instant.parse(row.evaluatedAt()), row.modelVersion(),
                row.initialPotency(), row.decayRatePerDay(), row.minReleasePotency(),
                row.elapsedDays(), row.currentPotency());
    }
}
