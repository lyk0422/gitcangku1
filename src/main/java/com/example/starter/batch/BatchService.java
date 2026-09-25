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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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
    private static final String CMD_REGISTER_EXCURSION = "REGISTER_EXCURSION";
    private static final String CMD_ADJUDICATE = "ADJUDICATE_EXCURSION";
    private static final String CMD_CONFIRM_MINOR = "CONFIRM_MINOR";

    /**
     * 批次储运温度规格缺省值（摄氏度，闭区间 2～8℃）。
     */
    private static final java.math.BigDecimal DEFAULT_MIN_STORAGE_TEMP = new java.math.BigDecimal("2.00");
    private static final java.math.BigDecimal DEFAULT_MAX_STORAGE_TEMP = new java.math.BigDecimal("8.00");

    /**
     * 指纹拼接分隔符（NUL）：业务参数不可能包含该字符，避免拼接碰撞。
     */
    private static final String SEP = "\u0000";

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
        java.math.BigDecimal minSpec = req.minStorageTempC() == null
                ? DEFAULT_MIN_STORAGE_TEMP : req.minStorageTempC();
        java.math.BigDecimal maxSpec = req.maxStorageTempC() == null
                ? DEFAULT_MAX_STORAGE_TEMP : req.maxStorageTempC();
        if ((req.minStorageTempC() == null) != (req.maxStorageTempC() == null)) {
            throw ApiException.badRequest("储运温度规格上下限必须同时提供或同时缺省（缺省 2～8℃）");
        }
        if (minSpec.compareTo(maxSpec) > 0) {
            throw ApiException.badRequest("储运温度规格下限不得大于上限");
        }
        String fingerprint = fingerprint("create", req.batchKey(), req.productCode(), req.batchNo(),
                req.producedAt().toString(), String.join(SEP, items),
                minSpec.stripTrailingZeros().toPlainString(),
                maxSpec.stripTrailingZeros().toPlainString());
        return executeIdempotent(CMD_CREATE, req.commandKey(), fingerprint, null, () -> {
            repo.findBatch(req.batchKey()).ifPresent(b -> {
                throw ApiException.conflict("batchKey 已存在: " + req.batchKey());
            });
            String now = now();
            repo.insertBatch(new BatchRepository.BatchRow(0L, req.batchKey(), req.productCode(),
                    req.batchNo(), req.producedAt().toString(), BatchStatus.QUARANTINED.name(), now,
                    minSpec, maxSpec, 0L));
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
        return executeIdempotent(CMD_TEST, req.commandKey(), fingerprint, null, () -> {
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
                    || status == BatchStatus.PENDING_DISPOSITION || status == BatchStatus.REWORKED
                    || status == BatchStatus.DISPOSED) {
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
        return executeIdempotent(CMD_APPROVE, req.commandKey(), fingerprint, null, () -> {
            BatchRepository.BatchRow batch = repo.findBatchForUpdate(batchKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
            BatchStatus status = BatchStatus.valueOf(batch.status());
            assertNoRecalledAncestor(batchKey);
            // 放行持续门禁最优先：任一未裁决 MAJOR/未确认 MINOR 偏差返回 422 并列出偏差标识，
            // 无论批次处于 QUARANTINED、PENDING_RELEASE、RELEASE_REVIEW 还是 PENDING_DISPOSITION
            assertNoExcursionGate(batchKey);
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
        return executeIdempotent(CMD_RECALL, req.commandKey(), fingerprint, null, () -> {
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
                .filter(b -> !BatchStatus.DISPOSED.name().equals(b.status()))
                .filter(b -> !BatchStatus.REWORKED.name().equals(b.status()))
                .filter(b -> !BatchStatus.PENDING_DISPOSITION.name().equals(b.status()))
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
     *
     * @param commandBatchVersion 命令归属批次的当前版本号；非批次级命令传 null。
     *                            偏差类命令指纹已含版本，快照同时记录版本以便重放复核。
     */
    private StoredResponse executeIdempotent(String type, String commandKey, String fingerprint,
                                             Long commandBatchVersion,
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
                            response.status(), response.body(), commandBatchVersion), now());
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
        return Instant.now().toString();
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
        return executeIdempotent(CMD_SPLIT, req.commandKey(), fingerprint, null, () -> {
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
            List<String> required = repo.findRequiredTests(parentKey);
            List<SplitResponse.SplitChild> childBodies = new ArrayList<>(children.size());
            for (int i = 0; i < children.size(); i++) {
                SplitRequest.ChildSpec spec = children.get(i);
                repo.insertBatch(new BatchRepository.BatchRow(0L, spec.batchKey(), parent.productCode(),
                        spec.batchNo(), parent.producedAt(), BatchStatus.QUARANTINED.name(), now,
                        parent.minStorageTempC(), parent.maxStorageTempC(), 0L));
                for (int j = 0; j < required.size(); j++) {
                    repo.insertRequiredTest(spec.batchKey(), required.get(j), j + 1);
                }
                repo.insertLineage(new BatchRepository.LineageRow(0L, parentKey, spec.batchKey(),
                        "SPLIT", i + 1, now));
                childBodies.add(new SplitResponse.SplitChild(spec.batchKey(), spec.batchNo(),
                        parent.productCode(), Instant.parse(parent.producedAt()),
                        BatchStatus.QUARANTINED, required, Instant.parse(now)));
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
     * 若任一级祖先已被召回（RECALLED）或拒收处置（DISPOSED）则抛 422：
     * 后代批次禁止新增检验、批准和拆分。REJECT 裁决按召回口径拦截。
     */
    private void assertNoRecalledAncestor(String batchKey) {
        Optional<String> recalled = recalledAncestor(batchKey, childToParent(),
                new HashSet<>(repo.findRecalledKeys()));
        if (recalled.isPresent()) {
            throw ApiException.unprocessable(
                    "祖先批次 " + recalled.get() + " 已召回或拒收处置，禁止新增检验、批准和拆分");
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

    // ==================== 储运偏差 ====================

    /**
     * 登记一条或多条储运偏差：先校验完整最终区间集合（下限不大于上限、同批次区间不重叠）
     * 与批次规格分级，任一非法整批回滚。并发按批次行锁 + 事务提交顺序裁决。
     */
    public StoredResponse registerExcursions(String batchKey,
                                             com.example.starter.batch.dto.RegisterExcursionsRequest req) {
        List<com.example.starter.batch.dto.RegisterExcursionsRequest.ExcursionInput> inputs = req.excursions();
        // 请求内基础校验：键不重复、温度下限不大于上限、区间左闭右开且起止严格递增
        Set<String> requestKeys = new HashSet<>();
        for (var in : inputs) {
            if (!requestKeys.add(in.excursionKey())) {
                throw ApiException.badRequest("excursionKey 在请求内重复: " + in.excursionKey());
            }
            if (in.minTempC().compareTo(in.maxTempC()) > 0) {
                throw ApiException.badRequest(
                        "偏差实测温度下限不得大于上限: " + in.excursionKey());
            }
            if (!in.startUtc().isBefore(in.endUtc())) {
                throw ApiException.badRequest(
                        "偏差区间起止必须满足 startUtc < endUtc（左闭右开）: " + in.excursionKey());
            }
        }
        return executeLockedBatchCommand(CMD_REGISTER_EXCURSION, req.commandKey(), batch -> {
            // 锁内先查命令快照：并发同键请求在锁等待期间可能已由对方提交
            StoredResponse replay = replayExcursionCommandIfSameParams(
                    CMD_REGISTER_EXCURSION, req.commandKey(), batchKey, inputs);
            if (replay != null) {
                return replay;
            }
            BatchStatus state = BatchStatus.valueOf(batch.status());
            // SPLIT 父批与召回口径一致，仍可登记偏差并裁决（REJECT 时拦截其全部后代）；
            // 检验拒绝/召回/返工/处置等终态不允许再登记
            if (state == BatchStatus.REJECTED || state == BatchStatus.RECALLED
                    || state == BatchStatus.REWORKED || state == BatchStatus.DISPOSED) {
                throw ApiException.conflict("批次状态 " + state + " 不允许登记储运偏差");
            }
            List<BatchRepository.ExcursionRow> existing = repo.findExcursions(batchKey);
            for (BatchRepository.ExcursionRow row : existing) {
                if (requestKeys.contains(row.excursionKey())) {
                    throw ApiException.conflict("excursionKey 已存在: " + row.excursionKey());
                }
            }
            // 完整最终区间集合（既有 ∪ 本次）重叠校验：半开区间，端点相接不视为重叠
            List<Interval> all = new ArrayList<>(existing.size() + inputs.size());
            for (BatchRepository.ExcursionRow row : existing) {
                all.add(new Interval(row.excursionKey(), Instant.parse(row.startUtc()),
                        Instant.parse(row.endUtc())));
            }
            for (var in : inputs) {
                all.add(new Interval(in.excursionKey(), in.startUtc(), in.endUtc()));
            }
            all.sort(Comparator.comparing(Interval::start).thenComparing(Interval::key));
            for (int i = 1; i < all.size(); i++) {
                Interval prev = all.get(i - 1);
                Interval cur = all.get(i);
                if (prev.end().isAfter(cur.start())) {
                    throw ApiException.unprocessable("偏差区间重叠: " + prev.key() + " 与 " + cur.key());
                }
            }

            long registeredVersion = batch.version();
            String now = now();
            List<com.example.starter.batch.dto.ExcursionResponse> bodies = new ArrayList<>(inputs.size());
            List<com.example.starter.batch.dto.RegisterExcursionsRequest.ExcursionInput> majorInputs =
                    new ArrayList<>();
            // 按请求顺序写入；严重级别只取决于批次温度规格与实测温度边界
            for (var in : inputs) {
                boolean major = in.minTempC().compareTo(batch.minStorageTempC()) < 0
                        || in.maxTempC().compareTo(batch.maxStorageTempC()) > 0;
                if (major) {
                    majorInputs.add(in);
                }
                BatchRepository.ExcursionRow row = new BatchRepository.ExcursionRow(0L, batchKey,
                        in.excursionKey(), in.startUtc().toString(), in.endUtc().toString(),
                        in.minTempC(), in.maxTempC(),
                        major ? ExcursionSeverity.MAJOR.name() : ExcursionSeverity.MINOR.name(),
                        ExcursionStatus.OPEN.name(), registeredVersion, null, null, now);
                repo.insertExcursion(row);
                bodies.add(toExcursionResponse(row));
            }
            long newVersion = repo.incrementVersion(batchKey);
            // 已放行批次新增 MAJOR：立即转待处置并写风险记录（每条 MAJOR 一条），历史放行不删除
            if (!majorInputs.isEmpty() && state == BatchStatus.RELEASED) {
                repo.updateStatus(batchKey, BatchStatus.PENDING_DISPOSITION.name());
                for (var in : majorInputs) {
                    repo.insertDispositionRisk(new BatchRepository.DispositionRiskRow(0L, batchKey,
                            in.excursionKey(), "POST_RELEASE_MAJOR",
                            "已放行批次登记未裁决 MAJOR 储运偏差，立即转为待处置", now));
                }
            }
            com.example.starter.batch.dto.RegisterExcursionsResponse body =
                    new com.example.starter.batch.dto.RegisterExcursionsResponse(
                            batchKey, newVersion, bodies);
            StoredResponse response = new StoredResponse(201, toJson(body));
            // 指纹含操作类型、批次版本、规范化区间与温度；同键同参重放首次结果，失败不占键
            repo.insertCommand(new BatchRepository.CommandRow(CMD_REGISTER_EXCURSION, req.commandKey(),
                    excursionFingerprint(CMD_REGISTER_EXCURSION, batchKey, registeredVersion, inputs),
                    201, response.body(), registeredVersion), now);
            return response;
        }, batchKey);
    }

    /**
     * 查询批次全部储运偏差区间（按登记顺序）。
     */
    public List<com.example.starter.batch.dto.ExcursionResponse> listExcursions(String batchKey) {
        repo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        return repo.findExcursions(batchKey).stream().map(this::toExcursionResponse).toList();
    }

    /**
     * 查询放行持续门禁：未裁决 MAJOR 与未确认 MINOR 偏差标识；两者皆空表示偏差门禁已解除。
     */
    public com.example.starter.batch.dto.ReleaseBlockResponse getReleaseBlock(String batchKey) {
        repo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        List<String> majorKeys = repo.findOpenMajorExcursions(batchKey).stream()
                .map(BatchRepository.ExcursionRow::excursionKey).toList();
        List<String> minorKeys = repo.findOpenMinorExcursions(batchKey).stream()
                .map(BatchRepository.ExcursionRow::excursionKey).toList();
        return new com.example.starter.batch.dto.ReleaseBlockResponse(batchKey,
                !majorKeys.isEmpty() || !minorKeys.isEmpty(), majorKeys, minorKeys);
    }

    /**
     * 查询批次全部 MAJOR 偏差裁决不可变快照。
     */
    public List<com.example.starter.batch.dto.AdjudicationResponse> listAdjudications(String batchKey) {
        repo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        return repo.findAdjudications(batchKey).stream().map(this::toAdjudicationResponse).toList();
    }

    /**
     * 查询批次处置风险记录（只增不改）。
     */
    public List<com.example.starter.batch.dto.DispositionRiskResponse> listDispositionRisks(String batchKey) {
        repo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        return repo.findDispositionRisks(batchKey).stream()
                .map(r -> new com.example.starter.batch.dto.DispositionRiskResponse(r.batchKey(),
                        r.excursionKey(), r.riskType(), r.detail(), Instant.parse(r.createdAt())))
                .toList();
    }

    /**
     * 放行持续门禁断言：任一未裁决 MAJOR 或未质控确认 MINOR 偏差均阻断放行（422，列出偏差标识）。
     * 在每一笔批准落定前调用，保证已进入 RELEASE_REVIEW 后新增偏差同样持续阻断。
     */
    private void assertNoExcursionGate(String batchKey) {
        List<String> majorKeys = repo.findOpenMajorExcursions(batchKey).stream()
                .map(BatchRepository.ExcursionRow::excursionKey).toList();
        List<String> minorKeys = repo.findOpenMinorExcursions(batchKey).stream()
                .map(BatchRepository.ExcursionRow::excursionKey).toList();
        if (!majorKeys.isEmpty() || !minorKeys.isEmpty()) {
            throw new ReleaseBlockedException(majorKeys, minorKeys);
        }
    }

    /**
     * 锁内重放判定：同类型同键命令若已存在，则以响应快照重建业务参数指纹比对——
     * 一致则重放首次结果，参数变化返回 409。快照指纹中的批次版本不参与重放比对，
     * 因为首次成功后版本已经递增，但同参重放仍须返回首次结果。
     */
    private StoredResponse replayExcursionCommandIfSameParams(
            String type, String commandKey, String batchKey,
            List<com.example.starter.batch.dto.RegisterExcursionsRequest.ExcursionInput> inputs) {
        var existing = repo.findCommand(type, commandKey);
        if (existing.isEmpty()) {
            return null;
        }
        BatchRepository.CommandRow row = existing.get();
        try {
            com.example.starter.batch.dto.RegisterExcursionsResponse snapshot = objectMapper.readValue(
                    row.responseBody(),
                    com.example.starter.batch.dto.RegisterExcursionsResponse.class);
            List<String> snapshotParts = new ArrayList<>();
            snapshotParts.add(batchKey);
            for (com.example.starter.batch.dto.ExcursionResponse e : snapshot.excursions()) {
                snapshotParts.add(e.excursionKey());
                snapshotParts.add(e.startUtc().toString());
                snapshotParts.add(e.endUtc().toString());
                snapshotParts.add(normalizeTemp(e.minTempC()));
                snapshotParts.add(normalizeTemp(e.maxTempC()));
            }
            List<String> requestParts = new ArrayList<>();
            requestParts.add(batchKey);
            for (var in : inputs) {
                requestParts.add(in.excursionKey());
                requestParts.add(in.startUtc().toString());
                requestParts.add(in.endUtc().toString());
                requestParts.add(normalizeTemp(in.minTempC()));
                requestParts.add(normalizeTemp(in.maxTempC()));
            }
            if (!snapshot.batchKey().equals(batchKey)
                    || !fingerprint(snapshotParts.toArray(new String[0]))
                    .equals(fingerprint(requestParts.toArray(new String[0])))) {
                throw ApiException.conflict("commandKey 已以不同参数使用: " + commandKey);
            }
            return new StoredResponse(row.responseStatus(), row.responseBody());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw ApiException.conflict("commandKey 已以不同参数使用: " + commandKey);
        }
    }

    /**
     * 偏差命令指纹：操作类型 + 批次版本 + 规范化区间与温度（温度去尾零，UTC instant 规范化）。
     */
    private String excursionFingerprint(
            String type, String batchKey, long batchVersion,
            List<com.example.starter.batch.dto.RegisterExcursionsRequest.ExcursionInput> inputs) {
        List<String> parts = new ArrayList<>();
        parts.add(type);
        parts.add(batchKey);
        parts.add(Long.toString(batchVersion));
        for (var in : inputs) {
            parts.add(in.excursionKey());
            parts.add(in.startUtc().toString());
            parts.add(in.endUtc().toString());
            parts.add(normalizeTemp(in.minTempC()));
            parts.add(normalizeTemp(in.maxTempC()));
        }
        return fingerprint(parts.toArray(new String[0]));
    }

    private String normalizeTemp(java.math.BigDecimal temp) {
        return temp.stripTrailingZeros().toPlainString();
    }

    private com.example.starter.batch.dto.ExcursionResponse toExcursionResponse(
            BatchRepository.ExcursionRow row) {
        return new com.example.starter.batch.dto.ExcursionResponse(row.batchKey(), row.excursionKey(),
                Instant.parse(row.startUtc()), Instant.parse(row.endUtc()),
                row.minTempC(), row.maxTempC(),
                ExcursionSeverity.valueOf(row.severity()), ExcursionStatus.valueOf(row.status()),
                row.batchVersion(), row.confirmedBy(),
                row.confirmedAt() == null ? null : Instant.parse(row.confirmedAt()),
                Instant.parse(row.createdAt()));
    }

    private com.example.starter.batch.dto.AdjudicationResponse toAdjudicationResponse(
            BatchRepository.AdjudicationRow row) {
        return new com.example.starter.batch.dto.AdjudicationResponse(row.batchKey(),
                row.excursionKey(), ExcursionDecision.valueOf(row.decision()), row.adjudicator(),
                row.reworkBatchKey(), row.snapshotJson(), Instant.parse(row.createdAt()));
    }

    /**
     * 裁决 MAJOR 偏差：只能 REWORK 或 REJECT，裁决写入不可变快照，按批次行锁 + 提交顺序并发裁决。
     * REWORK：原批次置 REWORKED（仍有其他未裁决 MAJOR 时停留待处置），沿返工链创建返工子批；
     * REJECT：本批次置 DISPOSED 并锁定全部后代，按召回口径拦截。
     */
    public StoredResponse adjudicateExcursion(String batchKey, String excursionKey, String actorId,
                                              String roleHeader,
                                              com.example.starter.batch.dto.AdjudicateExcursionRequest req) {
        if (actorId == null || actorId.isBlank()) {
            throw ApiException.badRequest("X-Actor-Id 不能为空");
        }
        ApprovalRole role = parseRole(roleHeader);
        if (role != ApprovalRole.QUALITY) {
            throw ApiException.badRequest("MAJOR 偏差裁决仅 QUALITY 角色可执行");
        }
        String actor = actorId.trim();
        return executeLockedBatchCommand(CMD_ADJUDICATE, req.commandKey(), batch -> {
            BatchRepository.ExcursionRow excursion = repo.findExcursion(batchKey, excursionKey)
                    .orElseThrow(() -> ApiException.notFound(
                            "偏差不存在: " + batchKey + "/" + excursionKey));
            // 指纹含操作类型、偏差登记时固化的批次版本（不可变）、规范化区间温度与裁决结论；
            // 实时批次版本在裁决后递增，不参与重放比对，保证同键同参始终重放首次结果
            String fingerprint = fingerprint(CMD_ADJUDICATE, batchKey,
                    Long.toString(excursion.batchVersion()), excursionKey,
                    excursion.startUtc(), excursion.endUtc(),
                    normalizeTemp(excursion.minTempC()), normalizeTemp(excursion.maxTempC()),
                    req.decision().name(),
                    req.reworkBatchKey() == null ? "" : req.reworkBatchKey().trim(),
                    req.reworkBatchNo() == null ? "" : req.reworkBatchNo().trim(), actor);
            // 重放优先于状态守卫：裁决成功后批次可能已进入 REWORKED/DISPOSED 终态，
            // 同键同参重放仍须返回首次结果
            StoredResponse replay = replayIfSameParams(CMD_ADJUDICATE, req.commandKey(), fingerprint);
            if (replay != null) {
                return replay;
            }
            BatchStatus state = BatchStatus.valueOf(batch.status());
            // SPLIT 父批与召回口径一致，仍可裁决 MAJOR（REJECT 拦截全部后代）
            if (state == BatchStatus.REJECTED || state == BatchStatus.RECALLED
                    || state == BatchStatus.REWORKED || state == BatchStatus.DISPOSED) {
                throw ApiException.conflict("批次状态 " + state + " 不允许裁决偏差");
            }
            if (!ExcursionSeverity.MAJOR.name().equals(excursion.severity())) {
                throw ApiException.unprocessable("MINOR 偏差不能裁决，仅可质控确认: " + excursionKey);
            }
            if (ExcursionStatus.ADJUDICATED.name().equals(excursion.status())) {
                throw ApiException.conflict("MAJOR 偏差已裁决，裁决快照不可变: " + excursionKey);
            }
            String now = now();
            String reworkBatchKey = null;
            if (req.decision() == ExcursionDecision.REWORK) {
                if (req.reworkBatchKey() == null || req.reworkBatchKey().isBlank()
                        || req.reworkBatchNo() == null || req.reworkBatchNo().isBlank()) {
                    throw ApiException.badRequest("REWORK 裁决必须提供 reworkBatchKey 与 reworkBatchNo");
                }
                reworkBatchKey = req.reworkBatchKey().trim();
                String reworkBatchNo = req.reworkBatchNo().trim();
                if (repo.findBatch(reworkBatchKey).isPresent()) {
                    throw ApiException.conflict("返工子批 batchKey 已存在: " + reworkBatchKey);
                }
                if (repo.findParentKey(reworkBatchKey).isPresent()) {
                    throw ApiException.conflict("返工子批 batchKey 已存在血缘: " + reworkBatchKey);
                }
                // 返工子批继承产品、生产时间、储运规格与必做检验项，初始 QUARANTINED，沿返工链重新检验放行
                List<String> required = repo.findRequiredTests(batchKey);
                repo.insertBatch(new BatchRepository.BatchRow(0L, reworkBatchKey, batch.productCode(),
                        reworkBatchNo, batch.producedAt(), BatchStatus.QUARANTINED.name(), now,
                        batch.minStorageTempC(), batch.maxStorageTempC(), 0L));
                for (int i = 0; i < required.size(); i++) {
                    repo.insertRequiredTest(reworkBatchKey, required.get(i), i + 1);
                }
                repo.insertLineage(new BatchRepository.LineageRow(0L, batchKey, reworkBatchKey,
                        "REWORK", 1, now));
                // 条件更新：并发同偏差裁决只有一个事务能将 OPEN 改为 ADJUDICATED
                if (repo.adjudicateMajorExcursion(batchKey, excursionKey) != 1) {
                    throw ApiException.conflict("MAJOR 偏差已被并发裁决: " + excursionKey);
                }
                repo.insertDispositionRisk(new BatchRepository.DispositionRiskRow(0L, batchKey,
                        excursionKey, "REWORK",
                        "MAJOR 偏差裁决返工，返工子批: " + reworkBatchKey, now));
                boolean otherOpenMajor = !repo.findOpenMajorExcursions(batchKey).isEmpty();
                // 仍有其他未裁决 MAJOR：已放行来源批次继续待处置；否则原批次进入返工终态
                if (!otherOpenMajor) {
                    repo.updateStatus(batchKey, BatchStatus.REWORKED.name());
                }
                long newVersion = repo.incrementVersion(batchKey);
                String snapshotJson = adjudicationSnapshotJson(excursion, req.decision(), actor,
                        reworkBatchKey, newVersion);
                repo.insertAdjudication(new BatchRepository.AdjudicationRow(0L, batchKey, excursionKey,
                        req.decision().name(), actor, role.name(), reworkBatchKey, snapshotJson, now));
                var body = new com.example.starter.batch.dto.AdjudicationResponse(batchKey, excursionKey,
                        req.decision(), actor, reworkBatchKey, snapshotJson, Instant.parse(now));
                StoredResponse response = new StoredResponse(201, toJson(body));
                repo.insertCommand(new BatchRepository.CommandRow(CMD_ADJUDICATE, req.commandKey(),
                        fingerprint, 201, response.body(), newVersion), now);
                return response;
            }
            // REJECT：锁定全部后代（按键排序确定锁顺序），本批次置 DISPOSED，后代按召回口径拦截
            List<String> descendants = descendantKeys(batchKey);
            Collections.sort(descendants);
            for (String descendantKey : descendants) {
                repo.findBatchForUpdate(descendantKey);
            }
            if (repo.adjudicateMajorExcursion(batchKey, excursionKey) != 1) {
                throw ApiException.conflict("MAJOR 偏差已被并发裁决: " + excursionKey);
            }
            repo.updateStatus(batchKey, BatchStatus.DISPOSED.name());
            long newVersion = repo.incrementVersion(batchKey);
            String snapshotJson = adjudicationSnapshotJson(excursion, req.decision(), actor,
                    null, newVersion);
            repo.insertAdjudication(new BatchRepository.AdjudicationRow(0L, batchKey, excursionKey,
                    req.decision().name(), actor, role.name(), null, snapshotJson, now));
            repo.insertDispositionRisk(new BatchRepository.DispositionRiskRow(0L, batchKey,
                    excursionKey, "REJECT_DISPOSITION",
                    "MAJOR 偏差裁决拒收，本批次及全部后代按召回口径拦截", now));
            var body = new com.example.starter.batch.dto.AdjudicationResponse(batchKey, excursionKey,
                    req.decision(), actor, null, snapshotJson, Instant.parse(now));
            StoredResponse response = new StoredResponse(201, toJson(body));
            repo.insertCommand(new BatchRepository.CommandRow(CMD_ADJUDICATE, req.commandKey(),
                    fingerprint, 201, response.body(), newVersion), now);
            return response;
        }, batchKey);
    }

    /**
     * MINOR 偏差质控确认：仅 QUALITY 角色可确认；确认后偏差门禁解除，批次版本递增。
     */
    public StoredResponse confirmMinorExcursion(String batchKey, String excursionKey, String actorId,
                                                String roleHeader,
                                                com.example.starter.batch.dto.ConfirmMinorExcursionRequest req) {
        if (actorId == null || actorId.isBlank()) {
            throw ApiException.badRequest("X-Actor-Id 不能为空");
        }
        ApprovalRole role = parseRole(roleHeader);
        if (role != ApprovalRole.QUALITY) {
            throw ApiException.badRequest("MINOR 偏差确认仅 QUALITY 角色可执行");
        }
        String actor = actorId.trim();
        return executeLockedBatchCommand(CMD_CONFIRM_MINOR, req.commandKey(), batch -> {
            BatchRepository.ExcursionRow excursion = repo.findExcursion(batchKey, excursionKey)
                    .orElseThrow(() -> ApiException.notFound(
                            "偏差不存在: " + batchKey + "/" + excursionKey));
            // 指纹采用偏差登记时固化的批次版本，重放时版本不再变化
            String fingerprint = fingerprint(CMD_CONFIRM_MINOR, batchKey,
                    Long.toString(excursion.batchVersion()), excursionKey,
                    excursion.startUtc(), excursion.endUtc(),
                    normalizeTemp(excursion.minTempC()), normalizeTemp(excursion.maxTempC()), actor);
            StoredResponse replay = replayIfSameParams(CMD_CONFIRM_MINOR, req.commandKey(), fingerprint);
            if (replay != null) {
                return replay;
            }
            if (!ExcursionSeverity.MINOR.name().equals(excursion.severity())) {
                throw ApiException.unprocessable("MAJOR 偏差必须裁决（REWORK/REJECT），不能质控确认: "
                        + excursionKey);
            }
            if (ExcursionStatus.CONFIRMED.name().equals(excursion.status())) {
                throw ApiException.conflict("MINOR 偏差已经质控确认: " + excursionKey);
            }
            String now = now();
            if (repo.confirmMinorExcursion(batchKey, excursionKey, actor, now) != 1) {
                throw ApiException.conflict("MINOR 偏差已被并发确认或裁决: " + excursionKey);
            }
            long newVersion = repo.incrementVersion(batchKey);
            BatchRepository.ExcursionRow confirmed = repo.findExcursion(batchKey, excursionKey).orElseThrow();
            com.example.starter.batch.dto.ExcursionResponse body = toExcursionResponse(confirmed);
            StoredResponse response = new StoredResponse(201, toJson(body));
            repo.insertCommand(new BatchRepository.CommandRow(CMD_CONFIRM_MINOR, req.commandKey(),
                    fingerprint, 201, response.body(), newVersion), now);
            return response;
        }, batchKey);
    }

    /**
     * 批次行锁命令模板：锁内执行业务并自行完成命令快照重放判定与写入；
     * 并发同键插入冲突时整事务回滚重试，读取对方已提交快照。
     */
    private StoredResponse executeLockedBatchCommand(String type, String commandKey,
                                                     java.util.function.Function<BatchRepository.BatchRow, StoredResponse> action,
                                                     String batchKey) {
        for (int attempt = 0; attempt < IDEMPOTENCY_MAX_ATTEMPTS; attempt++) {
            try {
                return tx.execute(status -> {
                    BatchRepository.BatchRow batch = repo.findBatchForUpdate(batchKey)
                            .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
                    return action.apply(batch);
                });
            } catch (DuplicateKeyException e) {
                // 并发同事务键或唯一约束冲突：回滚重试
            }
        }
        throw ApiException.conflict("命令并发冲突，请重试: " + commandKey);
    }

    /**
     * 锁内命令快照判定：指纹一致返回首次结果；指纹不一致 409；未占用返回 null（失败不占键）。
     */
    private StoredResponse replayIfSameParams(String type, String commandKey, String fingerprint) {
        var existing = repo.findCommand(type, commandKey);
        if (existing.isEmpty()) {
            return null;
        }
        BatchRepository.CommandRow row = existing.get();
        if (!row.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("commandKey 已以不同参数使用: " + commandKey);
        }
        return new StoredResponse(row.responseStatus(), row.responseBody());
    }

    /**
     * 裁决不可变快照 JSON：偏差区间/温度/级别/登记版本/裁决结论/裁决人/返工子批。
     */
    private String adjudicationSnapshotJson(BatchRepository.ExcursionRow excursion,
                                           ExcursionDecision decision, String adjudicator,
                                           String reworkBatchKey, long adjudicatedVersion) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("batchKey", excursion.batchKey());
        snapshot.put("excursionKey", excursion.excursionKey());
        snapshot.put("startUtc", excursion.startUtc());
        snapshot.put("endUtc", excursion.endUtc());
        snapshot.put("minTempC", excursion.minTempC().stripTrailingZeros().toPlainString());
        snapshot.put("maxTempC", excursion.maxTempC().stripTrailingZeros().toPlainString());
        snapshot.put("severity", excursion.severity());
        snapshot.put("registeredBatchVersion", excursion.batchVersion());
        snapshot.put("adjudicatedBatchVersion", adjudicatedVersion);
        snapshot.put("decision", decision.name());
        snapshot.put("adjudicator", adjudicator);
        snapshot.put("reworkBatchKey", reworkBatchKey);
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("裁决快照序列化失败", e);
        }
    }

    /**
     * 半开区间 [start, end) 内部比较模型。
     */
    private record Interval(String key, Instant start, Instant end) {
    }
}
