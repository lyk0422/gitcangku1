package com.example.starter.batch;

import com.example.starter.batch.dto.ApprovalResponse;
import com.example.starter.batch.dto.ApproveRequest;
import com.example.starter.batch.dto.BatchHistoryResponse;
import com.example.starter.batch.dto.BatchResponse;
import com.example.starter.batch.dto.CloseConditionRequest;
import com.example.starter.batch.dto.CloseConditionResponse;
import com.example.starter.batch.dto.ConditionItemInput;
import com.example.starter.batch.dto.ConditionItemResponse;
import com.example.starter.batch.dto.ConditionReleaseResponse;
import com.example.starter.batch.dto.CreateBatchRequest;
import com.example.starter.batch.dto.CreateConditionRequest;
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
import java.time.Clock;
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
    private static final String CMD_CLOSE_CONDITION = "CLOSE_CONDITION";

    /**
     * 指纹拼接分隔符（NUL）：业务参数不可能包含该字符，避免拼接碰撞。
     */
    private static final String SEP = "\u0000";

    private static final int IDEMPOTENCY_MAX_ATTEMPTS = 3;

    private final BatchRepository repo;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public BatchService(BatchRepository repo,
                        PlatformTransactionManager transactionManager,
                        ObjectMapper objectMapper,
                        Clock clock) {
        this.repo = repo;
        this.tx = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
        this.clock = clock;
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
                    || status == BatchStatus.RECALLED || status == BatchStatus.CONDITIONAL) {
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
            if (status == BatchStatus.QUARANTINED) {
                throw ApiException.unprocessable("必做检验项未全部通过，不能批准");
            }
            if (status == BatchStatus.CONDITIONAL) {
                // CONDITIONAL 期间不得直接走双角色批准；仅在上一条条件放行到期降级后
                // （批次状态未回写，按最新条件放行到期时刻实时判定）才允许改走原批准流程。
                if (!isLatestConditionExpired(batchKey)) {
                    throw ApiException.conflict("批次处于 CONDITIONAL 条件放行期内，不能直接批准");
                }
            } else if (status != BatchStatus.PENDING_RELEASE && status != BatchStatus.RELEASE_REVIEW) {
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
     * 召回：RELEASED 或条件放行期内（CONDITIONAL）的批次可召回，召回后进入 RECALLED
     * 并不再出现在可用批次查询中；已核销的条件子项记录保留，不被撤销。
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
            repo.updateStatus(batchKey, BatchStatus.RECALLED.name());
            RecallResponse body = new RecallResponse(batchKey, actor, req.reason(),
                    BatchStatus.RECALLED, Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 创建条件放行：批次须已通过全部必做检验但尚未批准（PENDING_RELEASE），
     * 或上一条条件放行已到期降级（批次行仍为 CONDITIONAL，但最新条件放行到期时刻已到）。
     * 创建后批次进入 CONDITIONAL，条件期内可标记为可用；同一 conditionKey 全局唯一。
     */
    public StoredResponse createCondition(String batchKey, String actorId, String roleHeader,
                                          CreateConditionRequest req) {
        if (actorId == null || actorId.isBlank()) {
            throw ApiException.badRequest("X-Actor-Id 不能为空");
        }
        ApprovalRole role = parseRole(roleHeader);
        String actor = actorId.trim();
        List<ConditionItemInput> items = req.conditions().stream()
                .map(c -> new ConditionItemInput(c.itemKey().trim(), c.description().trim()))
                .toList();
        Set<String> itemKeys = new HashSet<>();
        for (ConditionItemInput c : items) {
            if (!itemKeys.add(c.itemKey())) {
                throw ApiException.badRequest("conditions 存在重复子项 itemKey: " + c.itemKey());
            }
        }
        String fingerprint = fingerprint("createCondition", batchKey, actor, role.name(),
                req.conditionKey(), req.expiresAt().toString(),
                items.stream().map(c -> c.itemKey() + "=" + c.description())
                        .reduce("", (a, b) -> a + SEP + b));
        return executeIdempotent(CMD_CREATE_CONDITION, req.commandKey(), fingerprint, () -> {
            BatchRepository.BatchRow batch = repo.findBatchForUpdate(batchKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
            BatchStatus status = BatchStatus.valueOf(batch.status());
            if (status == BatchStatus.QUARANTINED || status == BatchStatus.REJECTED) {
                throw ApiException.unprocessable("必做检验项未全部通过，不能创建条件放行");
            }
            if (status == BatchStatus.CONDITIONAL && !isLatestConditionExpired(batchKey)) {
                throw ApiException.conflict("批次已在 CONDITIONAL 条件放行期内，不能再新建条件放行");
            }
            if (status == BatchStatus.RELEASE_REVIEW || status == BatchStatus.RELEASED
                    || status == BatchStatus.RECALLED) {
                throw ApiException.conflict("批次状态 " + status + " 不允许创建条件放行");
            }
            if (repo.findConditionalRelease(req.conditionKey()).isPresent()) {
                throw ApiException.conflict("conditionKey 已存在: " + req.conditionKey());
            }
            if (!req.expiresAt().isAfter(Instant.now(clock))) {
                throw ApiException.badRequest("expiresAt 必须晚于当前时刻");
            }
            // 批准人不得为该批次任一检验人（沿用既有批准前置约束）
            boolean actorInspected = repo.findTests(batchKey).stream()
                    .anyMatch(t -> t.inspector().equals(actor));
            if (actorInspected) {
                throw ApiException.unprocessable("批准人不得为该批次任一检验结果的检验人: " + actor);
            }

            String now = now();
            repo.insertConditionalRelease(new BatchRepository.ConditionalReleaseRow(0L,
                    req.conditionKey(), batchKey, req.commandKey(), actor, role.name(),
                    req.expiresAt().toString(), now));
            for (int i = 0; i < items.size(); i++) {
                repo.insertConditionItem(req.conditionKey(), items.get(i).itemKey(),
                        items.get(i).description(), i + 1);
            }
            repo.updateStatus(batchKey, BatchStatus.CONDITIONAL.name());
            ConditionReleaseResponse body = toConditionResponse(
                    repo.findConditionalRelease(req.conditionKey()).orElseThrow(),
                    BatchStatus.CONDITIONAL, false);
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 逐条核销条件子项：核销批准角色必须与创建角色不同、批准人不得为检验人；
     * 到期时刻已到仍有未核销子项时返回 422 并列出未核销子项（不删除记录）；
     * 全部子项核销后同一事务内批次转为 RELEASED；重复核销同一子项返回 409，不重复计数。
     */
    public StoredResponse closeCondition(String conditionKey, String actorId, String roleHeader,
                                         CloseConditionRequest req) {
        if (actorId == null || actorId.isBlank()) {
            throw ApiException.badRequest("X-Actor-Id 不能为空");
        }
        ApprovalRole role = parseRole(roleHeader);
        String actor = actorId.trim();
        String fingerprint = fingerprint("closeCondition", conditionKey, actor, role.name(),
                req.itemKey(), req.evidence());
        return executeIdempotent(CMD_CLOSE_CONDITION, req.commandKey(), fingerprint, () -> {
            BatchRepository.ConditionalReleaseRow release = repo.findConditionalRelease(conditionKey)
                    .orElseThrow(() -> ApiException.notFound("条件放行不存在: " + conditionKey));
            // 锁住批次行：与创建条件放行、批准、召回按事务提交顺序串行裁决
            BatchRepository.BatchRow batch = repo.findBatchForUpdate(release.batchKey())
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + release.batchKey()));
            List<BatchRepository.ConditionItemRow> items = repo.findConditionItems(conditionKey);
            BatchRepository.ConditionItemRow item = items.stream()
                    .filter(i -> i.itemKey().equals(req.itemKey()))
                    .findFirst()
                    .orElseThrow(() -> ApiException.notFound(
                            "条件子项不存在: " + conditionKey + "/" + req.itemKey()));
            if (item.closedAt() != null) {
                throw ApiException.conflict("条件子项已核销，不能重复核销: " + req.itemKey());
            }
            BatchStatus status = BatchStatus.valueOf(batch.status());
            List<String> openItems = openItemKeys(items);
            if (status == BatchStatus.RECALLED) {
                throw ConditionBlockedException.recalled(openItems);
            }
            boolean expired = !Instant.parse(release.expiresAt()).isAfter(Instant.now(clock));
            if (expired) {
                throw ConditionBlockedException.expired(openItems);
            }
            if (release.creatorRole().equals(role.name())) {
                throw ApiException.conflict(
                        "核销角色必须与创建条件放行的角色不同，创建角色为 " + release.creatorRole());
            }
            boolean actorInspected = repo.findTests(release.batchKey()).stream()
                    .anyMatch(t -> t.inspector().equals(actor));
            if (actorInspected) {
                throw ApiException.unprocessable("批准人不得为该批次任一检验结果的检验人: " + actor);
            }

            String closedAt = now();
            int updated = repo.closeConditionItemIfOpen(conditionKey, req.itemKey(), actor,
                    role.name(), req.commandKey(), req.evidence(), closedAt);
            if (updated == 0) {
                // 并发下被其他事务先行核销：不重复计数
                throw ApiException.conflict("条件子项已核销，不能重复核销: " + req.itemKey());
            }
            List<BatchRepository.ConditionItemRow> refreshed = repo.findConditionItems(conditionKey);
            int remaining = openItemKeys(refreshed).size();
            BatchStatus newStatus = status;
            if (remaining == 0) {
                newStatus = BatchStatus.RELEASED;
                repo.updateStatus(release.batchKey(), BatchStatus.RELEASED.name());
            }
            CloseConditionResponse body = new CloseConditionResponse(release.batchKey(), conditionKey,
                    req.itemKey(), actor, role.name(), req.evidence(), remaining, newStatus,
                    Instant.parse(closedAt));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 条件放行明细查询：返回创建信息、到期状态（按可注入时钟实时判定）与逐条子项核销状态；
     * 子项按 seq 稳定排序。
     */
    public ConditionReleaseResponse conditionDetail(String conditionKey) {
        BatchRepository.ConditionalReleaseRow release = repo.findConditionalRelease(conditionKey)
                .orElseThrow(() -> ApiException.notFound("条件放行不存在: " + conditionKey));
        BatchStatus batchStatus = repo.findBatch(release.batchKey())
                .map(b -> BatchStatus.valueOf(b.status()))
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + release.batchKey()));
        boolean expired = !Instant.parse(release.expiresAt()).isAfter(Instant.now(clock));
        return toConditionResponse(release, batchStatus, expired);
    }

    /**
     * 最新条件放行是否已到期（不回写批次状态）；无历史条件放行时返回 false。
     */
    private boolean isLatestConditionExpired(String batchKey) {
        return repo.findLatestConditionalReleaseForBatch(batchKey)
                .map(c -> !Instant.parse(c.expiresAt()).isAfter(Instant.now(clock)))
                .orElse(false);
    }

    private List<String> openItemKeys(List<BatchRepository.ConditionItemRow> items) {
        return items.stream()
                .filter(i -> i.closedAt() == null)
                .map(BatchRepository.ConditionItemRow::itemKey)
                .toList();
    }

    private ConditionReleaseResponse toConditionResponse(BatchRepository.ConditionalReleaseRow release,
                                                         BatchStatus batchStatus, boolean expired) {
        List<ConditionItemResponse> items = repo.findConditionItems(release.conditionKey()).stream()
                .map(i -> new ConditionItemResponse(i.itemKey(), i.description(), i.seq(),
                        i.closedAt() != null,
                        i.closedAt() == null ? null : Instant.parse(i.closedAt()),
                        i.closerId(), i.closerRole(), i.evidence()))
                .toList();
        return new ConditionReleaseResponse(release.batchKey(), release.conditionKey(),
                release.creatorId(), release.creatorRole(),
                Instant.parse(release.expiresAt()), Instant.parse(release.createdAt()),
                expired, batchStatus, items);
    }

    /**
     * 当前可用批次：排除已召回批次；CONDITIONAL 批次在最新条件放行到期且仍有未核销子项时
     * 由 SQL 实时降级排除，不回写批次状态、不依赖后台任务。
     */
    public List<BatchResponse> listAvailable() {
        return repo.findAvailableBatches(now()).stream().map(this::toBatchResponse).toList();
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
        // 条件放行记录（含到期降级与已核销子项）全部保留；expired 为按当前时钟的实时判定。
        List<ConditionReleaseResponse> conditions = repo.findConditionalReleasesForBatch(batchKey)
                .stream()
                .map(c -> toConditionResponse(c, BatchStatus.valueOf(batch.status()),
                        !Instant.parse(c.expiresAt()).isAfter(Instant.now(clock))))
                .toList();
        return new BatchHistoryResponse(toBatchResponse(batch), tests, approvals, recall, conditions);
    }

    /**
     * 幂等执行：事务内先读 command_log；未命中则先插入占位行（并发同键在此串行等待先到事务结局），
     * 再执行业务动作，成功后把首次响应快照更新到占位行并随事务提交。
     * 同键同参重放返回首次快照，同键改参返回 409；业务失败随事务回滚，占位行一并消失（失败不占键）。
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
                    repo.insertCommandPlaceholder(type, commandKey, fingerprint, now());
                    StoredResponse response = action.get();
                    repo.updateCommandResult(type, commandKey, response.status(), response.body());
                    return response;
                });
            } catch (DuplicateKeyException e) {
                // 并发同键：占位 INSERT 等待先到事务提交后得到唯一键冲突；回滚后重试，读取已提交快照
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
        return Instant.now(clock).toString();
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
