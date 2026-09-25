package com.example.starter.batch;

import com.example.starter.batch.dto.ApprovalResponse;
import com.example.starter.batch.dto.ApproveReleaseRequest;
import com.example.starter.batch.dto.ApproveRequest;
import com.example.starter.batch.dto.BatchHistoryResponse;
import com.example.starter.batch.dto.BatchResponse;
import com.example.starter.batch.dto.CreateBatchRequest;
import com.example.starter.batch.dto.LineageEntryResponse;
import com.example.starter.batch.dto.RecallImpactResponse;
import com.example.starter.batch.dto.RecallReleaseApplyRequest;
import com.example.starter.batch.dto.RecallReleaseResponse;
import com.example.starter.batch.dto.RecallReleaseSnapshotResponse;
import com.example.starter.batch.dto.RecallRequest;
import com.example.starter.batch.dto.RecallResponse;
import com.example.starter.batch.dto.ReinspectionGapsResponse;
import com.example.starter.batch.dto.ReinspectionRequest;
import com.example.starter.batch.dto.ReinspectionResponse;
import com.example.starter.batch.dto.SplitRequest;
import com.example.starter.batch.dto.SplitResponse;
import com.example.starter.batch.dto.SubmitTestRequest;
import com.example.starter.batch.dto.TestResultResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
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
import java.util.Arrays;
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
    private static final String CMD_REINSPECTION = "REINSPECTION";
    private static final String CMD_RELEASE_APPLY = "RELEASE_APPLY";
    private static final String CMD_RELEASE_APPROVE = "RELEASE_APPROVE";

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
     * 每次召回产生新召回代次（version 递增），记录召回前状态供解除后恢复；历史召回记录不删除。
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
            int version = repo.findRecalls(batchKey).size() + 1;
            repo.insertRecall(new BatchRepository.RecallRow(0L, batchKey, req.commandKey(),
                    actor, req.reason(), version, "ACTIVE", status.name(), now));
            repo.updateStatus(batchKey, BatchStatus.RECALLED.name());
            RecallResponse body = new RecallResponse(batchKey, actor, req.reason(), version,
                    "ACTIVE", BatchStatus.RECALLED, Instant.parse(now));
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
                .map(r -> new RecallResponse(r.batchKey(), r.actorId(), r.reason(), r.version(),
                        r.status(), BatchStatus.valueOf(batch.status()), Instant.parse(r.createdAt())))
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

    // ---------- 召回解除 ----------

    /**
     * 召回解除申请：仅根召回记录为 ACTIVE（批次处于 RECALLED）的批次可申请。
     * 申请含召回版本、纠正措施与复检批次集合（规范化：去空白、去重、字典序排序）；
     * releaseKey 全局唯一，同键同参重放返回原申请当前状态，同键改参返回 409；
     * 校验失败不落库、不占键。已销毁（非 RECALLED 终态）批次不可申请。
     */
    public StoredResponse applyRecallRelease(String batchKey, String actorId,
                                             RecallReleaseApplyRequest req) {
        if (actorId == null || actorId.isBlank()) {
            throw ApiException.badRequest("X-Actor-Id 不能为空");
        }
        String actor = actorId.trim();
        List<String> normalized = normalizeBatchSet(req.reinspectionBatches());
        String fingerprint = fingerprint("release-apply", batchKey, actor, req.releaseKey(),
                String.valueOf(req.recallVersion()), req.correctiveAction(),
                String.join(",", normalized));
        return executeIdempotent(CMD_RELEASE_APPLY, req.commandKey(), fingerprint, () -> {
            BatchRepository.BatchRow batch = repo.findBatchForUpdate(batchKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
            // releaseKey 重放：同参返回原申请当前状态，改参 409
            var existing = repo.findRelease(req.releaseKey());
            if (existing.isPresent()) {
                BatchRepository.ReleaseRow row = existing.get();
                boolean sameContent = row.batchKey().equals(batchKey)
                        && row.recallVersion() == req.recallVersion()
                        && row.correctiveAction().equals(req.correctiveAction())
                        && row.reinspectionBatches().equals(String.join(",", normalized))
                        && row.applicant().equals(actor);
                if (!sameContent) {
                    throw ApiException.conflict("releaseKey 已以不同参数使用: " + req.releaseKey());
                }
                return new StoredResponse(200, toJson(toReleaseResponse(row)));
            }
            BatchRepository.RecallRow recall = activeRecallOf(batch)
                    .orElseThrow(() -> ApiException.conflict("批次状态 " + batch.status()
                            + " 无 ACTIVE 召回记录，不允许申请解除"));
            if (recall.version() != req.recallVersion()) {
                throw ApiException.unprocessable("召回版本不匹配：当前 ACTIVE 召回代次为 "
                        + recall.version() + "，申请为第 " + req.recallVersion() + " 代");
            }
            // 申报复检集合必须等于当前血缘闭包（根批次+全部受影响后代）；
            // 批准时仍按最终血缘闭包复核，防止申请后血缘新增未知来源
            assertDeclaredMatchesClosure(batchKey, normalized);
            repo.findPendingRelease(batchKey).ifPresent(p -> {
                throw ApiException.conflict("批次已存在待批准的解除申请: " + p.releaseKey());
            });
            String now = now();
            BatchRepository.ReleaseRow row = new BatchRepository.ReleaseRow(0L, batchKey,
                    req.releaseKey(), req.commandKey(), req.recallVersion(), req.correctiveAction(),
                    String.join(",", normalized), actor, "PENDING", null, null, now);
            repo.insertRelease(row);
            return new StoredResponse(201, toJson(toReleaseResponse(row)));
        });
    }

    /**
     * 提交复检：仅处于召回上下文（自身或任一祖先存在 ACTIVE 召回）的批次可提交，
     * 复检归属血缘链上最顶层 ACTIVE 召回的（根批次, 召回代次）。复检仅记录证据，
     * 不改写批次状态；同一批次同一代次同一检验项仅一条，同内容重放返回原结果，
     * 不同内容返回 409。先锁根召回批次再锁本批，与召回/解除批准按提交顺序裁决。
     */
    public StoredResponse submitReinspection(String batchKey, ReinspectionRequest req) {
        String fingerprint = fingerprint("reinspection", batchKey, req.testItem(),
                req.outcome().name(), req.inspector());
        return executeIdempotent(CMD_REINSPECTION, req.commandKey(), fingerprint, () -> {
            repo.findBatch(batchKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
            String rootKey = topmostRecalledKey(batchKey)
                    .orElseThrow(() -> ApiException.conflict(
                            "批次未处于召回上下文，无需复检: " + batchKey));
            BatchRepository.BatchRow root = repo.findBatchForUpdate(rootKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + rootKey));
            if (!BatchStatus.RECALLED.name().equals(root.status())) {
                throw ApiException.conflict("召回上下文已并发解除，请重新提交: " + rootKey);
            }
            if (!rootKey.equals(batchKey)) {
                repo.findBatchForUpdate(batchKey);
            }
            BatchRepository.RecallRow recall = repo.findRecall(rootKey)
                    .filter(r -> "ACTIVE".equals(r.status()))
                    .orElseThrow(() -> ApiException.conflict("召回上下文已并发解除，请重新提交: " + rootKey));
            List<String> required = repo.findRequiredTests(batchKey);
            if (!required.contains(req.testItem())) {
                throw ApiException.unprocessable("检验项不属于该批次必做项: " + req.testItem());
            }
            var existing = repo.findReinspection(batchKey, rootKey, recall.version(), req.testItem());
            if (existing.isPresent()) {
                BatchRepository.ReinspectionRow row = existing.get();
                boolean sameContent = row.outcome().equals(req.outcome().name())
                        && row.inspector().equals(req.inspector());
                if (!sameContent) {
                    throw ApiException.conflict("该检验项已存在不同内容的复检记录: " + req.testItem());
                }
                return new StoredResponse(200, toJson(toReinspectionResponse(row)));
            }
            String now = now();
            BatchRepository.ReinspectionRow row = new BatchRepository.ReinspectionRow(0L, batchKey,
                    rootKey, recall.version(), req.testItem(), req.outcome().name(),
                    req.inspector(), req.commandKey(), now);
            repo.insertReinspection(row);
            return new StoredResponse(201, toJson(toReinspectionResponse(row)));
        });
    }

    /**
     * 批准召回解除：按最终血缘闭包预校验——申报复检集合等于闭包（血缘未新增未知来源）、
     * 闭包内无未决隔离（QUARANTINED）、该批次及全部受影响后代均完成合格复检；
     * 任一失败 422 且所有召回状态与放行资格保持不变（同事务回滚，不留半成品）。
     * 批准后仅解除申请所覆盖召回代次并写入不可变评审快照；后代历史召回记录不删除。
     * releaseKey 指纹含召回版本、规范化复检集合、措施和审批人：已批准时同审批人重放
     * 返回首次快照，换审批人返回 409。
     */
    public StoredResponse approveRecallRelease(String batchKey, String releaseKey, String actorId,
                                               ApproveReleaseRequest req) {
        if (actorId == null || actorId.isBlank()) {
            throw ApiException.badRequest("X-Actor-Id 不能为空");
        }
        String approver = actorId.trim();
        String fingerprint = fingerprint("release-approve", batchKey, releaseKey, approver);
        return executeIdempotent(CMD_RELEASE_APPROVE, req.commandKey(), fingerprint, () -> {
            BatchRepository.BatchRow batch = repo.findBatchForUpdate(batchKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
            // 行锁后重查命令快照：并发同键请求在锁等待期间可能已由对方提交
            var logged = loggedResponse(CMD_RELEASE_APPROVE, req.commandKey(), fingerprint);
            if (logged.isPresent()) {
                return logged.get();
            }
            BatchRepository.ReleaseRow application = repo.findRelease(releaseKey)
                    .filter(r -> r.batchKey().equals(batchKey))
                    .orElseThrow(() -> ApiException.notFound("解除申请不存在: " + releaseKey));
            if ("APPROVED".equals(application.status())) {
                if (!application.approver().equals(approver)) {
                    throw ApiException.conflict("releaseKey 已由其他审批人批准: " + releaseKey);
                }
                BatchRepository.SnapshotRow snapshot = repo.findSnapshot(releaseKey)
                        .orElseThrow(() -> new IllegalStateException("已批准申请缺少快照: " + releaseKey));
                return new StoredResponse(200, toJson(toSnapshotResponse(snapshot)));
            }
            // 锁定最终血缘闭包全部批次（按业务键排序），与召回/复检/拆分互斥，按提交顺序裁决
            List<String> descendants = descendantKeys(batchKey);
            Collections.sort(descendants);
            for (String key : descendants) {
                repo.findBatchForUpdate(key);
            }
            List<String> closureAll = new ArrayList<>(descendants.size() + 1);
            closureAll.add(batchKey);
            closureAll.addAll(descendants);
            // 校验 1：根召回记录仍为 ACTIVE 且代次与申请一致
            BatchRepository.RecallRow recall = activeRecallOf(batch)
                    .orElseThrow(() -> ApiException.conflict("根召回记录已不是 ACTIVE，不允许批准解除"));
            if (recall.version() != application.recallVersion()) {
                throw ApiException.conflict("召回代次已变化：申请针对第 " + application.recallVersion()
                        + " 代，当前 ACTIVE 为第 " + recall.version() + " 代");
            }
            // 校验 2：申报复检集合必须等于最终血缘闭包（血缘未新增未知来源）
            assertDeclaredMatchesClosure(batchKey,
                    Arrays.asList(application.reinspectionBatches().split(",")));
            List<String> closureSorted = closureAll.stream().sorted().toList();
            // 校验 3：闭包内无未决隔离批次
            Map<String, BatchRepository.BatchRow> rows = new HashMap<>();
            List<String> quarantined = new ArrayList<>();
            for (String key : closureAll) {
                BatchRepository.BatchRow row = key.equals(batchKey) ? batch
                        : repo.findBatch(key)
                                .orElseThrow(() -> ApiException.notFound("批次不存在: " + key));
                rows.put(key, row);
                if (BatchStatus.QUARANTINED.name().equals(row.status())) {
                    quarantined.add(key);
                }
            }
            if (!quarantined.isEmpty()) {
                throw ApiException.unprocessable("血缘闭包内存在未决隔离批次: " + quarantined);
            }
            // 校验 4：闭包内全部批次完成合格复检（全部必做项均有 PASS 复检）
            Map<String, List<String>> requiredByKey = new HashMap<>();
            Map<String, List<String>> passedByKey = new HashMap<>();
            List<String> gaps = new ArrayList<>();
            for (String key : closureAll) {
                List<String> required = repo.findRequiredTests(key);
                List<String> passed = passedReinspectionItems(key, batchKey, recall.version());
                requiredByKey.put(key, required);
                passedByKey.put(key, passed);
                List<String> missing = new ArrayList<>(required);
                missing.removeAll(passed);
                if (!missing.isEmpty()) {
                    gaps.add(key + " 缺 " + missing);
                }
            }
            if (!gaps.isEmpty()) {
                throw ApiException.unprocessable("存在未完成合格复检的批次: " + gaps);
            }
            // 全部校验通过：仅解除申请所覆盖召回代次，恢复召回前状态，写入不可变评审快照
            String now = now();
            repo.liftRecall(batchKey, recall.version());
            repo.updateStatus(batchKey, recall.priorStatus());
            repo.approveRelease(releaseKey, approver, now);
            List<RecallReleaseSnapshotResponse.SnapshotEntry> entries = new ArrayList<>();
            for (String key : closureSorted) {
                entries.add(new RecallReleaseSnapshotResponse.SnapshotEntry(key,
                        BatchStatus.valueOf(rows.get(key).status()),
                        requiredByKey.get(key), passedByKey.get(key)));
            }
            repo.insertSnapshot(new BatchRepository.SnapshotRow(0L, releaseKey, batchKey,
                    recall.version(), application.correctiveAction(), approver,
                    String.join(",", closureSorted), toJson(entries), now));
            RecallReleaseSnapshotResponse body = new RecallReleaseSnapshotResponse(releaseKey,
                    batchKey, recall.version(), application.correctiveAction(), approver,
                    closureSorted, entries, Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 血缘影响查询：根批次最新一代召回（含已解除）的最终血缘闭包，
     * 每项含批次自身状态与针对该召回代次的合格复检进度。
     */
    public RecallImpactResponse recallImpact(String batchKey) {
        repo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        BatchRepository.RecallRow recall = repo.findRecall(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次无召回记录: " + batchKey));
        List<RecallImpactResponse.ImpactEntry> affected = new ArrayList<>();
        for (String key : closureWithSelf(batchKey)) {
            BatchRepository.BatchRow row = repo.findBatch(key)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + key));
            List<String> missing = missingReinspectionItems(key, batchKey, recall.version());
            affected.add(new RecallImpactResponse.ImpactEntry(key,
                    BatchStatus.valueOf(row.status()), missing.isEmpty(), missing));
        }
        return new RecallImpactResponse(batchKey, recall.version(), recall.status(), affected);
    }

    /**
     * 复检缺口查询：当前 ACTIVE 召回下，最终血缘闭包内每个批次仍缺合格复检的必做检验项。
     */
    public ReinspectionGapsResponse reinspectionGaps(String batchKey) {
        BatchRepository.BatchRow batch = repo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        BatchRepository.RecallRow recall = activeRecallOf(batch)
                .orElseThrow(() -> ApiException.conflict("批次当前无 ACTIVE 召回: " + batchKey));
        List<ReinspectionGapsResponse.GapEntry> gaps = new ArrayList<>();
        for (String key : closureWithSelf(batchKey)) {
            gaps.add(new ReinspectionGapsResponse.GapEntry(key,
                    missingReinspectionItems(key, batchKey, recall.version())));
        }
        return new ReinspectionGapsResponse(batchKey, recall.version(), gaps);
    }

    /**
     * 某批次全部召回解除申请（含待批准与已批准），按申请顺序返回。
     */
    public List<RecallReleaseResponse> listRecallReleases(String batchKey) {
        repo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        return repo.findReleases(batchKey).stream().map(this::toReleaseResponse).toList();
    }

    /**
     * 解除评审快照查询：仅已批准的申请存在快照；待批准申请返回 409。
     */
    public RecallReleaseSnapshotResponse recallReleaseSnapshot(String batchKey, String releaseKey) {
        repo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        repo.findRelease(releaseKey)
                .filter(r -> r.batchKey().equals(batchKey))
                .orElseThrow(() -> ApiException.notFound("解除申请不存在: " + releaseKey));
        BatchRepository.SnapshotRow snapshot = repo.findSnapshot(releaseKey)
                .orElseThrow(() -> ApiException.conflict("解除申请尚未批准，无评审快照: " + releaseKey));
        return toSnapshotResponse(snapshot);
    }

    /**
     * 批次的 ACTIVE 召回记录；批次不处于 RECALLED 或最新代已解除时为空。
     */
    private Optional<BatchRepository.RecallRow> activeRecallOf(BatchRepository.BatchRow batch) {
        if (!BatchStatus.RECALLED.name().equals(batch.status())) {
            return Optional.empty();
        }
        return repo.findRecall(batch.batchKey()).filter(r -> "ACTIVE".equals(r.status()));
    }

    /**
     * 血缘链上（自身及各级祖先）最顶层的 RECALLED 批次业务键；无召回上下文时为空。
     */
    private Optional<String> topmostRecalledKey(String batchKey) {
        Map<String, String> parentOf = childToParent();
        Set<String> recalled = new HashSet<>(repo.findRecalledKeys());
        String topmost = recalled.contains(batchKey) ? batchKey : null;
        String current = batchKey;
        while (parentOf.containsKey(current)) {
            current = parentOf.get(current);
            if (recalled.contains(current)) {
                topmost = current;
            }
        }
        return Optional.ofNullable(topmost);
    }

    /**
     * 最终血缘闭包：根批次自身 + 全部受影响后代（按血缘创建顺序广度优先，根批次在首位）。
     */
    private List<String> closureWithSelf(String batchKey) {
        List<String> closure = new ArrayList<>();
        closure.add(batchKey);
        closure.addAll(descendantKeys(batchKey));
        return closure;
    }

    /**
     * 校验申报复检集合（须已规范化排序）等于根批次当前血缘闭包；
     * 不一致时 422 并区分两个方向：闭包内未申报（含血缘新增未知来源）与闭包外申报。
     */
    private void assertDeclaredMatchesClosure(String batchKey, List<String> declaredSorted) {
        List<String> closureSorted = closureWithSelf(batchKey).stream().sorted().toList();
        if (!declaredSorted.equals(closureSorted)) {
            List<String> undeclared = new ArrayList<>(closureSorted);
            undeclared.removeAll(declaredSorted);
            List<String> outside = new ArrayList<>(declaredSorted);
            outside.removeAll(closureSorted);
            throw ApiException.unprocessable("复检批次集合与血缘闭包不一致：闭包内未申报 "
                    + undeclared + "，闭包外申报 " + outside);
        }
    }

    /**
     * 某批次在指定召回上下文下仍缺合格（PASS）复检的必做检验项。
     */
    private List<String> missingReinspectionItems(String batchKey, String rootKey, int recallVersion) {
        List<String> missing = new ArrayList<>(repo.findRequiredTests(batchKey));
        missing.removeAll(passedReinspectionItems(batchKey, rootKey, recallVersion));
        return missing;
    }

    /**
     * 某批次在指定召回上下文下结论为 PASS 的复检检验项。
     */
    private List<String> passedReinspectionItems(String batchKey, String rootKey, int recallVersion) {
        return repo.findReinspections(batchKey, rootKey, recallVersion).stream()
                .filter(r -> TestOutcome.PASS.name().equals(r.outcome()))
                .map(BatchRepository.ReinspectionRow::testItem)
                .toList();
    }

    /**
     * 复检批次集合规范化：去空白、去重、按字典序排序。
     */
    private List<String> normalizeBatchSet(List<String> keys) {
        return keys.stream().map(String::trim).distinct().sorted().toList();
    }

    private RecallReleaseResponse toReleaseResponse(BatchRepository.ReleaseRow row) {
        return new RecallReleaseResponse(row.releaseKey(), row.batchKey(), row.recallVersion(),
                row.correctiveAction(), Arrays.asList(row.reinspectionBatches().split(",")),
                row.applicant(), row.status(), row.approver(),
                row.decidedAt() == null ? null : Instant.parse(row.decidedAt()),
                Instant.parse(row.createdAt()));
    }

    private ReinspectionResponse toReinspectionResponse(BatchRepository.ReinspectionRow row) {
        return new ReinspectionResponse(row.batchKey(), row.rootKey(), row.recallVersion(),
                row.testItem(), TestOutcome.valueOf(row.outcome()), row.inspector(),
                Instant.parse(row.createdAt()));
    }

    private RecallReleaseSnapshotResponse toSnapshotResponse(BatchRepository.SnapshotRow row) {
        List<RecallReleaseSnapshotResponse.SnapshotEntry> entries;
        try {
            entries = objectMapper.readValue(row.detail(), new TypeReference<>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("快照明细反序列化失败: " + row.releaseKey(), e);
        }
        return new RecallReleaseSnapshotResponse(row.releaseKey(), row.batchKey(),
                row.recallVersion(), row.correctiveAction(), row.approver(),
                Arrays.asList(row.closureBatches().split(",")), entries,
                Instant.parse(row.createdAt()));
    }
}
