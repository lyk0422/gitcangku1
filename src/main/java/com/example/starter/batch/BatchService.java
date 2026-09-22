package com.example.starter.batch;

import com.example.starter.batch.dto.ApprovalResponse;
import com.example.starter.batch.dto.ApproveRequest;
import com.example.starter.batch.dto.BatchHistoryResponse;
import com.example.starter.batch.dto.BatchResponse;
import com.example.starter.batch.dto.CreateBatchRequest;
import com.example.starter.batch.dto.LineageNodeResponse;
import com.example.starter.batch.dto.LineageResponse;
import com.example.starter.batch.dto.RecallRequest;
import com.example.starter.batch.dto.RecallResponse;
import com.example.starter.batch.dto.RecalledAncestorResponse;
import com.example.starter.batch.dto.SplitChildRequest;
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
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /**
     * 指纹拼接分隔符（NUL）：业务参数不可能包含该字符，避免拼接碰撞。
     */
    private static final String SEP = "\u0000";

    private static final int IDEMPOTENCY_MAX_ATTEMPTS = 8;

    private static final long IDEMPOTENCY_RETRY_BACKOFF_MS = 25L;

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
            // 锁定批次自身及整条祖先链：祖先召回先提交时，本事务等待后按 422 失败
            List<BatchRepository.BatchRow> chain = lockAncestorChain(batchKey);
            BatchRepository.BatchRow batch = chain.get(0);

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

            for (int i = 1; i < chain.size(); i++) {
                if (BatchStatus.RECALLED.name().equals(chain.get(i).status())) {
                    throw ApiException.unprocessable(
                            "祖先批次已召回，禁止新增检验: " + chain.get(i).batchKey());
                }
            }
            BatchStatus status = BatchStatus.valueOf(batch.status());
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
            List<BatchRepository.BatchRow> chain = lockAncestorChain(batchKey);
            BatchRepository.BatchRow batch = chain.get(0);
            for (int i = 1; i < chain.size(); i++) {
                if (BatchStatus.RECALLED.name().equals(chain.get(i).status())) {
                    throw ApiException.unprocessable(
                            "祖先批次已召回，禁止新增批准: " + chain.get(i).batchKey());
                }
            }
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
     * 召回：RELEASED 或 SPLIT 父批可召回，召回后进入 RECALLED；召回原因与原历史保留。
     * 提交后其全部后代立即从可用查询排除，并被禁止新增检验、批准与拆分（后代自身记录不改写）。
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
                throw ApiException.conflict(
                        "批次状态 " + status + " 不允许召回，仅 RELEASED 或 SPLIT 可召回");
            }
            // 幂等重放可能命中：状态已 RECALLED 时由 command_log 快照返回；同一召回命令不会二次插入。
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
     * 拆分：仅 RELEASED 且无已召回祖先的批次可拆为 2～5 个全新子批；
     * 父批置 SPLIT、全部子批与血缘关系同事务提交；commandKey 幂等。
     * 子批继承产品编码、生产 UTC 时间与必做检验项，初始 QUARANTINED，不继承检验/批准记录。
     */
    public StoredResponse split(String batchKey, SplitRequest req) {
        List<SplitChildRequest> children = req.children().stream()
                .map(c -> new SplitChildRequest(c.batchKey().trim(), c.batchNo().trim()))
                .toList();
        String childSpec = children.stream()
                .map(c -> c.batchKey() + "=" + c.batchNo())
                .reduce((a, b) -> a + SEP + b)
                .orElse("");
        String fingerprint = fingerprint("split", batchKey, childSpec);
        return executeIdempotent(CMD_SPLIT, req.commandKey(), fingerprint, () -> {
            // 沿子→父方向对整条祖先链加行锁，与祖先召回按提交顺序串行裁决
            List<BatchRepository.BatchRow> chain = lockAncestorChain(batchKey);
            BatchRepository.BatchRow parent = chain.get(0);
            // 召回（含自身在并发窗口中先被召回）先提交：新操作一律 422
            for (BatchRepository.BatchRow locked : chain) {
                if (BatchStatus.RECALLED.name().equals(locked.status())) {
                    throw ApiException.unprocessable(
                            "批次或其祖先已召回，禁止拆分: " + locked.batchKey());
                }
            }
            BatchStatus status = BatchStatus.valueOf(parent.status());
            if (status != BatchStatus.RELEASED) {
                throw ApiException.conflict(
                        "批次状态 " + status + " 不允许拆分，仅 RELEASED 可拆分");
            }
            Set<String> requestKeys = new HashSet<>();
            for (SplitChildRequest child : children) {
                if (!requestKeys.add(child.batchKey())) {
                    throw ApiException.conflict("请求中存在重复子批 batchKey: " + child.batchKey());
                }
                // 父批自身已存在，子批键与父批相同同样属于键已存在 → 409
                if (repo.findBatch(child.batchKey()).isPresent()) {
                    throw ApiException.conflict("子批 batchKey 已存在: " + child.batchKey());
                }
            }

            String now = now();
            List<String> required = repo.findRequiredTests(batchKey);
            List<BatchResponse> childBodies = new ArrayList<>(children.size());
            for (SplitChildRequest child : children) {
                repo.insertBatch(new BatchRepository.BatchRow(0L, child.batchKey(),
                        parent.productCode(), child.batchNo(), parent.producedAt(),
                        BatchStatus.QUARANTINED.name(), now));
                for (int i = 0; i < required.size(); i++) {
                    repo.insertRequiredTest(child.batchKey(), required.get(i), i + 1);
                }
                repo.insertLineage(batchKey, child.batchKey(), now);
                childBodies.add(new BatchResponse(child.batchKey(), parent.productCode(),
                        child.batchNo(), Instant.parse(parent.producedAt()),
                        BatchStatus.QUARANTINED, required, Instant.parse(now)));
            }
            repo.updateStatus(batchKey, BatchStatus.SPLIT.name());

            BatchResponse parentBody = new BatchResponse(parent.batchKey(), parent.productCode(),
                    parent.batchNo(), Instant.parse(parent.producedAt()), BatchStatus.SPLIT,
                    required, Instant.parse(parent.createdAt()));
            SplitResponse body = new SplitResponse(parentBody, childBodies, Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 祖先与后代查询：self 为被查询批次；ancestors 由近及远；descendants 按层次（BFS）排列。
     * 每个节点携带批次自身状态及导致其不可用的召回祖先（不含自身直接召回）。
     */
    public LineageResponse lineage(String batchKey) {
        Map<String, BatchRepository.BatchRow> rows = new HashMap<>();
        Map<String, String> parentOf = new HashMap<>();
        for (BatchRepository.BatchRow row : repo.findAllBatches()) {
            rows.put(row.batchKey(), row);
        }
        for (BatchRepository.LineageRow edge : repo.findAllLineage()) {
            parentOf.put(edge.childKey(), edge.parentKey());
        }
        BatchRepository.BatchRow selfRow = rows.get(batchKey);
        if (selfRow == null) {
            throw ApiException.notFound("批次不存在: " + batchKey);
        }
        Map<String, List<String>> childrenOf = new HashMap<>();
        for (BatchRepository.LineageRow edge : repo.findAllLineage()) {
            childrenOf.computeIfAbsent(edge.parentKey(), k -> new ArrayList<>()).add(edge.childKey());
        }

        List<LineageNodeResponse> ancestors = new ArrayList<>();
        String cursor = parentOf.get(batchKey);
        while (cursor != null) {
            BatchRepository.BatchRow ancestor = rows.get(cursor);
            if (ancestor != null) {
                ancestors.add(toLineageNode(ancestor, rows, parentOf));
            }
            cursor = parentOf.get(cursor);
        }

        List<LineageNodeResponse> descendants = new ArrayList<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(batchKey);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            List<String> kids = childrenOf.getOrDefault(current, List.of());
            for (String childKey : kids) {
                BatchRepository.BatchRow child = rows.get(childKey);
                if (child != null) {
                    descendants.add(toLineageNode(child, rows, parentOf));
                    queue.add(childKey);
                }
            }
        }

        return new LineageResponse(toLineageNode(selfRow, rows, parentOf), ancestors, descendants);
    }

    /**
     * 当前可用批次：排除自身 RECALLED、拆分后 SPLIT，以及存在已召回祖先的后代批次；
     * REJECTED 等其他状态仍保留在列表中（与既有语义一致，只有召回/拆分会移出可用视图）。
     */
    public List<BatchResponse> listAvailable() {
        Map<String, BatchRepository.BatchRow> rows = new LinkedHashMap<>();
        Map<String, String> parentOf = new HashMap<>();
        for (BatchRepository.BatchRow row : repo.findAllBatches()) {
            rows.put(row.batchKey(), row);
        }
        for (BatchRepository.LineageRow edge : repo.findAllLineage()) {
            parentOf.put(edge.childKey(), edge.parentKey());
        }
        List<BatchResponse> available = new ArrayList<>();
        for (BatchRepository.BatchRow row : rows.values()) {
            String status = row.status();
            if (BatchStatus.RECALLED.name().equals(status) || BatchStatus.SPLIT.name().equals(status)) {
                continue;
            }
            if (hasRecalledAncestor(row.batchKey(), rows, parentOf)) {
                continue;
            }
            available.add(toBatchResponse(row));
        }
        return available;
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
     * 未命中执行业务动作并写入快照。并发同键时，后到事务可能在行锁上等待，
     * 对方提交后本事务以业务冲突（409/422）或唯一键冲突失败：回滚后重查 command_log，
     * 若同键快照已落库则重放首次结果（或按指纹返回 409）；否则把原始业务异常抛出。
     */
    private StoredResponse executeIdempotent(String type, String commandKey, String fingerprint,
                                             Supplier<StoredResponse> action) {
        ApiException pendingBusinessError = null;
        for (int attempt = 0; attempt < IDEMPOTENCY_MAX_ATTEMPTS; attempt++) {
            try {
                return tx.execute(status -> {
                    var existing = repo.findCommand(type, commandKey);
                    if (existing.isPresent()) {
                        return replayOrConflict(existing.get(), fingerprint, commandKey);
                    }
                    StoredResponse response = action.get();
                    repo.insertCommand(new BatchRepository.CommandRow(type, commandKey, fingerprint,
                            response.status(), response.body()), now());
                    return response;
                });
            } catch (DuplicateKeyException e) {
                // 并发同事务键/业务唯一键冲突：回滚后查对方是否已提交同键快照
                StoredResponse snapshot = findCommittedSnapshot(type, commandKey, fingerprint);
                if (snapshot != null) {
                    return snapshot;
                }
                pendingBusinessError = ApiException.conflict("键并发冲突，请重试: " + commandKey);
                sleepBeforeRetry();
            } catch (ApiException e) {
                StoredResponse snapshot = findCommittedSnapshot(type, commandKey, fingerprint);
                if (snapshot != null) {
                    return snapshot;
                }
                // 无同键快照：这是真实的业务拒绝，原样抛出
                throw e;
            }
        }
        throw pendingBusinessError != null
                ? pendingBusinessError
                : ApiException.conflict("命令并发冲突，请重试: " + commandKey);
    }

    /**
     * 在独立事务中读取已提交的同键命令快照；指纹不符时抛 409，无记录返回 null。
     */
    private StoredResponse findCommittedSnapshot(String type, String commandKey, String fingerprint) {
        return tx.execute(status -> repo.findCommand(type, commandKey)
                .map(row -> replayOrConflict(row, fingerprint, commandKey))
                .orElse(null));
    }

    private StoredResponse replayOrConflict(BatchRepository.CommandRow row, String fingerprint,
                                            String commandKey) {
        if (!row.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("commandKey 已以不同参数使用: " + commandKey);
        }
        return new StoredResponse(row.responseStatus(), row.responseBody());
    }

    private void sleepBeforeRetry() {
        try {
            Thread.sleep(IDEMPOTENCY_RETRY_BACKOFF_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ApiException.conflict("命令并发冲突，重试被中断");
        }
    }

    private boolean allRequiredPassed(String batchKey, List<String> required) {        Set<String> passed = new HashSet<>();
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

    /**
     * 在事务内沿子→父方向对批次及其全部祖先逐行 SELECT ... FOR UPDATE：
     * 祖先召回与后代新操作由此按提交顺序串行裁决，返回链条索引 0 为批次自身，其后由近及远。
     */
    private List<BatchRepository.BatchRow> lockAncestorChain(String batchKey) {
        List<BatchRepository.BatchRow> chain = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String cursor = batchKey;
        while (cursor != null && seen.add(cursor)) {
            String lockedKey = cursor;
            BatchRepository.BatchRow row = repo.findBatchForUpdate(lockedKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + lockedKey));
            chain.add(row);
            cursor = repo.findLineageByChild(lockedKey)
                    .map(BatchRepository.LineageRow::parentKey)
                    .orElse(null);
        }
        return chain;
    }

    /**
     * 判断批次是否存在已召回祖先（不含自身）；自身状态不改写，也不把后代伪造成直接召回。
     */
    private boolean hasRecalledAncestor(String batchKey,
                                        Map<String, BatchRepository.BatchRow> rows,
                                        Map<String, String> parentOf) {
        String cursor = parentOf.get(batchKey);
        Set<String> seen = new HashSet<>();
        while (cursor != null && seen.add(cursor)) {
            BatchRepository.BatchRow ancestor = rows.get(cursor);
            if (ancestor != null && BatchStatus.RECALLED.name().equals(ancestor.status())) {
                return true;
            }
            cursor = parentOf.get(cursor);
        }
        return false;
    }

    /**
     * 构造血缘节点：批次自身概要 + 已召回祖先列表（由近及远，携带原因与召回时间）。
     */
    private LineageNodeResponse toLineageNode(BatchRepository.BatchRow row,
                                              Map<String, BatchRepository.BatchRow> rows,
                                              Map<String, String> parentOf) {
        List<RecalledAncestorResponse> recalled = new ArrayList<>();
        String cursor = parentOf.get(row.batchKey());
        Set<String> seen = new HashSet<>();
        while (cursor != null && seen.add(cursor)) {
            BatchRepository.BatchRow ancestor = rows.get(cursor);
            if (ancestor != null && BatchStatus.RECALLED.name().equals(ancestor.status())) {
                repo.findRecall(cursor).ifPresent(r -> recalled.add(new RecalledAncestorResponse(
                        r.batchKey(), r.reason(), Instant.parse(r.createdAt()))));
            }
            cursor = parentOf.get(cursor);
        }
        return new LineageNodeResponse(toBatchResponse(row), recalled);
    }
}
