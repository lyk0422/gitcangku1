package com.example.starter.batch;

import com.example.starter.batch.dto.ApprovalResponse;
import com.example.starter.batch.dto.ApproveRequest;
import com.example.starter.batch.dto.BatchHistoryResponse;
import com.example.starter.batch.dto.BatchResponse;
import com.example.starter.batch.dto.CreateBatchRequest;
import com.example.starter.batch.dto.LabelDiagnosticResponse;
import com.example.starter.batch.dto.LineageEntryResponse;
import com.example.starter.batch.dto.PackingDetailResponse;
import com.example.starter.batch.dto.PackingPlanResponse;
import com.example.starter.batch.dto.RecallRequest;
import com.example.starter.batch.dto.RecallResponse;
import com.example.starter.batch.dto.RegisterPackingRequest;
import com.example.starter.batch.dto.ReleaseSnapshotResponse;
import com.example.starter.batch.dto.SealBoxesRequest;
import com.example.starter.batch.dto.SealBoxesResponse;
import com.example.starter.batch.dto.SealEventResponse;
import com.example.starter.batch.dto.SealWindowResponse;
import com.example.starter.batch.dto.SplitRequest;
import com.example.starter.batch.dto.SplitResponse;
import com.example.starter.batch.dto.SubmitTestRequest;
import com.example.starter.batch.dto.TestResultResponse;
import com.example.starter.batch.dto.VoidSealRequest;
import com.example.starter.batch.dto.VoidSealResponse;
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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

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
    private static final String CMD_REGISTER_PACKING = "REGISTER_PACKING";
    private static final String CMD_SEAL_BOXES = "SEAL_BOXES";
    private static final String CMD_VOID_SEAL = "VOID_SEAL";

    private static final String SEAL_STATUS_SEALED = "SEALED";
    private static final String SEAL_STATUS_VOIDED = "VOIDED";

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
                // 放行最终校验：已登记包装计划时，全部计划数量必须封箱完成且标签数与封箱数一致，
                // 校验通过后与批准在同一事务固化放行快照；任一不满足整笔回滚，查不到半成品。
                freezeReleaseSnapshotIfPlanned(batchKey, now);
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
        PackingDetailResponse packing = repo.findPackingPlan(batchKey).isPresent()
                ? packingDetail(batchKey, null, null)
                : null;
        return new BatchHistoryResponse(toBatchResponse(batch), tests, approvals, recall, packing);
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

    // ==================== 批次标签核销（包装计划、封箱、作废、放行快照、诊断） ====================

    /**
     * 登记包装计划：计划数量为正整数，号段 [start,end] 两端含且起点不得大于终点。
     * 每个批次至多登记一次，重复登记返回 409；批次不存在返回 404；
     * 已召回/已放行批次禁止登记返回 409。号段容量无需等于计划数量：一标签对应一封箱，
     * 每箱数量为正整数，放行时要求数量之和恰为计划数量且已用标签数等于封箱数。
     */
    public StoredResponse registerPacking(String batchKey, RegisterPackingRequest req) {
        if (req.labelRangeEnd() < req.labelRangeStart()) {
            throw ApiException.badRequest("labelRangeEnd 不得小于 labelRangeStart");
        }
        String fingerprint = fingerprint("registerPacking", batchKey,
                Long.toString(req.plannedQuantity()), Long.toString(req.labelRangeStart()),
                Long.toString(req.labelRangeEnd()));
        return executeIdempotent(CMD_REGISTER_PACKING, req.commandKey(), fingerprint, () -> {
            BatchRepository.BatchRow batch = repo.findBatchForUpdate(batchKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
            var logged = loggedResponse(CMD_REGISTER_PACKING, req.commandKey(), fingerprint);
            if (logged.isPresent()) {
                return logged.get();
            }
            if (BatchStatus.RECALLED.name().equals(batch.status())) {
                throw ApiException.conflict("批次已召回，禁止登记包装计划");
            }
            if (BatchStatus.RELEASED.name().equals(batch.status())
                    || BatchStatus.SPLIT.name().equals(batch.status())) {
                throw ApiException.conflict("批次已放行，不能补登记包装计划: " + batchKey);
            }
            if (repo.findPackingPlan(batchKey).isPresent()) {
                throw ApiException.conflict("批次已登记包装计划，计划数量与号段不可修改: " + batchKey);
            }
            String now = now();
            repo.insertPackingPlan(new BatchRepository.PackingPlanRow(batchKey,
                    req.plannedQuantity(), req.labelRangeStart(), req.labelRangeEnd(), now));
            PackingPlanResponse body = new PackingPlanResponse(batchKey, req.plannedQuantity(),
                    req.labelRangeStart(), req.labelRangeEnd(), 0L, 0L,
                    req.plannedQuantity(), Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 批量封箱：先按完整最终集合（既有活跃占用 + 本次全部条目）统一校验，
     * 任一条目越界/重复/超数量/状态非法则整次回滚，不留下部分占用。
     * 并发同标签由 box_seal_active 与 label_binding 唯一约束保证最多一次成功。
     */
    public StoredResponse sealBoxes(String batchKey, SealBoxesRequest req) {
        List<SealBoxesRequest.Box> boxes = req.boxes();
        Set<String> sealKeys = new HashSet<>();
        Set<Long> labelNos = new HashSet<>();
        List<String> parts = new ArrayList<>();
        parts.add("sealBoxes");
        parts.add(batchKey);
        for (SealBoxesRequest.Box box : boxes) {
            if (!sealKeys.add(box.sealKey())) {
                throw ApiException.conflict("请求内 sealKey 重复: " + box.sealKey());
            }
            if (!labelNos.add(box.labelNo())) {
                throw ApiException.unprocessable("请求内标签号重复: " + box.labelNo());
            }
            parts.add(box.sealKey());
            parts.add(Long.toString(box.labelNo()));
            parts.add(Long.toString(box.quantity()));
        }
        String fingerprint = fingerprint(parts.toArray(new String[0]));
        // 同批次并发由 batch 行 FOR UPDATE 串行化；跨批次同标签号并发由 label_binding 主键兜底，
        // 负者事务回滚后在 executeIdempotent 的重试中重新读到绑定，转为“已关联其他批次”409。
        return executeIdempotent(CMD_SEAL_BOXES, req.commandKey(), fingerprint, () -> {
            BatchRepository.BatchRow batch = repo.findBatchForUpdate(batchKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
            var logged = loggedResponse(CMD_SEAL_BOXES, req.commandKey(), fingerprint);
            if (logged.isPresent()) {
                return logged.get();
            }
            BatchStatus status = BatchStatus.valueOf(batch.status());
            if (status == BatchStatus.RECALLED) {
                throw ApiException.conflict("批次已召回，禁止封箱");
            }
            if (status == BatchStatus.RELEASED || status == BatchStatus.SPLIT) {
                throw ApiException.conflict("批次已放行，禁止再封箱；标签差异只能按召回流程处置");
            }
            BatchRepository.PackingPlanRow plan = repo.findPackingPlan(batchKey)
                    .orElseThrow(() -> ApiException.unprocessable("批次尚未登记包装计划，不能封箱"));

            List<BatchRepository.ActiveSealRow> active = repo.findActiveSeals(batchKey);
            Map<String, BatchRepository.ActiveSealRow> activeBySeal = active.stream()
                    .collect(Collectors.toMap(BatchRepository.ActiveSealRow::sealKey, r -> r));
            Map<Long, BatchRepository.ActiveSealRow> activeByLabel = active.stream()
                    .collect(Collectors.toMap(BatchRepository.ActiveSealRow::labelNo, r -> r));

            // 第一阶段：仅校验完整最终集合（既有活跃占用 + 本次全部条目），不写任何数据。
            long currentQuantity = active.stream()
                    .mapToLong(BatchRepository.ActiveSealRow::quantity).sum();
            long requestQuantity = 0L;
            for (SealBoxesRequest.Box box : boxes) {
                if (box.labelNo() < plan.labelRangeStart() || box.labelNo() > plan.labelRangeEnd()) {
                    throw ApiException.unprocessable("标签号越界: " + box.labelNo()
                            + "，要求落在 [" + plan.labelRangeStart() + ", " + plan.labelRangeEnd() + "]");
                }
                var binding = repo.findLabelBinding(box.labelNo());
                if (binding.isPresent() && !binding.get().batchKey().equals(batchKey)) {
                    throw ApiException.conflict("标签号 " + box.labelNo()
                            + " 已关联其他批次 " + binding.get().batchKey() + "，每个标签只能关联一个批次");
                }
                if (activeBySeal.containsKey(box.sealKey())) {
                    throw ApiException.conflict("封箱键已存在活跃封箱，不能重复封箱: " + box.sealKey());
                }
                if (activeByLabel.containsKey(box.labelNo())) {
                    throw ApiException.unprocessable("标签号已被本批次其他封箱占用: " + box.labelNo());
                }
                requestQuantity += box.quantity();
            }
            long finalQuantity = currentQuantity + requestQuantity;
            if (finalQuantity > plan.plannedQuantity()) {
                throw ApiException.unprocessable("封箱数量超过计划数量: 实际累计 " + finalQuantity
                        + "，要求上限 " + plan.plannedQuantity()
                        + "，超出 " + (finalQuantity - plan.plannedQuantity()));
            }

            // 第二阶段：校验通过后统一写入；任一唯一约束冲突上抛，整事务回滚不留部分占用。
            String now = now();
            List<SealEventResponse> sealed = new ArrayList<>(boxes.size());
            for (SealBoxesRequest.Box box : boxes) {
                int version = repo.findLatestSealEvent(batchKey, box.sealKey())
                        .map(e -> e.version() + 1).orElse(1);
                repo.insertSealEvent(new BatchRepository.SealEventRow(0L, batchKey, box.sealKey(),
                        version, box.labelNo(), box.quantity(), SEAL_STATUS_SEALED,
                        null, now, null));
                repo.insertActiveSeal(new BatchRepository.ActiveSealRow(batchKey, box.labelNo(),
                        box.sealKey(), version, box.quantity(), now));
                // 全局标签绑定只在标签首次成功封箱时落库，作废不解除；唯一主键兜底并发跨批同号。
                if (repo.findLabelBinding(box.labelNo()).isEmpty()) {
                    repo.insertLabelBinding(new BatchRepository.LabelBindingRow(box.labelNo(),
                            batchKey, now));
                }
                sealed.add(new SealEventResponse(box.sealKey(), version, box.labelNo(),
                        box.quantity(), SEAL_STATUS_SEALED, null,
                        Instant.parse(now), null));
            }

            long sealedQuantity = repo.sumActiveQuantity(batchKey);
            List<BatchRepository.ActiveSealRow> finalActive = repo.findActiveSeals(batchKey);
            SealBoxesResponse body = new SealBoxesResponse(batchKey, sealedQuantity,
                    plan.plannedQuantity(), finalActive.size(), finalActive.size(),
                    plan.plannedQuantity() - sealedQuantity,
                    sealed.stream().sorted(Comparator.comparingLong(SealEventResponse::labelNo)).toList());
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 作废封箱：仅未放行（无放行快照）批次允许；写入作废原因，释放数量与标签活跃占用。
     * 作废后同 sealKey 可重封（version 递增），历史版本永久保留。
     */
    public StoredResponse voidSeal(String batchKey, String sealKey, VoidSealRequest req) {
        String fingerprint = fingerprint("voidSeal", batchKey, sealKey, req.reason());
        return executeIdempotent(CMD_VOID_SEAL, req.commandKey(), fingerprint, () -> {
            BatchRepository.BatchRow batch = repo.findBatchForUpdate(batchKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
            var logged = loggedResponse(CMD_VOID_SEAL, req.commandKey(), fingerprint);
            if (logged.isPresent()) {
                return logged.get();
            }
            if (repo.findReleaseSnapshot(batchKey).isPresent()) {
                throw ApiException.conflict("批次已放行，快照不可改写，封箱不能作废；差异只能按召回流程处置");
            }
            if (BatchStatus.RECALLED.name().equals(batch.status())) {
                throw ApiException.conflict("批次已召回，封箱不能作废");
            }
            BatchRepository.ActiveSealRow active = repo.findActiveSealByKey(batchKey, sealKey)
                    .orElseThrow(() -> {
                        BatchRepository.SealEventRow last = repo.findLatestSealEvent(batchKey, sealKey)
                                .orElse(null);
                        if (last == null) {
                            return ApiException.notFound("封箱不存在: " + sealKey);
                        }
                        return ApiException.conflict("封箱已作废，不能重复作废: " + sealKey);
                    });
            String now = now();
            int updated = repo.voidSealEvent(batchKey, sealKey, active.version(), req.reason(), now);
            if (updated != 1) {
                throw ApiException.conflict("封箱状态已变化，作废失败: " + sealKey);
            }
            repo.deleteActiveSeal(batchKey, sealKey);
            List<BatchRepository.ActiveSealRow> remaining = repo.findActiveSeals(batchKey);
            long sealedQuantity = remaining.stream().mapToLong(BatchRepository.ActiveSealRow::quantity).sum();
            VoidSealResponse body = new VoidSealResponse(batchKey, sealKey, active.version(),
                    req.reason(), sealedQuantity, remaining.size(), Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 包装核销明细（只读）：计划、活跃封箱、全部历史版本、放行快照、差异诊断，
     * 可选 UTC 左闭右开时间窗 [from,to) 统计。读取不改变任何状态。
     */
    public PackingDetailResponse packingDetail(String batchKey, Instant from, Instant to) {
        BatchRepository.BatchRow batch = repo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        BatchRepository.PackingPlanRow planRow = repo.findPackingPlan(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次尚未登记包装计划: " + batchKey));
        List<BatchRepository.ActiveSealRow> activeRows = repo.findActiveSeals(batchKey);
        List<BatchRepository.SealEventRow> eventRows = repo.findSealEvents(batchKey);

        long sealedQuantity = activeRows.stream().mapToLong(BatchRepository.ActiveSealRow::quantity).sum();
        PackingPlanResponse plan = new PackingPlanResponse(batchKey, planRow.plannedQuantity(),
                planRow.labelRangeStart(), planRow.labelRangeEnd(), sealedQuantity,
                activeRows.size(), planRow.plannedQuantity() - sealedQuantity,
                Instant.parse(planRow.createdAt()));

        List<SealEventResponse> activeSeals = activeRows.stream()
                .sorted(Comparator.comparingLong(BatchRepository.ActiveSealRow::labelNo))
                .map(a -> new SealEventResponse(a.sealKey(), a.version(), a.labelNo(), a.quantity(),
                        SEAL_STATUS_SEALED, null, Instant.parse(a.sealedAt()), null))
                .toList();
        List<SealEventResponse> history = eventRows.stream().map(this::toSealEventResponse).toList();

        ReleaseSnapshotResponse snapshot = repo.findReleaseSnapshot(batchKey)
                .map(r -> toSnapshotResponse(batchKey, r)).orElse(null);
        LabelDiagnosticResponse diagnostic = buildDiagnostic(planRow, activeRows, snapshot);

        SealWindowResponse window = null;
        if (from != null || to != null) {
            if (from != null && to != null && !to.isAfter(from)) {
                throw ApiException.badRequest("时间窗要求 to 晚于 from（左闭右开 [from, to)）");
            }
            List<BatchRepository.SealEventRow> inWindow = eventRows.stream()
                    .filter(e -> from == null || !Instant.parse(e.sealedAt()).isBefore(from))
                    .filter(e -> to == null || Instant.parse(e.sealedAt()).isBefore(to))
                    .toList();
            Long duration = (from != null && to != null)
                    ? Duration.between(from, to).getSeconds() : null;
            window = new SealWindowResponse(from, to, duration, inWindow.size(),
                    inWindow.stream().mapToLong(BatchRepository.SealEventRow::quantity).sum());
        }
        return new PackingDetailResponse(batchKey, plan, activeSeals, history, snapshot,
                diagnostic, window);
    }

    /**
     * 只读差异诊断明细入口（与 packingDetail 同源，便于直接查询诊断）。
     */
    public LabelDiagnosticResponse labelDiagnostic(String batchKey) {
        repo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        BatchRepository.PackingPlanRow planRow = repo.findPackingPlan(batchKey)
                .orElseThrow(() -> ApiException.unprocessable("批次尚未登记包装计划: " + batchKey));
        List<BatchRepository.ActiveSealRow> activeRows = repo.findActiveSeals(batchKey);
        ReleaseSnapshotResponse snapshot = repo.findReleaseSnapshot(batchKey)
                .map(r -> toSnapshotResponse(batchKey, r)).orElse(null);
        return buildDiagnostic(planRow, activeRows, snapshot);
    }

    /**
     * 构造差异诊断：数量差额、标签数与封箱数差额、越界标签，以及与放行快照的标签集合差异。
     */
    private LabelDiagnosticResponse buildDiagnostic(BatchRepository.PackingPlanRow plan,
                                                    List<BatchRepository.ActiveSealRow> active,
                                                    ReleaseSnapshotResponse snapshot) {
        String batchKey = plan.batchKey();
        long sealedQuantity = active.stream().mapToLong(BatchRepository.ActiveSealRow::quantity).sum();
        long quantityDifference = sealedQuantity - plan.plannedQuantity();
        List<Long> usedLabels = active.stream()
                .map(BatchRepository.ActiveSealRow::labelNo)
                .sorted().toList();
        long distinctLabelCount = new HashSet<>(usedLabels).size();
        long labelSealDifference = distinctLabelCount - active.size();
        List<Long> outside = usedLabels.stream()
                .filter(l -> l < plan.labelRangeStart() || l > plan.labelRangeEnd())
                .toList();
        boolean reconciled = quantityDifference == 0L
                && labelSealDifference == 0L
                && outside.isEmpty();
        Boolean snapshotConsistent = null;
        List<Long> missing = null;
        List<Long> extra = null;
        if (snapshot != null) {
            Set<Long> frozen = new LinkedHashSet<>(snapshot.usedLabels());
            Set<Long> current = new LinkedHashSet<>(usedLabels);
            missing = frozen.stream().filter(l -> !current.contains(l)).sorted().toList();
            extra = current.stream().filter(l -> !frozen.contains(l)).sorted().toList();
            snapshotConsistent = missing.isEmpty() && extra.isEmpty()
                    && snapshot.sealedQuantity() == sealedQuantity;
        }
        return new LabelDiagnosticResponse(reconciled, plan.plannedQuantity(), sealedQuantity,
                quantityDifference, active.size(), usedLabels.size(), labelSealDifference,
                outside, snapshotConsistent, missing, extra);
    }

    /**
     * 放行最终校验与快照固化：计划数量必须全部完成，且号段内已用标签数等于活跃封箱数。
     * 校验与快照写入、第二批准、状态翻转在同一事务；失败整体回滚。
     */
    private void freezeReleaseSnapshotIfPlanned(String batchKey, String now) {
        var planOpt = repo.findPackingPlan(batchKey);
        if (planOpt.isEmpty()) {
            return;
        }
        BatchRepository.PackingPlanRow plan = planOpt.get();
        List<BatchRepository.ActiveSealRow> active = repo.findActiveSeals(batchKey);
        long sealedQuantity = active.stream().mapToLong(BatchRepository.ActiveSealRow::quantity).sum();
        if (sealedQuantity != plan.plannedQuantity()) {
            throw ApiException.unprocessable("放行前必须完成全部计划数量封箱: 实际 " + sealedQuantity
                    + "，要求 " + plan.plannedQuantity()
                    + "，差额 " + (plan.plannedQuantity() - sealedQuantity));
        }
        long distinctLabels = active.stream().map(BatchRepository.ActiveSealRow::labelNo).distinct().count();
        if (distinctLabels != active.size()) {
            throw ApiException.unprocessable("已用标签数与封箱数不一致: 标签数 " + distinctLabels
                    + "，封箱数 " + active.size());
        }
        List<BatchRepository.ActiveSealRow> ordered = active.stream()
                .sorted(Comparator.comparingLong(BatchRepository.ActiveSealRow::labelNo)).toList();
        List<Long> labels = ordered.stream().map(BatchRepository.ActiveSealRow::labelNo).toList();
        String usedLabelsCsv = labels.stream().map(String::valueOf).collect(Collectors.joining(","));
        repo.insertReleaseSnapshot(new BatchRepository.ReleaseSnapshotRow(batchKey,
                plan.plannedQuantity(), sealedQuantity, distinctLabels, active.size(),
                usedLabelsCsv, now));
        int seq = 1;
        for (BatchRepository.ActiveSealRow a : ordered) {
            repo.insertSnapshotSeal(batchKey, a.sealKey(), a.version(), a.labelNo(), a.quantity(),
                    a.sealedAt(), seq++);
        }
    }

    private SealEventResponse toSealEventResponse(BatchRepository.SealEventRow row) {
        return new SealEventResponse(row.sealKey(), row.version(), row.labelNo(), row.quantity(),
                row.status(), row.voidReason(), Instant.parse(row.sealedAt()),
                row.voidedAt() == null ? null : Instant.parse(row.voidedAt()));
    }

    private ReleaseSnapshotResponse toSnapshotResponse(String batchKey,
                                                       BatchRepository.ReleaseSnapshotRow row) {
        List<Long> labels = row.usedLabels().isBlank()
                ? List.of()
                : java.util.Arrays.stream(row.usedLabels().split(","))
                        .map(Long::valueOf).sorted().toList();
        List<ReleaseSnapshotResponse.FrozenSeal> seals = repo.findSnapshotSeals(batchKey).stream()
                .map(s -> new ReleaseSnapshotResponse.FrozenSeal(s.sealKey(), s.version(),
                        s.labelNo(), s.quantity(), Instant.parse(s.sealedAt())))
                .toList();
        return new ReleaseSnapshotResponse(batchKey, row.plannedQuantity(), row.sealedQuantity(),
                row.sealedQuantity() - row.plannedQuantity(), row.usedLabelCount(),
                row.sealCount(), labels, seals, Instant.parse(row.createdAt()));
    }
}
