package com.example.starter.batch;

import com.example.starter.batch.dto.ApprovalResponse;
import com.example.starter.batch.dto.ApproveRequest;
import com.example.starter.batch.dto.BatchHistoryResponse;
import com.example.starter.batch.dto.BatchResponse;
import com.example.starter.batch.dto.CreateBatchRequest;
import com.example.starter.batch.dto.ExtensionConfirmRequest;
import com.example.starter.batch.dto.ExtensionResponse;
import com.example.starter.batch.dto.ExtensionSubmitRequest;
import com.example.starter.batch.dto.LineageEntryResponse;
import com.example.starter.batch.dto.RecallRequest;
import com.example.starter.batch.dto.RecallResponse;
import com.example.starter.batch.dto.ShelfLifeResponse;
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
import java.time.Duration;
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
    private static final String CMD_EXT_SUBMIT = "EXTENSION_SUBMIT";
    private static final String CMD_EXT_CONFIRM = "EXTENSION_CONFIRM";

    /**
     * 指纹拼接分隔符（NUL）：业务参数不可能包含该字符，避免拼接碰撞。
     */
    private static final String SEP = "\u0000";

    private static final int IDEMPOTENCY_MAX_ATTEMPTS = 3;

    /**
     * 复检结论合格的唯一可接受取值（去除首尾空白后精确匹配）。
     */
    private static final String QUALIFIED = "合格";

    /**
     * 同一批次复检延期次数上限。
     */
    private static final int MAX_EXTENSION_COUNT = 3;

    private final BatchRepository repo;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper;
    private final TimeProvider clock;

    public BatchService(BatchRepository repo,
                        PlatformTransactionManager transactionManager,
                        ObjectMapper objectMapper,
                        TimeProvider clock) {
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
        int shelfLifeMinutes = req.shelfLifeMinutes();
        if (shelfLifeMinutes <= 0) {
            throw ApiException.badRequest("shelfLifeMinutes 必须为正整数");
        }
        String fingerprint = fingerprint("create", req.batchKey(), req.productCode(), req.batchNo(),
                req.producedAt().toString(), String.valueOf(shelfLifeMinutes), String.join(SEP, items));
        return executeIdempotent(CMD_CREATE, req.commandKey(), fingerprint, () -> {
            // 并发同键创建：竞争失败方重试时赢家的 batch 已提交，连同其 command_log 一起可见，
            // 此时按同键同参重放首次结果；只有 batch 存在但无同键命令时才是真正的 batchKey 冲突。
            var existingBatch = repo.findBatch(req.batchKey());
            if (existingBatch.isPresent()) {
                StoredResponse replay = replayIfSameCommand(CMD_CREATE, req.commandKey(), fingerprint);
                if (replay != null) {
                    return replay;
                }
                throw ApiException.conflict("batchKey 已存在: " + req.batchKey());
            }
            String now = now();
            String baseExpiresAt = req.producedAt()
                    .plus(Duration.ofMinutes(shelfLifeMinutes)).toString();
            repo.insertBatch(new BatchRepository.BatchRow(0L, req.batchKey(), req.productCode(),
                    req.batchNo(), req.producedAt().toString(), BatchStatus.QUARANTINED.name(), now,
                    shelfLifeMinutes, baseExpiresAt, baseExpiresAt));
            for (int i = 0; i < items.size(); i++) {
                repo.insertRequiredTest(req.batchKey(), items.get(i), i + 1);
            }
            BatchResponse body = new BatchResponse(req.batchKey(), req.productCode(), req.batchNo(),
                    req.producedAt(), BatchStatus.QUARANTINED, items, Instant.parse(now),
                    shelfLifeMinutes, Instant.parse(baseExpiresAt), Instant.parse(baseExpiresAt),
                    !clock.now().isBefore(Instant.parse(baseExpiresAt)));
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
            assertNotExpired(batch);
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
     * 当前可用批次：排除已召回（RECALLED）、已拆分（SPLIT）批次、已到期批次，
     * 以及任一祖先被召回的后代批次；到期与后代状态均不改写。
     */
    public List<BatchResponse> listAvailable() {
        Map<String, String> parentOf = childToParent();
        Set<String> recalled = new HashSet<>(repo.findRecalledKeys());
        Instant now = clock.now();
        return repo.findAvailableBatches().stream()
                .filter(b -> !BatchStatus.SPLIT.name().equals(b.status()))
                .filter(b -> !isExpired(b, now))
                .filter(b -> recalledAncestor(b.batchKey(), parentOf, recalled).isEmpty())
                .map(b -> toBatchResponse(b, now))
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
        List<ExtensionResponse> extensions = repo.findExtensions(batchKey).stream()
                .map(this::toExtensionResponse)
                .toList();
        return new BatchHistoryResponse(toBatchResponse(batch, clock.now()), tests, approvals, recall,
                extensions);
    }

    /**
     * 提交复检延期：目标批次必须为已双批准放行（RELEASED）、通过全部必做检验、无召回祖先；
     * 复检结论必须合格，复检人与该批两名原批准人都不同；同批累计顺延分钟不超过保质分钟两倍、
     * 最多三次。提交仅生成待确认记录，不改变有效期与可用性。
     */
    public StoredResponse submitExtension(String batchKey, String actorId, ExtensionSubmitRequest req) {
        if (actorId == null || actorId.isBlank()) {
            throw ApiException.badRequest("X-Actor-Id 不能为空");
        }
        String reviewer = actorId.trim();
        String conclusion = req.recheckConclusion().trim();
        int extendMinutes = req.extendMinutes();
        String fingerprint = fingerprint("ext-submit", batchKey, req.extensionKey(), conclusion,
                String.valueOf(extendMinutes), reviewer);
        return executeIdempotent(CMD_EXT_SUBMIT, req.commandKey(), fingerprint, () -> {
            BatchRepository.BatchRow batch = repo.findBatchForUpdate(batchKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
            // 锁等待期间同键请求可能已提交：重放首次结果而非因 extensionKey 已存在误判冲突
            var logged = loggedResponse(CMD_EXT_SUBMIT, req.commandKey(), fingerprint);
            if (logged.isPresent()) {
                return logged.get();
            }
            assertExtensionPrerequisites(batch, reviewer);
            if (repo.findExtension(req.extensionKey()).isPresent()) {
                throw ApiException.conflict("extensionKey 已存在: " + req.extensionKey());
            }
            if (!QUALIFIED.equals(conclusion)) {
                throw ApiException.unprocessable("复检结论必须为合格才能延期: " + conclusion);
            }
            List<BatchRepository.ExtensionRow> confirmed = repo.findConfirmedExtensions(batchKey);
            assertExtensionLimits(batch, confirmed, extendMinutes);

            int seq = repo.findExtensions(batchKey).size() + 1;
            String now = now();
            repo.insertExtension(new BatchRepository.ExtensionRow(0L, req.extensionKey(), batchKey,
                    req.commandKey(), null, conclusion, extendMinutes, reviewer, null, seq,
                    ExtensionStatus.SUBMITTED.name(), now, null));
            return new StoredResponse(201, toJson(new ExtensionResponse(req.extensionKey(), batchKey,
                    conclusion, extendMinutes, reviewer, null, seq,
                    ExtensionStatus.SUBMITTED.name(), Instant.parse(now), null)));
        });
    }

    /**
     * 确认延期生效：确认人必须不同于复检人；在同一事务内把待确认记录置为 CONFIRMED、
     * 将有效期整体顺延。提交后若出现召回祖先或其他前置条件失效，确认 422 且一切不变。
     */
    public StoredResponse confirmExtension(String extensionKey, String actorId, String roleHeader,
                                           ExtensionConfirmRequest req) {
        if (actorId == null || actorId.isBlank()) {
            throw ApiException.badRequest("X-Actor-Id 不能为空");
        }
        ApprovalRole role = parseRole(roleHeader);
        String confirmer = actorId.trim();
        String fingerprint = fingerprint("ext-confirm", extensionKey, confirmer, role.name());
        return executeIdempotent(CMD_EXT_CONFIRM, req.commandKey(), fingerprint, () -> {
            BatchRepository.ExtensionRow extension = repo.findExtension(extensionKey)
                    .orElseThrow(() -> ApiException.notFound("延期申请不存在: " + extensionKey));
            String extensionBatchKey = extension.batchKey();
            // 锁定目标批次行，与该批批准/拆分/召回/延期按提交顺序串行
            BatchRepository.BatchRow batch = repo.findBatchForUpdate(extensionBatchKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + extensionBatchKey));
            // 锁等待期间同键确认可能已提交：重放首次结果而非误判为已确认冲突
            var loggedAfterLock = loggedResponse(CMD_EXT_CONFIRM, req.commandKey(), fingerprint);
            if (loggedAfterLock.isPresent()) {
                return loggedAfterLock.get();
            }
            // 锁后重查延期记录：等待期间可能已被并发确认
            extension = repo.findExtension(extensionKey)
                    .orElseThrow(() -> ApiException.notFound("延期申请不存在: " + extensionKey));
            if (ExtensionStatus.CONFIRMED.name().equals(extension.status())) {
                throw ApiException.conflict("该延期已确认生效: " + extensionKey);
            }
            if (confirmer.equals(extension.reviewerId())) {
                throw ApiException.unprocessable("确认人必须不同于复检人: " + confirmer);
            }
            assertExtensionPrerequisites(batch, extension.reviewerId());
            List<BatchRepository.ExtensionRow> confirmed = repo.findConfirmedExtensions(batch.batchKey());
            assertExtensionLimits(batch, confirmed, extension.extendMinutes());

            String now = now();
            int updated = repo.confirmExtension(extensionKey, req.commandKey(), confirmer, now);
            if (updated == 0) {
                // 并发确认抢先提交：按提交顺序裁决，本请求失败且不改变任何状态
                throw ApiException.conflict("该延期已被并发确认: " + extensionKey);
            }
            String newExpiresAt = Instant.parse(batch.expiresAt())
                    .plus(Duration.ofMinutes(extension.extendMinutes())).toString();
            repo.updateExpiresAt(batch.batchKey(), newExpiresAt);
            return new StoredResponse(201, toJson(new ExtensionResponse(extensionKey, batch.batchKey(),
                    extension.recheckConclusion(), extension.extendMinutes(), extension.reviewerId(),
                    confirmer, extension.seq(), ExtensionStatus.CONFIRMED.name(),
                    Instant.parse(extension.submittedAt()), Instant.parse(now))));
        });
    }

    /**
     * 复检延期公共前置：批次必须仍为 RELEASED、通过全部必做检验、无召回祖先，
     * 且已存在两名不同的原批准人，复检人与二者都不同。
     */
    private void assertExtensionPrerequisites(BatchRepository.BatchRow batch, String reviewer) {
        String batchKey = batch.batchKey();
        assertNoRecalledAncestor(batchKey);
        if (!BatchStatus.RELEASED.name().equals(batch.status())) {
            throw ApiException.unprocessable(
                    "批次状态 " + batch.status() + " 不允许复检延期，仅 RELEASED 批次可延期");
        }
        List<String> required = repo.findRequiredTests(batchKey);
        if (!allRequiredPassed(batchKey, required)) {
            throw ApiException.unprocessable("批次尚未通过全部必做检验，不能复检延期");
        }
        List<BatchRepository.ApprovalRow> approvals = repo.findApprovals(batchKey);
        if (approvals.size() < 2) {
            throw ApiException.unprocessable("批次未经两名批准人完成放行，不能复检延期");
        }
        for (BatchRepository.ApprovalRow approval : approvals) {
            if (approval.actorId().equals(reviewer)) {
                throw ApiException.unprocessable(
                        "复检人不得与该批次原批准人相同: " + reviewer);
            }
        }
    }

    /**
     * 延期上限：同批最多三次，且累计顺延分钟不超过原保质分钟两倍；超限 422。
     */
    private void assertExtensionLimits(BatchRepository.BatchRow batch,
                                       List<BatchRepository.ExtensionRow> confirmed,
                                       int extendMinutes) {
        if (confirmed.size() >= MAX_EXTENSION_COUNT) {
            throw ApiException.unprocessable(
                    "同一批次最多延期 " + MAX_EXTENSION_COUNT + " 次");
        }
        int used = confirmed.stream().mapToInt(BatchRepository.ExtensionRow::extendMinutes).sum();
        int cap = batch.shelfLifeMinutes() * 2;
        if ((long) used + extendMinutes > cap) {
            throw ApiException.unprocessable(
                    "累计顺延分钟不得超过原保质分钟两倍（" + cap + " 分钟），已顺延 " + used + " 分钟");
        }
    }

    /**
     * 有效期视图：当前有效期、到期标识与相对当前服务端时钟的剩余分钟（到期后为 0）。
     */
    public ShelfLifeResponse shelfLife(String batchKey) {
        BatchRepository.BatchRow batch = repo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        Instant now = clock.now();
        List<BatchRepository.ExtensionRow> confirmed = repo.findConfirmedExtensions(batchKey);
        int extendedMinutes = confirmed.stream().mapToInt(BatchRepository.ExtensionRow::extendMinutes).sum();
        Instant expiresAt = Instant.parse(batch.expiresAt());
        long remaining = Math.max(0L, Duration.between(now, expiresAt).toMinutes());
        return new ShelfLifeResponse(batchKey, batch.shelfLifeMinutes(),
                Instant.parse(batch.producedAt()), Instant.parse(batch.baseExpiresAt()), expiresAt,
                now, isExpired(batch, now), remaining, confirmed.size(), extendedMinutes);
    }

    /**
     * 某批次的延期历史，按延期序号稳定排序（含待确认与已生效记录）。
     */
    public List<ExtensionResponse> extensionHistory(String batchKey) {
        repo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        return repo.findExtensions(batchKey).stream().map(this::toExtensionResponse).toList();
    }

    /**
     * 到期批次清单：当前服务端时钟下 now &gt;= expiresAt 的全部批次，按 id 稳定排序；
     * 到期不改写批次状态。
     */
    public List<ShelfLifeResponse> listExpired() {
        Instant now = clock.now();
        return repo.findAllBatches().stream()
                .filter(b -> isExpired(b, now))
                .map(b -> shelfLife(b.batchKey()))
                .toList();
    }

    private ExtensionResponse toExtensionResponse(BatchRepository.ExtensionRow row) {
        return new ExtensionResponse(row.extensionKey(), row.batchKey(), row.recheckConclusion(),
                row.extendMinutes(), row.reviewerId(), row.confirmerId(), row.seq(), row.status(),
                Instant.parse(row.submittedAt()),
                row.confirmedAt() == null ? null : Instant.parse(row.confirmedAt()));
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

    /**
     * 若同键命令已成功执行则重放其快照（指纹不同抛 409）；未执行过返回 null。
     * 供竞争失败重试时，在已被赢家创建的业务实体上重放首次结果。
     */
    private StoredResponse replayIfSameCommand(String type, String commandKey, String fingerprint) {
        return loggedResponse(type, commandKey, fingerprint).orElse(null);
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

    private BatchResponse toBatchResponse(BatchRepository.BatchRow row, Instant now) {
        return new BatchResponse(row.batchKey(), row.productCode(), row.batchNo(),
                Instant.parse(row.producedAt()), BatchStatus.valueOf(row.status()),
                repo.findRequiredTests(row.batchKey()), Instant.parse(row.createdAt()),
                row.shelfLifeMinutes(), Instant.parse(row.baseExpiresAt()),
                Instant.parse(row.expiresAt()), isExpired(row, now));
    }

    /**
     * 当前时刻达到（等于或晚于）有效期即视为到期；只影响可用性判定与查询标识，不改写状态。
     */
    private boolean isExpired(BatchRepository.BatchRow row, Instant now) {
        return !now.isBefore(Instant.parse(row.expiresAt()));
    }

    private void assertNotExpired(BatchRepository.BatchRow row) {
        if (isExpired(row, clock.now())) {
            throw ApiException.unprocessable("批次已过有效期，禁止批准和拆分: " + row.batchKey());
        }
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
        return clock.now().toString();
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
            assertNotExpired(parent);
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
            List<String> required = repo.findRequiredTests(parentKey);
            Instant nowInstant = clock.now();
            // 子批继承父批保质分钟，按自身生产时间（继承父批生产时间）重新计算初始有效期
            String childBaseExpiresAt = Instant.parse(parent.producedAt())
                    .plus(Duration.ofMinutes(parent.shelfLifeMinutes())).toString();
            List<SplitResponse.SplitChild> childBodies = new ArrayList<>(children.size());
            for (int i = 0; i < children.size(); i++) {
                SplitRequest.ChildSpec spec = children.get(i);
                repo.insertBatch(new BatchRepository.BatchRow(0L, spec.batchKey(), parent.productCode(),
                        spec.batchNo(), parent.producedAt(), BatchStatus.QUARANTINED.name(), now,
                        parent.shelfLifeMinutes(), childBaseExpiresAt, childBaseExpiresAt));
                for (int j = 0; j < required.size(); j++) {
                    repo.insertRequiredTest(spec.batchKey(), required.get(j), j + 1);
                }
                repo.insertLineage(new BatchRepository.LineageRow(0L, parentKey, spec.batchKey(),
                        i + 1, now));
                childBodies.add(new SplitResponse.SplitChild(spec.batchKey(), spec.batchNo(),
                        parent.productCode(), Instant.parse(parent.producedAt()),
                        BatchStatus.QUARANTINED, required, Instant.parse(now),
                        parent.shelfLifeMinutes(), Instant.parse(childBaseExpiresAt),
                        Instant.parse(childBaseExpiresAt),
                        !nowInstant.isBefore(Instant.parse(childBaseExpiresAt))));
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
}
