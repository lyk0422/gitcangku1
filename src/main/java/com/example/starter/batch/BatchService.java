package com.example.starter.batch;

import com.example.starter.batch.dto.ApprovalResponse;
import com.example.starter.batch.dto.ApproveRequest;
import com.example.starter.batch.dto.BatchHistoryResponse;
import com.example.starter.batch.dto.BatchResponse;
import com.example.starter.batch.dto.ClearConditionItemRequest;
import com.example.starter.batch.dto.ClearConditionItemResponse;
import com.example.starter.batch.dto.ConditionItemResponse;
import com.example.starter.batch.dto.ConditionalReleaseResponse;
import com.example.starter.batch.dto.CreateBatchRequest;
import com.example.starter.batch.dto.CreateConditionalReleaseRequest;
import com.example.starter.batch.dto.RecallRequest;
import com.example.starter.batch.dto.RecallResponse;
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
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
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
    private static final String CMD_CREATE_CONDITION = "CREATE_CONDITION";
    private static final String CMD_CLEAR_CONDITION = "CLEAR_CONDITION";

    /**
     * 指纹拼接分隔符（NUL）：业务参数不可能包含该字符，避免拼接碰撞。
     */
    private static final String SEP = "\u0000";

    private static final int IDEMPOTENCY_MAX_ATTEMPTS = 3;

    private final BatchRepository repo;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper;
    private final TimeSource timeSource;

    public BatchService(BatchRepository repo,
                        PlatformTransactionManager transactionManager,
                        ObjectMapper objectMapper,
                        TimeSource timeSource) {
        this.repo = repo;
        this.tx = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
        this.timeSource = timeSource;
    }

    /**
     * 创建批次：初始状态 QUARANTINED；batchKey 全局唯一，重复返回 409。
     */
    public StoredResponse createBatch(CreateBatchRequest req) {
        List<String> items = req.requiredTests().stream().map(String::trim).toList();
        if (new HashSet<>(items).size() != items.size()) {
            throw ApiException.badRequest("requiredTests 存在重复检验项");
        }
        String fingerprint = fingerprint("create", req.batchKey(), req.productCode(), req.batchNo(),
                req.producedAt().toString(), String.join(SEP, items));
        return executeIdempotent(CMD_CREATE, req.commandKey(), fingerprint, () -> {
            repo.findBatch(req.batchKey()).ifPresent(b -> {
                throw ApiException.conflict("batchKey 已存在: " + req.batchKey());
            });
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
            if (status == BatchStatus.REJECTED || status == BatchStatus.RELEASED
                    || status == BatchStatus.RECALLED) {
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
            // 条件到期降级后可走原双角色批准流程；CONDITIONAL 期间不允许直接批准
            batch = downgradeIfExpired(batch);
            BatchStatus status = BatchStatus.valueOf(batch.status());
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
            repo.insertApproval(new BatchRepository.ApprovalRow(0L, batchKey, req.commandKey(),
                    actor, role.name(), seq, now));
            repo.updateStatus(batchKey, newStatus.name());
            ApprovalResponse body = new ApprovalResponse(batchKey, actor, role, seq, newStatus,
                    Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 召回：RELEASED 或 CONDITIONAL（条件期内可用）批次可召回，召回后进入 RECALLED 并不再出现在可用批次查询中。
     * CONDITIONAL 批次召回时其 ACTIVE 条件放行置为 EXPIRED 并保留全部子项与已核销记录。
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
            if (status != BatchStatus.RELEASED && status != BatchStatus.CONDITIONAL) {
                throw ApiException.conflict("批次状态 " + status + " 不允许召回，仅 RELEASED/CONDITIONAL 可召回");
            }
            String now = now();
            repo.insertRecall(new BatchRepository.RecallRow(0L, batchKey, req.commandKey(),
                    actor, req.reason(), now));
            if (status == BatchStatus.CONDITIONAL) {
                // 条件放行随召回终止：未核销子项保留，已核销记录不撤销
                repo.findActiveConditionByBatch(batchKey)
                        .ifPresent(c -> repo.updateConditionStatus(c.conditionKey(), "EXPIRED", null));
            }
            repo.updateStatus(batchKey, BatchStatus.RECALLED.name());
            RecallResponse body = new RecallResponse(batchKey, actor, req.reason(),
                    BatchStatus.RECALLED, Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 创建条件放行：仅 PENDING_RELEASE（全部必做检验通过且尚未批准）批次可创建；
     * 创建后批次进入 CONDITIONAL，条件期内出现在可用批次中。conditionKey 全局唯一。
     */
    public StoredResponse createConditionalRelease(String batchKey, String actorId,
                                                   String roleHeader,
                                                   CreateConditionalReleaseRequest req) {
        if (actorId == null || actorId.isBlank()) {
            throw ApiException.badRequest("X-Actor-Id 不能为空");
        }
        ApprovalRole role = parseRole(roleHeader);
        String actor = actorId.trim();
        List<String> conditions = req.conditions().stream().map(String::trim).toList();
        String fingerprint = fingerprint("create-condition", batchKey, actor, role.name(),
                req.conditionKey(), String.join(SEP, conditions), req.expiresAt().toString());
        return executeIdempotent(CMD_CREATE_CONDITION, req.commandKey(), fingerprint, () -> {
            BatchRepository.BatchRow batch = repo.findBatchForUpdate(batchKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
            batch = downgradeIfExpired(batch);
            BatchStatus status = BatchStatus.valueOf(batch.status());
            if (status == BatchStatus.QUARANTINED) {
                throw ApiException.unprocessable("必做检验项未全部通过，不能创建条件放行");
            }
            if (status == BatchStatus.CONDITIONAL) {
                throw ApiException.conflict("批次已存在进行中的条件放行，不得重复创建");
            }
            if (status != BatchStatus.PENDING_RELEASE) {
                throw ApiException.conflict("批次状态 " + status + " 不允许创建条件放行");
            }
            Instant now = timeSource.now();
            if (!req.expiresAt().isAfter(now)) {
                throw ApiException.unprocessable("条件有效期必须晚于当前时刻: " + req.expiresAt());
            }
            repo.findCondition(req.conditionKey()).ifPresent(c -> {
                throw ApiException.conflict("conditionKey 已存在: " + req.conditionKey());
            });
            String nowIso = now.toString();
            repo.insertCondition(new BatchRepository.ConditionRow(0L, batchKey, req.conditionKey(),
                    actor, role.name(), req.expiresAt().toString(),
                    ConditionStatus.ACTIVE.name(), nowIso, null));
            for (int i = 0; i < conditions.size(); i++) {
                repo.insertConditionItem(new BatchRepository.ConditionItemRow(0L, req.conditionKey(),
                        batchKey, String.valueOf(i + 1), conditions.get(i), i + 1, 0,
                        null, null, null, null));
            }
            repo.updateStatus(batchKey, BatchStatus.CONDITIONAL.name());
            ConditionalReleaseResponse body = toConditionResponse(
                    repo.findCondition(req.conditionKey()).orElseThrow(), timeSource.now());
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 核销条件子项：须由与创建角色不同的批准角色提交；全部子项核销后同事务把批次转为 RELEASED。
     * 批次已召回或条件已到期（仍有未核销子项）返回 422 并列出未核销子项。
     */
    public StoredResponse clearConditionItem(String batchKey, String conditionKey, String actorId,
                                             String roleHeader, ClearConditionItemRequest req) {
        if (actorId == null || actorId.isBlank()) {
            throw ApiException.badRequest("X-Actor-Id 不能为空");
        }
        ApprovalRole role = parseRole(roleHeader);
        String actor = actorId.trim();
        String fingerprint = fingerprint("clear-condition", batchKey, conditionKey, actor,
                role.name(), req.itemKey(), req.evidence());
        return executeIdempotent(CMD_CLEAR_CONDITION, req.commandKey(), fingerprint, () -> {
            BatchRepository.BatchRow batch = repo.findBatchForUpdate(batchKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
            BatchRepository.ConditionRow condition = repo.findConditionForUpdate(conditionKey)
                    .filter(c -> c.batchKey().equals(batchKey))
                    .orElseThrow(() -> ApiException.notFound(
                            "条件放行不存在: " + conditionKey));
            List<String> pending = pendingItemKeys(conditionKey);
            BatchStatus batchStatus = BatchStatus.valueOf(batch.status());
            if (batchStatus == BatchStatus.RECALLED) {
                throw ApiException.unprocessable("批次已召回，条件核销不再受理", pending);
            }
            if (ConditionStatus.EXPIRED.name().equals(condition.status())) {
                throw ApiException.unprocessable("条件已到期，批次已降级为不可用", pending);
            }
            if (ConditionStatus.FULFILLED.name().equals(condition.status())) {
                throw ApiException.conflict("条件已全部核销完成: " + conditionKey);
            }
            if (batchStatus != BatchStatus.CONDITIONAL) {
                throw ApiException.conflict("批次状态 " + batchStatus + " 不允许核销条件");
            }
            // 到期判定：可注入时钟，到期且仍有未核销子项时先降级再返回 422
            if (!Instant.parse(condition.expiresAt()).isAfter(timeSource.now())) {
                repo.updateConditionStatus(conditionKey, ConditionStatus.EXPIRED.name(), null);
                repo.updateStatus(batchKey, BatchStatus.PENDING_RELEASE.name());
                throw ApiException.unprocessable("条件已到期，批次已降级为不可用", pending);
            }
            if (role.name().equals(condition.createdRole())) {
                throw ApiException.unprocessable(
                        "核销角色必须与创建角色不同，创建角色: " + condition.createdRole());
            }
            BatchRepository.ConditionItemRow item = repo.findConditionItems(conditionKey).stream()
                    .filter(i -> i.itemKey().equals(req.itemKey()))
                    .findFirst()
                    .orElseThrow(() -> ApiException.notFound(
                            "条件子项不存在: " + req.itemKey()));
            if (item.cleared() != 0) {
                throw ApiException.conflict("条件子项已核销，不得重复计数: " + req.itemKey());
            }
            String now = now();
            int updated = repo.clearConditionItem(conditionKey, req.itemKey(), actor, role.name(),
                    req.evidence(), now);
            if (updated == 0) {
                throw ApiException.conflict("条件子项已核销，不得重复计数: " + req.itemKey());
            }
            List<String> remaining = pendingItemKeys(conditionKey);
            BatchStatus newStatus = batchStatus;
            if (remaining.isEmpty()) {
                repo.updateConditionStatus(conditionKey, ConditionStatus.FULFILLED.name(), now);
                repo.updateStatus(batchKey, BatchStatus.RELEASED.name());
                newStatus = BatchStatus.RELEASED;
            }
            ClearConditionItemResponse body = new ClearConditionItemResponse(batchKey, conditionKey,
                    req.itemKey(), actor, role.name(), newStatus, remaining, Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 批次全部条件放行明细（含未核销子项与到期状态），按创建顺序稳定排序。
     */
    public List<ConditionalReleaseResponse> listConditions(String batchKey) {
        return tx.execute(status -> {
            repo.findBatchForUpdate(batchKey)
                    .ifPresent(this::downgradeIfExpired);
            if (repo.findBatch(batchKey).isEmpty()) {
                throw ApiException.notFound("批次不存在: " + batchKey);
            }
            Instant now = timeSource.now();
            return repo.findConditionsByBatch(batchKey).stream()
                    .map(c -> toConditionResponse(c, now))
                    .toList();
        });
    }

    /**
     * 单条条件放行明细：全部子项 + 未核销子项标识 + 到期状态。
     */
    public ConditionalReleaseResponse conditionDetail(String batchKey, String conditionKey) {
        return tx.execute(status -> {
            repo.findBatchForUpdate(batchKey)
                    .ifPresent(this::downgradeIfExpired);
            BatchRepository.ConditionRow condition = repo.findCondition(conditionKey)
                    .filter(c -> c.batchKey().equals(batchKey))
                    .orElseThrow(() -> ApiException.notFound("条件放行不存在: " + conditionKey));
            return toConditionResponse(condition, timeSource.now());
        });
    }

    /**
     * 到期降级：CONDITIONAL 批次且条件到期仍有未核销子项时，条件置 EXPIRED、批次降回 PENDING_RELEASE。
     * 判定使用可注入时钟，不依赖后台任务；返回最新的批次行。
     */
    private BatchRepository.BatchRow downgradeIfExpired(BatchRepository.BatchRow batch) {
        if (!BatchStatus.CONDITIONAL.name().equals(batch.status())) {
            return batch;
        }
        var active = repo.findActiveConditionByBatch(batch.batchKey());
        if (active.isEmpty()) {
            return batch;
        }
        BatchRepository.ConditionRow condition = active.get();
        if (Instant.parse(condition.expiresAt()).isAfter(timeSource.now())) {
            return batch;
        }
        if (pendingItemKeys(condition.conditionKey()).isEmpty()) {
            return batch;
        }
        repo.updateConditionStatus(condition.conditionKey(), ConditionStatus.EXPIRED.name(), null);
        repo.updateStatus(batch.batchKey(), BatchStatus.PENDING_RELEASE.name());
        return repo.findBatch(batch.batchKey()).orElse(batch);
    }

    private List<String> pendingItemKeys(String conditionKey) {
        return repo.findConditionItems(conditionKey).stream()
                .filter(i -> i.cleared() == 0)
                .map(BatchRepository.ConditionItemRow::itemKey)
                .toList();
    }

    private ConditionalReleaseResponse toConditionResponse(BatchRepository.ConditionRow row,
                                                           Instant now) {
        List<ConditionItemResponse> items = repo.findConditionItems(row.conditionKey()).stream()
                .map(i -> new ConditionItemResponse(i.itemKey(), i.description(), i.cleared() != 0,
                        i.clearedBy(), i.clearedRole(), i.evidence(),
                        i.clearedAt() == null ? null : Instant.parse(i.clearedAt())))
                .toList();
        List<String> pending = items.stream().filter(i -> !i.cleared())
                .map(ConditionItemResponse::itemKey).toList();
        ConditionStatus status = ConditionStatus.valueOf(row.status());
        boolean expired = status == ConditionStatus.EXPIRED
                || (status == ConditionStatus.ACTIVE && !pending.isEmpty()
                        && !Instant.parse(row.expiresAt()).isAfter(now));
        return new ConditionalReleaseResponse(row.batchKey(), row.conditionKey(), row.createdBy(),
                row.createdRole(), Instant.parse(row.expiresAt()), status, expired, items, pending,
                Instant.parse(row.createdAt()),
                row.completedAt() == null ? null : Instant.parse(row.completedAt()));
    }

    /**
     * 当前可用批次：排除已召回（RECALLED）批次；先做到期降级，排除条件到期未核销的批次。
     */
    public List<BatchResponse> listAvailable() {
        return tx.execute(status -> {
            for (BatchRepository.ConditionRow c : repo.findActiveConditions()) {
                repo.findBatchForUpdate(c.batchKey()).ifPresent(this::downgradeIfExpired);
            }
            return repo.findAvailableBatches().stream().map(this::toBatchResponse).toList();
        });
    }

    /**
     * 批次完整历史：批次概要 + 全部检验 + 全部批准 + 召回记录 + 全部条件放行记录，
     * 历史不因召回或条件到期而删除或改写。
     */
    public BatchHistoryResponse history(String batchKey) {
        return tx.execute(status -> {
            BatchRepository.BatchRow batch = repo.findBatchForUpdate(batchKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
            batch = downgradeIfExpired(batch);
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
                        return new ApprovalResponse(a.batchKey(), a.actorId(),
                                ApprovalRole.valueOf(a.role()), a.seq(), snapshot,
                                Instant.parse(a.createdAt()));
                    })
                    .toList();
            RecallResponse recall = repo.findRecall(batchKey)
                    .map(r -> new RecallResponse(r.batchKey(), r.actorId(), r.reason(),
                            BatchStatus.RECALLED, Instant.parse(r.createdAt())))
                    .orElse(null);
            Instant now = timeSource.now();
            List<ConditionalReleaseResponse> conditions = repo.findConditionsByBatch(batchKey)
                    .stream()
                    .map(c -> toConditionResponse(c, now))
                    .toList();
            return new BatchHistoryResponse(toBatchResponse(batch), tests, approvals, recall,
                    conditions);
        });
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
                    var existing = repo.findCommand(type, commandKey);
                    if (existing.isPresent()) {
                        BatchRepository.CommandRow row = existing.get();
                        if (!row.fingerprint().equals(fingerprint)) {
                            throw ApiException.conflict(
                                    "commandKey 已以不同参数使用: " + commandKey);
                        }
                        return new StoredResponse(row.responseStatus(), row.responseBody());
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
        return timeSource.now().toString();
    }

    private String fingerprint(String... parts) {        String canonical = String.join(SEP, parts);
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
