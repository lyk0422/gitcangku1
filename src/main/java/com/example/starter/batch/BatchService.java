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
import com.example.starter.batch.dto.YieldAllocationResponse;
import com.example.starter.batch.dto.YieldBlockResponse;
import com.example.starter.batch.dto.YieldRecordResponse;
import com.example.starter.batch.dto.YieldSubmitRequest;
import com.example.starter.batch.dto.YieldSubmitResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
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
    private static final String CMD_YIELD = "YIELD";

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
            } catch (PessimisticLockingFailureException e) {
                // 行锁等待超时或死锁（如与召回的后代锁交织）：回滚后重试，按提交顺序裁决
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
     * 登记/修订批次产率：一个请求可含多批次，先校验最终分配再单事务写入，任一失败整单回滚。
     *
     * <p>校验顺序：参数（>0 且最多三位小数）→ 召回血缘闭包门禁（409）→ 版本裁决
     * （登记要求无既有记录，修订要求 expectedVersion 等于当前版本）→ 父子守恒
     * （直接父批已登记产出量，且子批投入量之和不超过父批产出量，超出 422 并给出
     * 父批次、已分配和拟分配量；修订父批产出量低于已有子批分配总量同样 422）。
     * 并发策略：事务内按业务键排序锁定涉及批次及其直接父批行，按事务提交顺序裁决。
     */
    public StoredResponse submitYields(YieldSubmitRequest req) {
        String operator = req.operator().trim();
        List<YieldSubmitRequest.Item> items = req.items();
        List<String> keys = items.stream().map(YieldSubmitRequest.Item::batchKey).toList();
        if (new HashSet<>(keys).size() != keys.size()) {
            throw ApiException.badRequest("items 内 batchKey 重复");
        }
        for (YieldSubmitRequest.Item item : items) {
            validateQty(item.inputQty(), "inputQty");
            validateQty(item.outputQty(), "outputQty");
            if (item.expectedVersion() != null && item.expectedVersion() < 1) {
                throw ApiException.badRequest("expectedVersion 必须为正整数");
            }
        }
        List<YieldSubmitRequest.Item> sortedItems = new ArrayList<>(items);
        sortedItems.sort(java.util.Comparator.comparing(YieldSubmitRequest.Item::batchKey));
        List<String> parts = new ArrayList<>();
        parts.add("yield");
        parts.add(operator);
        for (YieldSubmitRequest.Item item : sortedItems) {
            parts.add(item.batchKey());
            parts.add(item.expectedVersion() == null ? "NEW" : String.valueOf(item.expectedVersion()));
            parts.add(normQty(item.inputQty()));
            parts.add(normQty(item.outputQty()));
        }
        String fingerprint = fingerprint(parts.toArray(new String[0]));
        return executeIdempotent(CMD_YIELD, req.yieldKey(), fingerprint, () -> {
            // 锁定涉及批次及其直接父批（按业务键排序保证锁顺序确定），与拆分/召回/
            // 并发产率命令互斥；父批行锁使同一父批下的子批分配串行化
            Set<String> lockKeys = new TreeSet<>(keys);
            for (String key : keys) {
                lockKeys.addAll(repo.findParentKeys(key));
            }
            Map<String, BatchRepository.BatchRow> batches = new HashMap<>();
            for (String key : lockKeys) {
                batches.put(key, repo.findBatchForUpdate(key)
                        .orElseThrow(() -> ApiException.notFound("批次不存在: " + key)));
            }
            // 行锁后重查命令快照：并发同键请求在锁等待期间可能已由对方提交
            var logged = loggedResponse(CMD_YIELD, req.yieldKey(), fingerprint);
            if (logged.isPresent()) {
                return logged.get();
            }

            Map<String, String> parentOf = childToParent();
            Set<String> recalled = new HashSet<>(repo.findRecalledKeys());
            Map<String, BatchRepository.YieldRow> stored = new HashMap<>();
            for (String key : keys) {
                // 召回门禁：批次自身被召回或血缘闭包内任一祖先被召回 → 409，已登记记录不删除
                String blocker = BatchStatus.RECALLED.name().equals(batches.get(key).status())
                        ? key
                        : recalledAncestor(key, parentOf, recalled).orElse(null);
                if (blocker != null) {
                    throw ApiException.conflict("批次 " + key + " 处于召回血缘闭包内（召回批次 "
                            + blocker + "），不得新增或修订产率");
                }
                repo.findYield(key).ifPresent(y -> stored.put(key, y));
            }

            // 版本裁决：同一批次仅一份产率记录
            for (YieldSubmitRequest.Item item : items) {
                BatchRepository.YieldRow row = stored.get(item.batchKey());
                if (row == null) {
                    if (item.expectedVersion() != null) {
                        throw ApiException.conflict(
                                "批次 " + item.batchKey() + " 尚未登记产率，不能修订");
                    }
                } else if (item.expectedVersion() == null) {
                    throw ApiException.conflict("批次 " + item.batchKey()
                            + " 已存在产率记录，修订须携带 expectedVersion");
                } else if (item.expectedVersion() != row.version()) {
                    throw ApiException.conflict("批次 " + item.batchKey()
                            + " expectedVersion 不匹配：当前版本 " + row.version()
                            + "，实传 " + item.expectedVersion());
                }
            }

            // 父子守恒：按最终分配（本请求覆盖既有记录）校验每个涉及的父批
            Map<String, BigDecimal> proposedInput = new HashMap<>();
            Map<String, BigDecimal> proposedOutput = new HashMap<>();
            for (YieldSubmitRequest.Item item : items) {
                proposedInput.put(item.batchKey(), scale3(item.inputQty()));
                proposedOutput.put(item.batchKey(), scale3(item.outputQty()));
            }
            Set<String> parentsToCheck = new TreeSet<>();
            for (String key : keys) {
                parentsToCheck.addAll(repo.findParentKeys(key));
            }
            for (String key : keys) {
                if (!repo.findChildKeys(key).isEmpty()) {
                    parentsToCheck.add(key);
                }
            }
            for (String parentKey : parentsToCheck) {
                BigDecimal output = proposedOutput.containsKey(parentKey)
                        ? proposedOutput.get(parentKey)
                        : repo.findYield(parentKey)
                                .map(BatchRepository.YieldRow::outputQty).orElse(null);
                BigDecimal allocated = BigDecimal.ZERO;
                BigDecimal proposedForParent = BigDecimal.ZERO;
                for (String childKey : repo.findChildKeys(parentKey)) {
                    BigDecimal input = proposedInput.get(childKey);
                    if (input == null) {
                        input = repo.findYield(childKey)
                                .map(BatchRepository.YieldRow::inputQty).orElse(null);
                    }
                    if (input != null) {
                        allocated = allocated.add(input);
                    }
                    if (proposedInput.containsKey(childKey)) {
                        proposedForParent = proposedForParent.add(proposedInput.get(childKey));
                    }
                }
                if (output == null) {
                    if (allocated.signum() > 0) {
                        throw ApiException.unprocessable(
                                "父批次 " + parentKey + " 未登记产出量，子批次不得登记投入量");
                    }
                } else if (allocated.compareTo(output) > 0) {
                    BigDecimal alreadyAllocated = allocated.subtract(proposedForParent);
                    throw ApiException.unprocessable("父批次 " + parentKey + " 产出量 "
                            + output.toPlainString() + " 不足：已分配 "
                            + alreadyAllocated.toPlainString() + "，拟分配 "
                            + proposedForParent.toPlainString());
                }
            }

            String now = now();
            boolean allNew = true;
            List<YieldRecordResponse> records = new ArrayList<>(items.size());
            for (YieldSubmitRequest.Item item : items) {
                BatchRepository.YieldRow old = stored.get(item.batchKey());
                long version = old == null ? 1L : old.version() + 1;
                if (old != null) {
                    allNew = false;
                }
                String createdAt = old == null ? now : old.createdAt();
                BatchRepository.YieldRow row = new BatchRepository.YieldRow(0L, item.batchKey(),
                        scale3(item.inputQty()), scale3(item.outputQty()), version, operator,
                        createdAt, now);
                if (old == null) {
                    repo.insertYield(row);
                } else {
                    repo.updateYield(row);
                }
                records.add(toYieldResponse(row));
            }
            YieldSubmitResponse body = new YieldSubmitResponse(req.yieldKey(), records,
                    Instant.parse(now));
            return new StoredResponse(allNew ? 201 : 200, toJson(body));
        });
    }

    /**
     * 查询单批次产率：原始数值保留，yieldRate 按四位小数展示。
     */
    public YieldRecordResponse getYield(String batchKey) {
        repo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        return repo.findYield(batchKey)
                .map(this::toYieldResponse)
                .orElseThrow(() -> ApiException.notFound("批次未登记产率: " + batchKey));
    }

    /**
     * 父子分配汇总：本批产出、全部直接子批已分配投入与剩余可分配量，
     * 以及各直接父批的产出/分配/剩余和本批占用投入。
     */
    public YieldAllocationResponse getYieldAllocation(String batchKey) {
        repo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        BatchRepository.YieldRow self = repo.findYield(batchKey).orElse(null);
        BigDecimal allocated = BigDecimal.ZERO;
        List<YieldAllocationResponse.ChildAllocation> children = new ArrayList<>();
        for (String childKey : repo.findChildKeys(batchKey)) {
            BatchRepository.YieldRow childYield = repo.findYield(childKey).orElse(null);
            if (childYield == null) {
                children.add(new YieldAllocationResponse.ChildAllocation(childKey, null, null, null));
            } else {
                allocated = allocated.add(childYield.inputQty());
                children.add(new YieldAllocationResponse.ChildAllocation(childKey,
                        childYield.inputQty().toPlainString(), childYield.outputQty().toPlainString(),
                        yieldRate(childYield)));
            }
        }
        List<YieldAllocationResponse.ParentAllocation> parents = new ArrayList<>();
        for (String parentKey : repo.findParentKeys(batchKey)) {
            BatchRepository.YieldRow parentYield = repo.findYield(parentKey).orElse(null);
            BigDecimal parentAllocated = BigDecimal.ZERO;
            for (BatchRepository.YieldRow childYield : repo.findChildYields(parentKey)) {
                parentAllocated = parentAllocated.add(childYield.inputQty());
            }
            parents.add(new YieldAllocationResponse.ParentAllocation(parentKey,
                    parentYield == null ? null : parentYield.outputQty().toPlainString(),
                    parentAllocated.toPlainString(),
                    parentYield == null ? null
                            : parentYield.outputQty().subtract(parentAllocated).toPlainString(),
                    self == null ? null : self.inputQty().toPlainString()));
        }
        return new YieldAllocationResponse(batchKey,
                self == null ? null : self.outputQty().toPlainString(),
                allocated.toPlainString(),
                self == null ? null : self.outputQty().subtract(allocated).toPlainString(),
                children, parents);
    }

    /**
     * 产率召回阻断原因：批次自身被直接召回，或血缘闭包内最近的被召回祖先。
     */
    public YieldBlockResponse getYieldBlock(String batchKey) {
        BatchRepository.BatchRow batch = repo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        String blocker = BatchStatus.RECALLED.name().equals(batch.status())
                ? batchKey
                : recalledAncestor(batchKey, childToParent(),
                        new HashSet<>(repo.findRecalledKeys())).orElse(null);
        if (blocker == null) {
            return new YieldBlockResponse(batchKey, false, null, null, null);
        }
        BatchRepository.RecallRow recall = repo.findRecall(blocker)
                .orElseThrow(() -> new IllegalStateException("召回批次缺少召回记录: " + blocker));
        return new YieldBlockResponse(batchKey, true, blocker, recall.reason(),
                Instant.parse(recall.createdAt()));
    }

    /**
     * 校验数量：大于零且最多三位小数，且不超出 DECIMAL(19,3) 范围。
     */
    private void validateQty(BigDecimal qty, String field) {
        if (qty.signum() <= 0) {
            throw ApiException.badRequest(field + " 必须大于零");
        }
        if (qty.stripTrailingZeros().scale() > 3) {
            throw ApiException.badRequest(field + " 最多三位小数");
        }
        if (qty.precision() - qty.scale() > 16) {
            throw ApiException.badRequest(field + " 超出数值范围（整数位最多 16 位）");
        }
    }

    /**
     * 指纹用数量规范化：去除末尾零，避免 1.0 与 1.00 产生不同指纹。
     */
    private String normQty(BigDecimal qty) {
        return qty.stripTrailingZeros().toPlainString();
    }

    /**
     * 落库数量规范化：统一三位小数（与 DECIMAL(19,3) 一致），保证提交响应与后续查询一致；
     * 前置校验已保证最多三位小数，故不会引入舍入。
     */
    private BigDecimal scale3(BigDecimal qty) {
        return qty.setScale(3, RoundingMode.UNNECESSARY);
    }

    /**
     * 产率：产出/投入按四位小数 HALF_UP 展示；仅用于展示，存储保留原始数值。
     */
    private String yieldRate(BatchRepository.YieldRow row) {
        return row.outputQty().divide(row.inputQty(), 4, RoundingMode.HALF_UP).toPlainString();
    }

    private YieldRecordResponse toYieldResponse(BatchRepository.YieldRow row) {
        return new YieldRecordResponse(row.batchKey(), row.inputQty().toPlainString(),
                row.outputQty().toPlainString(), yieldRate(row), row.version(), row.operator(),
                Instant.parse(row.createdAt()), Instant.parse(row.updatedAt()));
    }
}
