package com.example.starter.batch;

import com.example.starter.batch.dto.ApprovalResponse;
import com.example.starter.batch.dto.ApproveRequest;
import com.example.starter.batch.dto.ApproveReleaseRequest;
import com.example.starter.batch.dto.BatchHistoryResponse;
import com.example.starter.batch.dto.BatchResponse;
import com.example.starter.batch.dto.CreateBatchRequest;
import com.example.starter.batch.dto.LineageEntryResponse;
import com.example.starter.batch.dto.RecallImpactEntry;
import com.example.starter.batch.dto.RecallReleaseApplyRequest;
import com.example.starter.batch.dto.RecallReleaseDetailResponse;
import com.example.starter.batch.dto.RecallReleaseResponse;
import com.example.starter.batch.dto.RecallReleaseSnapshotResponse;
import com.example.starter.batch.dto.RecallRequest;
import com.example.starter.batch.dto.RecallResponse;
import com.example.starter.batch.dto.RetestGapResponse;
import com.example.starter.batch.dto.RetestRequest;
import com.example.starter.batch.dto.RetestResponse;
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
    private static final String CMD_RETEST = "RETEST";
    private static final String CMD_RELEASE_APPLY = "RECALL_RELEASE";
    private static final String CMD_RELEASE_APPROVE = "APPROVE_RELEASE";

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
            // 召回代次：同一批次解除后再次召回生成新代次，历史代次记录不删除
            int version = repo.findRecall(batchKey).map(r -> r.version() + 1).orElse(1);
            repo.insertRecall(new BatchRepository.RecallRow(0L, batchKey, req.commandKey(),
                    actor, req.reason(), version, "ACTIVE", status.name(), now, null));
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
     * 提交召回复检：仅自身 RECALLED 或祖先被召回的批次可提交；复检不改变批次状态，
     * retestKey 批次内幂等，同内容重放返回原结果，不同内容返回 409。
     */
    public StoredResponse submitRetest(String batchKey, RetestRequest req) {
        String fingerprint = fingerprint("retest", batchKey, req.retestKey(),
                req.outcome().name(), req.inspector());
        return executeIdempotent(CMD_RETEST, req.commandKey(), fingerprint, () -> {
            BatchRepository.BatchRow batch = repo.findBatchForUpdate(batchKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
            var existing = repo.findRetest(batchKey, req.retestKey());
            if (existing.isPresent()) {
                BatchRepository.RetestRow row = existing.get();
                boolean sameContent = row.outcome().equals(req.outcome().name())
                        && row.inspector().equals(req.inspector());
                if (!sameContent) {
                    throw ApiException.conflict("retestKey 已以不同内容提交: " + req.retestKey());
                }
                return new StoredResponse(200, toJson(toRetestResponse(row)));
            }
            boolean underRecall = BatchStatus.RECALLED.name().equals(batch.status())
                    || recalledAncestor(batchKey, childToParent(),
                            new HashSet<>(repo.findRecalledKeys())).isPresent();
            if (!underRecall) {
                throw ApiException.conflict("批次未处于召回影响范围，无需复检: " + batchKey);
            }
            String now = now();
            repo.insertRetest(new BatchRepository.RetestRow(0L, batchKey, req.retestKey(),
                    req.outcome().name(), req.inspector(), now));
            RetestResponse body = new RetestResponse(batchKey, req.retestKey(), req.outcome(),
                    req.inspector(), Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 申请召回解除：仅根召回记录为 ACTIVE 的批次可申请；复检批次集合去重并规范排序。
     * releaseKey 幂等：指纹含召回版本、规范化复检集合、纠正措施和指定审批人；
     * 同键同参重放返回首次结果，同键改参 409，失败不占键。
     */
    public StoredResponse applyRecallRelease(String batchKey, String actorId,
                                             RecallReleaseApplyRequest req) {
        if (actorId == null || actorId.isBlank()) {
            throw ApiException.badRequest("X-Actor-Id 不能为空");
        }
        String actor = actorId.trim();
        List<String> retestSet = req.retestBatches().stream().map(String::trim).distinct().sorted()
                .toList();
        String measures = req.correctiveMeasures().trim();
        String approver = req.approver().trim();
        String fingerprint = fingerprint("recall-release", batchKey,
                String.valueOf(req.recallVersion()), String.join(SEP, retestSet), measures,
                approver);
        return executeIdempotent(CMD_RELEASE_APPLY, req.releaseKey(), fingerprint, () -> {
            repo.findBatchForUpdate(batchKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
            // 批次行锁后重查命令快照：并发同键请求在锁等待期间可能已由对方提交
            var logged = loggedResponse(CMD_RELEASE_APPLY, req.releaseKey(), fingerprint);
            if (logged.isPresent()) {
                return logged.get();
            }
            BatchRepository.RecallRow recall = repo.findRecall(batchKey)
                    .filter(r -> "ACTIVE".equals(r.recallStatus()))
                    .orElseThrow(() -> ApiException.conflict(
                            "批次无 ACTIVE 召回记录，不可申请解除: " + batchKey));
            if (recall.version() != req.recallVersion()) {
                throw ApiException.conflict("召回版本不匹配：当前 ACTIVE 召回版本为 "
                        + recall.version() + "，申请版本为 " + req.recallVersion());
            }
            String now = now();
            repo.insertRelease(new BatchRepository.ReleaseRow(0L, req.releaseKey(), batchKey,
                    req.recallVersion(), measures, String.join(",", retestSet), approver, actor,
                    "PENDING", now, null));
            RecallReleaseResponse body = new RecallReleaseResponse(req.releaseKey(), batchKey,
                    req.recallVersion(), measures, retestSet, approver, actor, "PENDING",
                    Instant.parse(now), null);
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 批准召回解除：按最终血缘闭包预校验——该批次及全部受影响后代均完成合格复检、
     * 无未决隔离且血缘未新增未知来源；任一失败 422，所有召回状态与放行资格保持不变。
     * 批准后仅解除申请所覆盖召回代次并写入不可变评审快照；后代历史召回记录不删除，
     * 批次状态恢复为召回前状态，历史检验与放行记录不重写。
     */
    public StoredResponse approveRecallRelease(String batchKey, String releaseKey, String actorId,
                                               ApproveReleaseRequest req) {
        if (actorId == null || actorId.isBlank()) {
            throw ApiException.badRequest("X-Actor-Id 不能为空");
        }
        String actor = actorId.trim();
        String fingerprint = fingerprint("approve-release", batchKey, releaseKey, actor);
        return executeIdempotent(CMD_RELEASE_APPROVE, req.commandKey(), fingerprint, () -> {
            repo.findBatchForUpdate(batchKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
            // 批次行锁后重查命令快照：并发同键请求在锁等待期间可能已由对方提交
            var logged = loggedResponse(CMD_RELEASE_APPROVE, req.commandKey(), fingerprint);
            if (logged.isPresent()) {
                return logged.get();
            }
            BatchRepository.ReleaseRow release = repo.findRelease(releaseKey)
                    .filter(r -> r.batchKey().equals(batchKey))
                    .orElseThrow(() -> ApiException.notFound("解除申请不存在: " + releaseKey));
            if (!"PENDING".equals(release.status())) {
                throw ApiException.conflict("解除申请已批准，不可重复批准: " + releaseKey);
            }
            if (!release.approver().equals(actor)) {
                throw ApiException.unprocessable("审批人必须为申请指定审批人: " + release.approver());
            }
            // 逐行锁定全部后代（按业务键排序保证锁顺序确定）：与后代上的检验/批准/拆分/召回
            // 互斥，申请、复检、批准按事务提交顺序裁决
            List<String> descendants = descendantKeys(batchKey);
            Collections.sort(descendants);
            for (String descendantKey : descendants) {
                repo.findBatchForUpdate(descendantKey);
            }
            // 最终血缘闭包（含自身，规范排序）
            List<String> closure = new ArrayList<>(descendants);
            closure.add(batchKey);
            Collections.sort(closure);
            List<String> declared = release.retestBatches().isEmpty()
                    ? List.of()
                    : List.of(release.retestBatches().split(","));
            List<String> unknown = declared.stream().filter(k -> !closure.contains(k)).toList();
            if (!unknown.isEmpty()) {
                throw ApiException.unprocessable(
                        "复检批次集合包含血缘闭包外的未知来源批次: " + String.join(",", unknown));
            }
            List<String> missing = closure.stream().filter(k -> !declared.contains(k)).toList();
            if (!missing.isEmpty()) {
                throw ApiException.unprocessable(
                        "复检批次集合缺少最终血缘闭包批次: " + String.join(",", missing));
            }
            List<String> quarantined = new ArrayList<>();
            List<String> unqualified = new ArrayList<>();
            for (String key : closure) {
                BatchRepository.BatchRow row = repo.findBatch(key)
                        .orElseThrow(() -> ApiException.notFound("批次不存在: " + key));
                if (BatchStatus.QUARANTINED.name().equals(row.status())) {
                    quarantined.add(key);
                }
                if (!retestQualified(key)) {
                    unqualified.add(key);
                }
            }
            if (!quarantined.isEmpty()) {
                throw ApiException.unprocessable(
                        "存在未决隔离批次，不可解除: " + String.join(",", quarantined));
            }
            if (!unqualified.isEmpty()) {
                throw ApiException.unprocessable(
                        "以下批次未完成合格复检: " + String.join(",", unqualified));
            }
            BatchRepository.RecallRow recall = repo.findRecall(batchKey)
                    .filter(r -> "ACTIVE".equals(r.recallStatus()))
                    .orElseThrow(() -> ApiException.conflict(
                            "批次无 ACTIVE 召回记录，不可解除: " + batchKey));
            String now = now();
            // 仅解除申请所覆盖召回代次；后代历史召回记录不删除，仍需各自满足复检
            repo.updateRecallReleased(recall.id(), now);
            repo.updateStatus(batchKey, recall.priorStatus());
            repo.updateReleaseApproved(releaseKey, now);
            repo.insertSnapshot(new BatchRepository.SnapshotRow(0L, releaseKey, batchKey,
                    release.recallVersion(), String.join(",", closure),
                    release.correctiveMeasures(), actor, now));
            RecallReleaseSnapshotResponse body = new RecallReleaseSnapshotResponse(releaseKey,
                    batchKey, release.recallVersion(), closure, release.correctiveMeasures(),
                    actor, Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 召回血缘影响查询：以该批次为根的闭包（自身在前，后代按拆分创建顺序展开），
     * 每项含批次自身状态、最新召回代次与复检合格性。
     */
    public List<RecallImpactEntry> recallImpact(String batchKey) {
        repo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        List<String> closure = new ArrayList<>();
        closure.add(batchKey);
        closure.addAll(descendantKeys(batchKey));
        List<RecallImpactEntry> result = new ArrayList<>(closure.size());
        for (String key : closure) {
            BatchRepository.BatchRow row = repo.findBatch(key)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + key));
            var recall = repo.findRecall(key);
            var latest = latestRetest(key);
            result.add(new RecallImpactEntry(key, BatchStatus.valueOf(row.status()),
                    recall.map(BatchRepository.RecallRow::version).orElse(null),
                    recall.map(BatchRepository.RecallRow::recallStatus).orElse(null),
                    latest.map(r -> TestOutcome.valueOf(r.outcome())).orElse(null),
                    latest.map(r -> TestOutcome.PASS.name().equals(r.outcome())).orElse(false)));
        }
        return result;
    }

    /**
     * 复检缺口查询：最终血缘闭包（规范排序）内未满足解除条件的批次及可区分原因。
     */
    public RetestGapResponse retestGaps(String batchKey) {
        repo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        List<String> closure = new ArrayList<>(descendantKeys(batchKey));
        closure.add(batchKey);
        Collections.sort(closure);
        List<RetestGapResponse.RetestGap> gaps = new ArrayList<>();
        for (String key : closure) {
            BatchRepository.BatchRow row = repo.findBatch(key)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + key));
            if (BatchStatus.QUARANTINED.name().equals(row.status())) {
                gaps.add(new RetestGapResponse.RetestGap(key, "PENDING_QUARANTINE"));
            }
            var latest = latestRetest(key);
            if (latest.isEmpty()) {
                gaps.add(new RetestGapResponse.RetestGap(key, "MISSING_RETEST"));
            } else if (!TestOutcome.PASS.name().equals(latest.get().outcome())) {
                gaps.add(new RetestGapResponse.RetestGap(key, "FAILED_RETEST"));
            }
        }
        return new RetestGapResponse(batchKey, closure, gaps);
    }

    /**
     * 解除申请详情：申请本体 + 批准后的不可变评审快照（未批准时 snapshot 为 null）。
     */
    public RecallReleaseDetailResponse recallReleaseDetail(String batchKey, String releaseKey) {
        repo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        BatchRepository.ReleaseRow release = repo.findRelease(releaseKey)
                .filter(r -> r.batchKey().equals(batchKey))
                .orElseThrow(() -> ApiException.notFound("解除申请不存在: " + releaseKey));
        RecallReleaseSnapshotResponse snapshot = repo.findSnapshot(releaseKey)
                .map(this::toSnapshotResponse)
                .orElse(null);
        return new RecallReleaseDetailResponse(toReleaseResponse(release), snapshot);
    }

    private Optional<BatchRepository.RetestRow> latestRetest(String batchKey) {
        List<BatchRepository.RetestRow> retests = repo.findRetests(batchKey);
        return retests.isEmpty()
                ? Optional.empty()
                : Optional.of(retests.get(retests.size() - 1));
    }

    /**
     * 合格复检判定：该批次最新一条复检结论为 PASS。
     */
    private boolean retestQualified(String batchKey) {
        return latestRetest(batchKey)
                .map(r -> TestOutcome.PASS.name().equals(r.outcome()))
                .orElse(false);
    }

    private RetestResponse toRetestResponse(BatchRepository.RetestRow row) {
        return new RetestResponse(row.batchKey(), row.retestKey(),
                TestOutcome.valueOf(row.outcome()), row.inspector(), Instant.parse(row.createdAt()));
    }

    private RecallReleaseResponse toReleaseResponse(BatchRepository.ReleaseRow row) {
        List<String> retestBatches = row.retestBatches().isEmpty()
                ? List.of()
                : List.of(row.retestBatches().split(","));
        return new RecallReleaseResponse(row.releaseKey(), row.batchKey(), row.recallVersion(),
                row.correctiveMeasures(), retestBatches, row.approver(), row.applicant(),
                row.status(), Instant.parse(row.createdAt()),
                row.decidedAt() == null ? null : Instant.parse(row.decidedAt()));
    }

    private RecallReleaseSnapshotResponse toSnapshotResponse(BatchRepository.SnapshotRow row) {
        return new RecallReleaseSnapshotResponse(row.releaseKey(), row.batchKey(),
                row.recallVersion(), List.of(row.closureBatches().split(",")),
                row.correctiveMeasures(), row.approver(), Instant.parse(row.createdAt()));
    }
}
