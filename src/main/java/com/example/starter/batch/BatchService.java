package com.example.starter.batch;

import com.example.starter.batch.dto.ApprovalResponse;
import com.example.starter.batch.dto.ApproveRequest;
import com.example.starter.batch.dto.BatchHistoryResponse;
import com.example.starter.batch.dto.BatchResponse;
import com.example.starter.batch.dto.BatchYieldResponse;
import com.example.starter.batch.dto.CreateBatchRequest;
import com.example.starter.batch.dto.LineageEntryResponse;
import com.example.starter.batch.dto.RecallRequest;
import com.example.starter.batch.dto.RecallResponse;
import com.example.starter.batch.dto.SplitRequest;
import com.example.starter.batch.dto.SplitResponse;
import com.example.starter.batch.dto.SubmitTestRequest;
import com.example.starter.batch.dto.SubmitYieldRequest;
import com.example.starter.batch.dto.SubmitYieldResponse;
import com.example.starter.batch.dto.TestResultResponse;
import com.example.starter.batch.dto.YieldAllocationResponse;
import com.example.starter.batch.dto.YieldEntryResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
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
    private static final String CMD_YIELD = "SUBMIT_YIELD";

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
     * 登记或修订批次产率：一个请求可含多批次条目，先校验全部条目应用后的最终分配，
     * 再在单事务写入，任一失败整单回滚。仅 RELEASED 或 SPLIT 的完成批次可登记；
     * 拆分/合批子批须直接父批已登记产出量，且同一父批下子批投入量之和不超过父批产出量；
     * 召回批次及其血缘闭包内批次返回 409。yieldKey 幂等：指纹含操作者、expectedVersion、
     * 规范化批次集合和全部数值，同键成功重放首次快照，失败不占键。
     */
    public StoredResponse submitYield(String actorId, SubmitYieldRequest req) {
        if (actorId == null || actorId.isBlank()) {
            throw ApiException.badRequest("X-Actor-Id 不能为空");
        }
        String operator = actorId.trim();
        List<SubmitYieldRequest.YieldEntry> entries = req.entries();
        List<String> entryKeys = entries.stream().map(SubmitYieldRequest.YieldEntry::batchKey).toList();
        if (new HashSet<>(entryKeys).size() != entryKeys.size()) {
            throw ApiException.conflict("entries 内 batchKey 重复");
        }
        List<String> parts = new ArrayList<>();
        parts.add("yield");
        parts.add(operator);
        entries.stream()
                .sorted(Comparator.comparing(SubmitYieldRequest.YieldEntry::batchKey))
                .forEach(e -> {
                    parts.add(e.batchKey());
                    parts.add(e.expectedVersion() == null ? "null" : e.expectedVersion().toString());
                    parts.add(normalizeQuantity(e.inputQuantity()));
                    parts.add(normalizeQuantity(e.outputQuantity()));
                });
        String fingerprint = fingerprint(parts.toArray(new String[0]));
        return executeIdempotent(CMD_YIELD, req.yieldKey(), fingerprint, () -> {
            // 直接父批关系（血缘只增不改，可在加锁前读取）
            Map<String, String> parentOfEntry = new HashMap<>();
            for (String key : entryKeys) {
                repo.findParentKey(key).ifPresent(p -> parentOfEntry.put(key, p));
            }
            // 按业务键排序逐行锁定全部条目批次及其直接父批：与召回/拆分/并发产率命令互斥，
            // 按事务提交顺序裁决
            Set<String> lockKeys = new TreeSet<>(entryKeys);
            lockKeys.addAll(parentOfEntry.values());
            Map<String, BatchRepository.BatchRow> batches = new HashMap<>();
            for (String key : lockKeys) {
                repo.findBatchForUpdate(key)
                        .ifPresent(row -> batches.put(key, row));
            }
            // 行锁后重查命令快照：并发同键请求在锁等待期间可能已由对方提交
            var logged = loggedResponse(CMD_YIELD, req.yieldKey(), fingerprint);
            if (logged.isPresent()) {
                return logged.get();
            }
            for (String key : entryKeys) {
                if (!batches.containsKey(key)) {
                    throw ApiException.notFound("批次不存在: " + key);
                }
            }

            // 逐条目校验：召回门禁 → 完成状态 → 版本语义
            Map<String, BatchRepository.YieldRow> existingYields = new HashMap<>();
            for (SubmitYieldRequest.YieldEntry entry : entries) {
                String key = entry.batchKey();
                BatchRepository.BatchRow batch = batches.get(key);
                assertYieldNotRecallBlocked(key, batch);
                if (!BatchStatus.RELEASED.name().equals(batch.status())
                        && !BatchStatus.SPLIT.name().equals(batch.status())) {
                    throw ApiException.conflict("批次状态 " + batch.status()
                            + " 不允许登记产率，仅 RELEASED 或 SPLIT 的完成批次可登记");
                }
                Optional<BatchRepository.YieldRow> existing = repo.findYield(key);
                existing.ifPresent(y -> existingYields.put(key, y));
                if (entry.expectedVersion() == null) {
                    if (existing.isPresent()) {
                        throw ApiException.conflict(
                                "批次已存在产率记录，修订须携带 expectedVersion: " + key);
                    }
                } else {
                    if (existing.isEmpty()) {
                        throw ApiException.conflict("批次尚无产率记录，无法修订: " + key);
                    }
                    int current = existing.get().version();
                    if (current != entry.expectedVersion()) {
                        throw ApiException.conflict("expectedVersion 与当前版本不一致: 期望 "
                                + entry.expectedVersion() + "，当前 " + current);
                    }
                }
            }

            // 父子守恒：按全部条目应用后的最终状态校验
            assertYieldConservation(entries, parentOfEntry, existingYields);

            String now = now();
            List<YieldEntryResponse> bodies = new ArrayList<>(entries.size());
            for (SubmitYieldRequest.YieldEntry entry : entries) {
                String key = entry.batchKey();
                BatchRepository.YieldRow existing = existingYields.get(key);
                int version = existing == null ? 1 : existing.version() + 1;
                BatchRepository.YieldRow row = new BatchRepository.YieldRow(0L, key,
                        entry.inputQuantity(), entry.outputQuantity(), version, operator, now, now);
                if (existing == null) {
                    repo.insertYield(row);
                } else {
                    repo.updateYield(row);
                }
                bodies.add(toYieldResponse(row));
            }
            SubmitYieldResponse body = new SubmitYieldResponse(req.yieldKey(), bodies,
                    Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 查询批次产率：含批次状态、产率记录（未登记为 null）与召回阻断原因（无阻断为 null）。
     */
    public BatchYieldResponse yieldOf(String batchKey) {
        BatchRepository.BatchRow batch = repo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        YieldEntryResponse yield = repo.findYield(batchKey)
                .map(this::toYieldResponse)
                .orElse(null);
        return new BatchYieldResponse(batchKey, BatchStatus.valueOf(batch.status()), yield,
                recallBlockOf(batch));
    }

    /**
     * 查询父子分配汇总：本批作为父批的产出/已分配/剩余，以及作为子批的直接父批汇总。
     */
    public YieldAllocationResponse yieldAllocation(String batchKey) {
        repo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        BigDecimal output = repo.findYield(batchKey)
                .map(BatchRepository.YieldRow::outputQuantity)
                .orElse(null);
        Map<String, BigDecimal> childInputs = new HashMap<>();
        for (BatchRepository.YieldRow row : repo.findChildYields(batchKey)) {
            childInputs.put(row.batchKey(), row.inputQuantity());
        }
        BigDecimal allocated = BigDecimal.ZERO;
        List<YieldAllocationResponse.ChildAllocation> children = new ArrayList<>();
        for (String childKey : repo.findDirectChildKeys(batchKey)) {
            BigDecimal input = childInputs.get(childKey);
            if (input != null) {
                allocated = allocated.add(input);
            }
            children.add(new YieldAllocationResponse.ChildAllocation(childKey, input));
        }
        BigDecimal remaining = output == null ? null : output.subtract(allocated);

        YieldAllocationResponse.ParentAllocation parent = null;
        Optional<String> parentKey = repo.findParentKey(batchKey);
        if (parentKey.isPresent()) {
            String pk = parentKey.get();
            BigDecimal parentOutput = repo.findYield(pk)
                    .map(BatchRepository.YieldRow::outputQuantity)
                    .orElse(null);
            BigDecimal parentAllocated = BigDecimal.ZERO;
            for (BatchRepository.YieldRow row : repo.findChildYields(pk)) {
                parentAllocated = parentAllocated.add(row.inputQuantity());
            }
            BigDecimal parentRemaining = parentOutput == null ? null
                    : parentOutput.subtract(parentAllocated);
            parent = new YieldAllocationResponse.ParentAllocation(pk, parentOutput,
                    parentAllocated, parentRemaining);
        }
        return new YieldAllocationResponse(batchKey, output, allocated, remaining, parent, children);
    }

    /**
     * 父子守恒校验（最终分配状态）：条目批次有直接父批时父批必须已登记产出量；
     * 同一父批下全部子批最终投入量之和不得超过父批最终产出量；
     * 修订父批产出量低于已有子批分配总量同样拒绝。任一违反抛 422，整单不生效。
     */
    private void assertYieldConservation(List<SubmitYieldRequest.YieldEntry> entries,
                                         Map<String, String> parentOfEntry,
                                         Map<String, BatchRepository.YieldRow> existingYields) {
        Map<String, SubmitYieldRequest.YieldEntry> entryByKey = new HashMap<>();
        for (SubmitYieldRequest.YieldEntry entry : entries) {
            entryByKey.put(entry.batchKey(), entry);
        }
        // 受影响的父批：条目的直接父批（子批驱动）与条目批次自身（父批产出量驱动）
        Set<String> conservationParents = new TreeSet<>(parentOfEntry.values());
        conservationParents.addAll(entryByKey.keySet());

        Map<String, BigDecimal> finalOutput = new HashMap<>();
        for (String parentKey : conservationParents) {
            SubmitYieldRequest.YieldEntry entry = entryByKey.get(parentKey);
            if (entry != null) {
                finalOutput.put(parentKey, entry.outputQuantity());
            } else {
                repo.findYield(parentKey).ifPresentOrElse(
                        y -> finalOutput.put(parentKey, y.outputQuantity()),
                        () -> finalOutput.put(parentKey, null));
            }
        }
        // 子批驱动：直接父批必须已登记产出量（含同请求内登记的最终状态）
        for (Map.Entry<String, String> e : parentOfEntry.entrySet()) {
            if (finalOutput.get(e.getValue()) == null) {
                throw ApiException.unprocessable(
                        "直接父批次 " + e.getValue() + " 未登记产出量，禁止登记或修订子批产率: "
                                + e.getKey());
            }
        }
        // 每个受影响父批：子批最终投入量之和 ≤ 父批最终产出量
        for (String parentKey : conservationParents) {
            BigDecimal output = finalOutput.get(parentKey);
            if (output == null) {
                continue;
            }
            Map<String, BigDecimal> finalInputs = new HashMap<>();
            for (BatchRepository.YieldRow row : repo.findChildYields(parentKey)) {
                finalInputs.put(row.batchKey(), row.inputQuantity());
            }
            for (Map.Entry<String, String> e : parentOfEntry.entrySet()) {
                if (e.getValue().equals(parentKey)) {
                    finalInputs.put(e.getKey(), entryByKey.get(e.getKey()).inputQuantity());
                }
            }
            BigDecimal allocated = BigDecimal.ZERO;
            for (BigDecimal input : finalInputs.values()) {
                allocated = allocated.add(input);
            }
            if (allocated.compareTo(output) > 0) {
                SubmitYieldRequest.YieldEntry parentEntry = entryByKey.get(parentKey);
                if (parentEntry != null) {
                    throw ApiException.unprocessable("父批次 " + parentKey + " 拟登记产出量 "
                            + output.toPlainString() + " 低于已有子批次分配总量 "
                            + allocated.toPlainString());
                }
                String childKey = parentOfEntry.entrySet().stream()
                        .filter(e -> e.getValue().equals(parentKey))
                        .map(Map.Entry::getKey)
                        .findFirst().orElseThrow();
                BigDecimal proposed = entryByKey.get(childKey).inputQuantity();
                BigDecimal others = allocated.subtract(proposed);
                throw ApiException.unprocessable("父批次 " + parentKey + " 产出量 "
                        + output.toPlainString() + " 不足：已分配 " + others.toPlainString()
                        + "，拟分配 " + proposed.toPlainString());
            }
        }
    }

    /**
     * 产率召回门禁：批次自身已召回或其血缘闭包内祖先已召回时抛 409；已登记记录不删除。
     */
    private void assertYieldNotRecallBlocked(String batchKey, BatchRepository.BatchRow batch) {
        if (BatchStatus.RECALLED.name().equals(batch.status())) {
            throw ApiException.conflict("批次已召回，禁止新增或修订产率: " + batchKey);
        }
        Optional<String> recalled = recalledAncestor(batchKey, childToParent(),
                new HashSet<>(repo.findRecalledKeys()));
        if (recalled.isPresent()) {
            throw ApiException.conflict(
                    "祖先批次 " + recalled.get() + " 已召回，禁止新增或修订产率");
        }
    }

    /**
     * 召回阻断原因：批次自身被直接召回（direct=true）或血缘闭包内祖先被召回（direct=false）；
     * 无阻断返回 null。
     */
    private BatchYieldResponse.RecallBlock recallBlockOf(BatchRepository.BatchRow batch) {
        if (BatchStatus.RECALLED.name().equals(batch.status())) {
            String reason = repo.findRecall(batch.batchKey())
                    .map(BatchRepository.RecallRow::reason).orElse(null);
            return new BatchYieldResponse.RecallBlock(batch.batchKey(), reason, true);
        }
        Optional<String> recalled = recalledAncestor(batch.batchKey(), childToParent(),
                new HashSet<>(repo.findRecalledKeys()));
        if (recalled.isPresent()) {
            String reason = repo.findRecall(recalled.get())
                    .map(BatchRepository.RecallRow::reason).orElse(null);
            return new BatchYieldResponse.RecallBlock(recalled.get(), reason, false);
        }
        return null;
    }

    /**
     * 产率记录视图：原始投入/产出数值保留，产率按产出/投入四位小数（HALF_UP）展示。
     */
    private YieldEntryResponse toYieldResponse(BatchRepository.YieldRow row) {
        BigDecimal rate = row.outputQuantity().divide(row.inputQuantity(), 4, RoundingMode.HALF_UP);
        return new YieldEntryResponse(row.batchKey(), row.inputQuantity(), row.outputQuantity(),
                rate, row.version(), row.operatorId(), Instant.parse(row.updatedAt()));
    }

    /**
     * 数量规范化：去除尾部零的纯十进制表示，用于幂等指纹，避免 1.5 与 1.50 产生不同指纹。
     */
    private String normalizeQuantity(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
