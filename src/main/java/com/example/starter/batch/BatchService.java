package com.example.starter.batch;

import com.example.starter.batch.dto.ApprovalResponse;
import com.example.starter.batch.dto.ApproveRequest;
import com.example.starter.batch.dto.BatchHistoryResponse;
import com.example.starter.batch.dto.BatchResponse;
import com.example.starter.batch.dto.CompositionVersionResponse;
import com.example.starter.batch.dto.CreateBatchRequest;
import com.example.starter.batch.dto.LineageEntryResponse;
import com.example.starter.batch.dto.LineageSnapshotResponse;
import com.example.starter.batch.dto.MergeDiagnoseRequest;
import com.example.starter.batch.dto.MergeDiagnoseResponse;
import com.example.starter.batch.dto.MergeRequest;
import com.example.starter.batch.dto.MergeResponse;
import com.example.starter.batch.dto.RecallRequest;
import com.example.starter.batch.dto.RecallResponse;
import com.example.starter.batch.dto.ReviseCompositionRequest;
import com.example.starter.batch.dto.RiskResponse;
import com.example.starter.batch.dto.SplitRequest;
import com.example.starter.batch.dto.SplitResponse;
import com.example.starter.batch.dto.SubmitTestRequest;
import com.example.starter.batch.dto.TestResultResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
    private static final String CMD_ALLERGEN_REVISE = "ALLERGEN_REVISE";
    private static final String CMD_MERGE = "MERGE_BATCH";

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
            // 初始成分版本 v1：无过敏原、无隔离级别要求，待首次 PASS 检验后视为已检验
            repo.insertComposition(new BatchRepository.CompositionRow(0L, req.batchKey(), 1, "",
                    SegregationLevel.NONE.name(), now));
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
                    || status == BatchStatus.RECALLED || status == BatchStatus.SPLIT
                    || status == BatchStatus.MERGED) {
                throw ApiException.conflict("批次状态 " + status + " 不允许提交检验");
            }
            // 成分版本作用域：成分修订后需对新版本重新检验，旧版本结果不占用新版本的检验项
            BatchRepository.CompositionRow currentComp = repo.findLatestComposition(batchKey)
                    .orElseThrow(() -> new IllegalStateException("批次缺少成分版本: " + batchKey));
            List<String> required = repo.findRequiredTests(batchKey);
            if (!required.contains(req.testItem())) {
                throw ApiException.unprocessable("检验项不属于该批次必做项: " + req.testItem());
            }
            boolean itemAlreadyTested = repo.findTests(batchKey).stream()
                    .anyMatch(t -> t.testItem().equals(req.testItem())
                            && t.compositionVersion() == currentComp.version());
            if (itemAlreadyTested) {
                throw ApiException.conflict("该检验项在当前成分版本上已存在检验结果: " + req.testItem());
            }

            String now = now();
            repo.insertTest(new BatchRepository.TestRow(0L, batchKey, req.testKey(), req.testItem(),
                    req.result().name(), req.inspector(), currentComp.version(), now));

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
            if (status == BatchStatus.ALLERGEN_RISK) {
                return approveAllergenRisk(batchKey, actor, role, req);
            }
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
                // 放行门禁：最终血缘集合（自身 + 拆分/合批全部祖先）的当前成分版本须均已检验
                assertCompositionGate(batchKey);
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
            if (status != BatchStatus.RELEASED && status != BatchStatus.SPLIT
                    && status != BatchStatus.ALLERGEN_RISK) {
                throw ApiException.conflict("批次状态 " + status
                        + " 不允许召回，仅 RELEASED、ALLERGEN_RISK 或 SPLIT 可召回");
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
     * 当前可用批次：排除已召回（RECALLED）、已拆分（SPLIT）、已合批（MERGED）批次，
     * 以及任一祖先（拆分父批或合批来源）被召回的后代批次；后代自身状态不改写。
     */
    public List<BatchResponse> listAvailable() {
        Map<String, List<String>> parentsOf = childToParents();
        Set<String> recalled = new HashSet<>(repo.findRecalledKeys());
        return repo.findAvailableBatches().stream()
                .filter(b -> !BatchStatus.SPLIT.name().equals(b.status()))
                .filter(b -> !BatchStatus.MERGED.name().equals(b.status()))
                .filter(b -> recalledAncestor(b.batchKey(), parentsOf, recalled).isEmpty())
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
                    // 历史快照：seq 1 落定 RELEASE_REVIEW，seq 2 落定 RELEASED；
                    // 风险周期内 seq 3 维持 ALLERGEN_RISK，seq 4 重新落定 RELEASED，依奇偶类推；
                    // 不随后续召回或风险改写
                    BatchStatus snapshot;
                    if (a.seq() == 1) {
                        snapshot = BatchStatus.RELEASE_REVIEW;
                    } else if (a.seq() % 2 == 0) {
                        snapshot = BatchStatus.RELEASED;
                    } else {
                        snapshot = BatchStatus.ALLERGEN_RISK;
                    }
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
            // 子批成分版本 v1 继承父批当前过敏原集合与隔离级别，待自身检验后视为已检验
            BatchRepository.CompositionRow parentComp = repo.findLatestComposition(parentKey)
                    .orElseThrow(() -> new IllegalStateException("批次缺少成分版本: " + parentKey));
            List<SplitResponse.SplitChild> childBodies = new ArrayList<>(children.size());
            for (int i = 0; i < children.size(); i++) {
                SplitRequest.ChildSpec spec = children.get(i);
                repo.insertBatch(new BatchRepository.BatchRow(0L, spec.batchKey(), parent.productCode(),
                        spec.batchNo(), parent.producedAt(), BatchStatus.QUARANTINED.name(), now));
                for (int j = 0; j < required.size(); j++) {
                    repo.insertRequiredTest(spec.batchKey(), required.get(j), j + 1);
                }
                repo.insertComposition(new BatchRepository.CompositionRow(0L, spec.batchKey(), 1,
                        parentComp.allergenCodes(), parentComp.segregationLevel(), now));
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
     * 祖先查询：跨拆分父批与合批来源逐级向上到根，每项含批次自身状态及导致其不可用的召回祖先。
     */
    public List<LineageEntryResponse> listAncestors(String batchKey) {
        repo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        Map<String, List<String>> parentsOf = childToParents();
        Set<String> recalled = new HashSet<>(repo.findRecalledKeys());
        List<LineageEntryResponse> result = new ArrayList<>();
        for (String key : ancestorKeys(batchKey, parentsOf)) {
            result.add(toLineageEntry(key, parentsOf, recalled));
        }
        return result;
    }

    /**
     * 后代查询：按血缘关系创建顺序广度优先展开，每项含批次自身状态及导致其不可用的召回祖先。
     */
    public List<LineageEntryResponse> listDescendants(String batchKey) {
        repo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        Map<String, List<String>> parentsOf = childToParents();
        Set<String> recalled = new HashSet<>(repo.findRecalledKeys());
        List<LineageEntryResponse> result = new ArrayList<>();
        for (String key : descendantKeys(batchKey)) {
            result.add(toLineageEntry(key, parentsOf, recalled));
        }
        return result;
    }

    /**
     * 若任一级祖先（拆分父批或合批来源）已被召回则抛 422：后代批次禁止新增检验、批准和拆分。
     */
    private void assertNoRecalledAncestor(String batchKey) {
        Optional<String> recalled = recalledAncestor(batchKey, childToParents(),
                new HashSet<>(repo.findRecalledKeys()));
        if (recalled.isPresent()) {
            throw ApiException.unprocessable(
                    "祖先批次 " + recalled.get() + " 已召回，禁止新增检验、批准和拆分");
        }
    }

    /**
     * 沿父链（拆分父批与合批来源）向上查找最近的被直接召回（RECALLED）祖先；
     * 批次自身召回不算祖先召回。
     */
    private Optional<String> recalledAncestor(String batchKey, Map<String, List<String>> parentsOf,
                                              Set<String> recalled) {
        for (String ancestor : ancestorKeys(batchKey, parentsOf)) {
            if (recalled.contains(ancestor)) {
                return Optional.of(ancestor);
            }
        }
        return Optional.empty();
    }

    /**
     * 全部血缘边的 子批→父批集合 映射：拆分父批与合批来源均为父级；关系不可改写，只增不改。
     */
    private Map<String, List<String>> childToParents() {
        Map<String, List<String>> parentsOf = new HashMap<>();
        for (BatchRepository.LineageRow row : repo.findAllLineage()) {
            parentsOf.computeIfAbsent(row.childKey(), k -> new ArrayList<>()).add(row.parentKey());
        }
        for (BatchRepository.MergeLineageRow row : repo.findAllMergeLineage()) {
            parentsOf.computeIfAbsent(row.targetKey(), k -> new ArrayList<>()).add(row.sourceKey());
        }
        return parentsOf;
    }

    /**
     * 某批次的全部祖先业务键：拆分父批与合批来源按边创建顺序广度优先向上展开（不含自身）。
     */
    private List<String> ancestorKeys(String rootKey, Map<String, List<String>> parentsOf) {
        List<String> result = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(rootKey);
        visited.add(rootKey);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (String parent : parentsOf.getOrDefault(current, List.of())) {
                if (visited.add(parent)) {
                    result.add(parent);
                    queue.add(parent);
                }
            }
        }
        return result;
    }

    /**
     * 某批次的全部后代业务键，按血缘关系创建顺序广度优先展开（不含自身）；
     * 拆分（父→子）与合批（来源→目标）边均参与展开。
     */
    private List<String> descendantKeys(String rootKey) {
        Map<String, List<String>> childrenOf = new HashMap<>();
        for (BatchRepository.LineageRow row : repo.findAllLineage()) {
            childrenOf.computeIfAbsent(row.parentKey(), k -> new ArrayList<>()).add(row.childKey());
        }
        for (BatchRepository.MergeLineageRow row : repo.findAllMergeLineage()) {
            childrenOf.computeIfAbsent(row.sourceKey(), k -> new ArrayList<>()).add(row.targetKey());
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

    private LineageEntryResponse toLineageEntry(String batchKey, Map<String, List<String>> parentsOf,
                                                Set<String> recalled) {
        BatchRepository.BatchRow row = repo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        return new LineageEntryResponse(row.batchKey(), row.batchNo(),
                BatchStatus.valueOf(row.status()),
                recalledAncestor(batchKey, parentsOf, recalled).orElse(null));
    }

    /**
     * 成分修订：维护批次过敏原代码集合与隔离级别，追加不可变成分版本。
     * 集合换序视为同参（规范化后参与指纹）；未知代码或空/未知级别 422；
     * expectedVersion 与当前版本不一致 409；召回来源的后代不得降低隔离级别（422）；
     * 已放行批次引入新增过敏原时转为 ALLERGEN_RISK 并保留原放行快照。
     * allergenKey 幂等：同键同参重放返回首次结果，失败不占键。
     */
    public StoredResponse reviseComposition(String batchKey, ReviseCompositionRequest req) {
        List<String> codes = normalizeCodes(req.allergenCodes());
        SegregationLevel level = SegregationLevel.parse(req.segregationLevel())
                .orElseThrow(() -> ApiException.unprocessable(
                        "隔离级别为空或未知: " + req.segregationLevel()));
        String fingerprint = fingerprint("revise", batchKey, String.valueOf(req.expectedVersion()),
                String.join(",", codes), level.name(), "REVISE", "");
        return executeIdempotent(CMD_ALLERGEN_REVISE, req.allergenKey(), fingerprint, () -> {
            BatchRepository.BatchRow batch = repo.findBatchForUpdate(batchKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
            // 行锁后重查命令快照：并发同键请求在锁等待期间可能已由对方提交
            var logged = loggedResponse(CMD_ALLERGEN_REVISE, req.allergenKey(), fingerprint);
            if (logged.isPresent()) {
                return logged.get();
            }
            Set<String> known = new HashSet<>(repo.findAllergenCodes());
            for (String code : codes) {
                if (!known.contains(code)) {
                    throw ApiException.unprocessable("未知过敏原代码: " + code);
                }
            }
            BatchStatus status = BatchStatus.valueOf(batch.status());
            if (status == BatchStatus.REJECTED || status == BatchStatus.RECALLED
                    || status == BatchStatus.SPLIT || status == BatchStatus.MERGED) {
                throw ApiException.conflict("批次状态 " + status + " 不允许成分修订");
            }
            BatchRepository.CompositionRow current = repo.findLatestComposition(batchKey)
                    .orElseThrow(() -> new IllegalStateException("批次缺少成分版本: " + batchKey));
            if (req.expectedVersion() != current.version()) {
                throw ApiException.conflict("expectedVersion " + req.expectedVersion()
                        + " 与当前成分版本 v" + current.version() + " 不一致");
            }
            SegregationLevel currentLevel = SegregationLevel.valueOf(current.segregationLevel());
            if (level.rank() < currentLevel.rank()) {
                Optional<String> recalled = recalledAncestor(batchKey, childToParents(),
                        new HashSet<>(repo.findRecalledKeys()));
                if (recalled.isPresent()) {
                    throw ApiException.unprocessable("来源批次 " + recalled.get()
                            + " 已召回，后代成分版本不得降低隔离级别");
                }
            }
            String now = now();
            int newVersion = current.version() + 1;
            repo.insertComposition(new BatchRepository.CompositionRow(0L, batchKey, newVersion,
                    String.join(",", codes), level.name(), now));
            // 已放行批次发现新增过敏原：转为 ALLERGEN_RISK 并保留原放行快照
            if (status == BatchStatus.RELEASED
                    && !new HashSet<>(splitCodes(current.allergenCodes())).containsAll(codes)) {
                repo.insertRisk(new BatchRepository.RiskRow(0L, batchKey, now,
                        repo.maxApprovalId(batchKey), buildReleaseSnapshot(batchKey), null));
                repo.updateStatus(batchKey, BatchStatus.ALLERGEN_RISK.name());
            }
            CompositionVersionResponse body = new CompositionVersionResponse(batchKey, newVersion,
                    codes, level.name(), false, Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 合批：多个 RELEASED 来源批次在目标容器内合并为全新目标批次。
     * 先校验全部来源状态（RELEASED、无召回祖先、当前成分版本已检验）与容器兼容矩阵，
     * 任一不兼容整次 422/409 并回滚全部血缘与库存（来源状态与目标批次均不落库）。
     * 目标批次初始 QUARANTINED，成分 v1 为来源过敏原并集与最高隔离级别；
     * 来源批次置为 MERGED 退出可用库存。
     */
    public StoredResponse merge(MergeRequest req) {
        List<String> sources = req.sourceBatchKeys();
        if (new HashSet<>(sources).size() != sources.size()) {
            throw ApiException.conflict("sourceBatchKeys 在请求内重复");
        }
        if (sources.contains(req.targetBatchKey())) {
            throw ApiException.conflict("目标批次不得同时是来源批次: " + req.targetBatchKey());
        }
        String fingerprint = mergeFingerprint(req);
        return executeIdempotent(CMD_MERGE, req.allergenKey(), fingerprint, () -> {
            // 按业务键排序依次锁定全部来源，保证锁顺序确定
            Map<String, BatchRepository.BatchRow> locked = new LinkedHashMap<>();
            for (String key : sources.stream().sorted().toList()) {
                locked.put(key, repo.findBatchForUpdate(key)
                        .orElseThrow(() -> ApiException.notFound("来源批次不存在: " + key)));
            }
            // 行锁后重查命令快照：并发同键请求在锁等待期间可能已由对方提交
            var logged = loggedResponse(CMD_MERGE, req.allergenKey(), fingerprint);
            if (logged.isPresent()) {
                return logged.get();
            }
            if (!repo.containerExists(req.containerKey())) {
                throw ApiException.notFound("目标容器不存在: " + req.containerKey());
            }
            if (repo.findBatch(req.targetBatchKey()).isPresent()) {
                throw ApiException.conflict("targetBatchKey 已存在: " + req.targetBatchKey());
            }
            Map<String, List<String>> parentsOf = childToParents();
            Set<String> recalled = new HashSet<>(repo.findRecalledKeys());
            List<BatchRepository.CompositionRow> comps = new ArrayList<>(sources.size());
            for (String key : sources) {
                BatchRepository.BatchRow source = locked.get(key);
                if (!BatchStatus.RELEASED.name().equals(source.status())) {
                    throw ApiException.conflict("来源批次 " + key + " 状态 " + source.status()
                            + " 不允许合批，仅 RELEASED 可合并");
                }
                Optional<String> recalledAncestor = recalledAncestor(key, parentsOf, recalled);
                if (recalledAncestor.isPresent()) {
                    throw ApiException.unprocessable("来源批次 " + key + " 的祖先 "
                            + recalledAncestor.get() + " 已召回，禁止合批");
                }
                BatchRepository.CompositionRow comp = repo.findLatestComposition(key)
                        .orElseThrow(() -> new IllegalStateException("批次缺少成分版本: " + key));
                if (!repo.existsPassTestOnVersion(key, comp.version())) {
                    throw ApiException.unprocessable("来源批次 " + key + " 的当前成分版本 v"
                            + comp.version() + " 未检验，禁止合批");
                }
                comps.add(comp);
            }
            List<String> missingPairs = missingCompatibilityPairs(req.containerKey(), comps);
            if (!missingPairs.isEmpty()) {
                throw ApiException.unprocessable("目标容器 " + req.containerKey()
                        + " 未声明兼容级别对: " + String.join(", ", missingPairs));
            }
            String now = now();
            List<String> unionCodes = comps.stream()
                    .flatMap(c -> splitCodes(c.allergenCodes()).stream())
                    .distinct().sorted().toList();
            String maxLevel = comps.stream()
                    .map(c -> SegregationLevel.valueOf(c.segregationLevel()))
                    .max((a, b) -> Integer.compare(a.rank(), b.rank()))
                    .orElse(SegregationLevel.NONE).name();
            List<String> unionRequired = new ArrayList<>();
            for (String key : sources) {
                for (String item : repo.findRequiredTests(key)) {
                    if (!unionRequired.contains(item)) {
                        unionRequired.add(item);
                    }
                }
            }
            BatchRepository.BatchRow first = locked.get(sources.get(0));
            repo.insertBatch(new BatchRepository.BatchRow(0L, req.targetBatchKey(),
                    first.productCode(), req.batchNo(), now, BatchStatus.QUARANTINED.name(), now));
            for (int i = 0; i < unionRequired.size(); i++) {
                repo.insertRequiredTest(req.targetBatchKey(), unionRequired.get(i), i + 1);
            }
            repo.insertComposition(new BatchRepository.CompositionRow(0L, req.targetBatchKey(), 1,
                    String.join(",", unionCodes), maxLevel, now));
            for (int i = 0; i < sources.size(); i++) {
                repo.insertMergeLineage(new BatchRepository.MergeLineageRow(0L,
                        req.targetBatchKey(), sources.get(i), req.containerKey(), i + 1, now));
                repo.updateStatus(sources.get(i), BatchStatus.MERGED.name());
            }
            MergeResponse body = new MergeResponse(req.targetBatchKey(), BatchStatus.QUARANTINED,
                    req.containerKey(), sources, unionCodes, maxLevel, Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 合批兼容诊断：只校验不执行，返回各来源状态、缺失兼容级别对与并集成分。
     */
    public MergeDiagnoseResponse diagnoseMerge(MergeDiagnoseRequest req) {
        if (!repo.containerExists(req.containerKey())) {
            throw ApiException.notFound("目标容器不存在: " + req.containerKey());
        }
        Map<String, List<String>> parentsOf = childToParents();
        Set<String> recalled = new HashSet<>(repo.findRecalledKeys());
        List<MergeDiagnoseResponse.SourceDiagnosis> diagnoses = new ArrayList<>();
        List<BatchRepository.CompositionRow> comps = new ArrayList<>();
        Set<String> unionCodes = new LinkedHashSet<>();
        boolean allEligible = true;
        for (String key : req.sourceBatchKeys()) {
            Optional<BatchRepository.BatchRow> batch = repo.findBatch(key);
            if (batch.isEmpty()) {
                diagnoses.add(new MergeDiagnoseResponse.SourceDiagnosis(key, false, null, null,
                        false, null, false, "来源批次不存在"));
                allEligible = false;
                continue;
            }
            BatchRepository.CompositionRow comp = repo.findLatestComposition(key)
                    .orElseThrow(() -> new IllegalStateException("批次缺少成分版本: " + key));
            comps.add(comp);
            unionCodes.addAll(splitCodes(comp.allergenCodes()));
            boolean tested = repo.existsPassTestOnVersion(key, comp.version());
            String recalledAncestor = recalledAncestor(key, parentsOf, recalled).orElse(null);
            String reason = null;
            if (!BatchStatus.RELEASED.name().equals(batch.get().status())) {
                reason = "状态 " + batch.get().status() + " 不允许合批，仅 RELEASED 可合并";
            } else if (recalledAncestor != null) {
                reason = "祖先 " + recalledAncestor + " 已召回";
            } else if (!tested) {
                reason = "当前成分版本 v" + comp.version() + " 未检验";
            }
            allEligible &= reason == null;
            diagnoses.add(new MergeDiagnoseResponse.SourceDiagnosis(key, true,
                    batch.get().status(), comp.segregationLevel(), tested, recalledAncestor,
                    reason == null, reason));
        }
        List<String> missingPairs = missingCompatibilityPairs(req.containerKey(), comps);
        List<String> distinctLevels = comps.stream()
                .map(BatchRepository.CompositionRow::segregationLevel)
                .distinct().sorted().toList();
        List<String> sortedUnion = unionCodes.stream().sorted().toList();
        return new MergeDiagnoseResponse(req.containerKey(),
                allEligible && missingPairs.isEmpty(), distinctLevels, missingPairs,
                sortedUnion, diagnoses);
    }

    /**
     * 成分版本查询：批次全部不可变成分版本，含各版本是否已检验。
     */
    public List<CompositionVersionResponse> listCompositions(String batchKey) {
        repo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        return repo.findCompositions(batchKey).stream()
                .map(this::toCompositionResponse)
                .toList();
    }

    /**
     * 风险查询：批次过敏原风险状态与原放行快照；无风险记录时 riskActive=false。
     */
    public RiskResponse risk(String batchKey) {
        BatchRepository.BatchRow batch = repo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        Optional<BatchRepository.RiskRow> risk = repo.findLatestRisk(batchKey);
        if (risk.isEmpty()) {
            return new RiskResponse(batchKey, BatchStatus.valueOf(batch.status()), false,
                    null, null, null);
        }
        BatchRepository.RiskRow row = risk.get();
        return new RiskResponse(batchKey, BatchStatus.valueOf(batch.status()),
                row.clearedAt() == null, Instant.parse(row.enteredAt()),
                row.clearedAt() == null ? null : Instant.parse(row.clearedAt()),
                readJson(row.snapshotJson()));
    }

    /**
     * 血缘快照：当前成分版本、跨拆分与合批的传递祖先集合、直接合批来源。
     */
    public LineageSnapshotResponse lineageSnapshot(String batchKey) {
        BatchRepository.BatchRow batch = repo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        Map<String, List<String>> parentsOf = childToParents();
        Set<String> recalled = new HashSet<>(repo.findRecalledKeys());
        List<LineageEntryResponse> ancestors = new ArrayList<>();
        for (String key : ancestorKeys(batchKey, parentsOf)) {
            ancestors.add(toLineageEntry(key, parentsOf, recalled));
        }
        List<LineageSnapshotResponse.MergeSourceEntry> mergeSources = repo.findMergeSources(batchKey)
                .stream()
                .map(m -> new LineageSnapshotResponse.MergeSourceEntry(m.sourceKey(),
                        m.containerKey(), m.seq()))
                .toList();
        BatchRepository.CompositionRow current = repo.findLatestComposition(batchKey)
                .orElseThrow(() -> new IllegalStateException("批次缺少成分版本: " + batchKey));
        return new LineageSnapshotResponse(batchKey, BatchStatus.valueOf(batch.status()),
                toCompositionResponse(current), ancestors, mergeSources);
    }

    /**
     * ALLERGEN_RISK 批次的重新放行批准：要求全部必做检验项在当前成分版本上重新 PASS，
     * 风险后两个不同角色、不同批准人批准；第二笔批准通过放行门禁后解除风险回到 RELEASED。
     */
    private StoredResponse approveAllergenRisk(String batchKey, String actor, ApprovalRole role,
                                               ApproveRequest req) {
        BatchRepository.RiskRow risk = repo.findActiveRisk(batchKey)
                .orElseThrow(() -> ApiException.conflict(
                        "批次状态 ALLERGEN_RISK 但缺少风险记录: " + batchKey));
        BatchRepository.CompositionRow current = repo.findLatestComposition(batchKey)
                .orElseThrow(() -> new IllegalStateException("批次缺少成分版本: " + batchKey));
        Set<String> passed = new HashSet<>(
                repo.findPassedItemsOnVersion(batchKey, current.version()));
        List<String> required = repo.findRequiredTests(batchKey);
        if (!passed.containsAll(required)) {
            throw ApiException.unprocessable("必做检验项未在当前成分版本 v" + current.version()
                    + " 上全部重新通过，不能批准");
        }
        boolean actorInspected = repo.findTests(batchKey).stream()
                .anyMatch(t -> t.inspector().equals(actor));
        if (actorInspected) {
            throw ApiException.unprocessable("批准人不得为该批次任一检验结果的检验人: " + actor);
        }
        List<BatchRepository.ApprovalRow> all = repo.findApprovals(batchKey);
        List<BatchRepository.ApprovalRow> postRisk = all.stream()
                .filter(a -> a.id() > risk.approvalMarker())
                .toList();
        String now = now();
        int seq = all.size() + 1;
        BatchStatus newStatus;
        if (postRisk.isEmpty()) {
            newStatus = BatchStatus.ALLERGEN_RISK;
        } else {
            BatchRepository.ApprovalRow first = postRisk.get(0);
            if (first.role().equals(role.name())) {
                throw ApiException.conflict("角色 " + role + " 已在风险后批准过，需要另一种角色");
            }
            if (first.actorId().equals(actor)) {
                throw ApiException.conflict("两个批准人必须不同: " + actor);
            }
            // 放行门禁：最终血缘集合的当前成分版本须均已检验
            assertCompositionGate(batchKey);
            newStatus = BatchStatus.RELEASED;
            repo.clearRisk(batchKey, now);
        }
        repo.insertApproval(new BatchRepository.ApprovalRow(0L, batchKey, req.commandKey(),
                actor, role.name(), seq, now));
        repo.updateStatus(batchKey, newStatus.name());
        ApprovalResponse body = new ApprovalResponse(batchKey, actor, role, seq, newStatus,
                Instant.parse(now));
        return new StoredResponse(201, toJson(body));
    }

    /**
     * 放行门禁：最终血缘集合（自身 + 拆分/合批全部祖先）内各批次当前成分版本
     * 须已有 PASS 检验，任一未检验即 422 阻断放行。
     */
    private void assertCompositionGate(String batchKey) {
        List<String> lineageSet = new ArrayList<>();
        lineageSet.add(batchKey);
        lineageSet.addAll(ancestorKeys(batchKey, childToParents()));
        for (String key : lineageSet) {
            BatchRepository.CompositionRow comp = repo.findLatestComposition(key)
                    .orElseThrow(() -> new IllegalStateException("批次缺少成分版本: " + key));
            if (!repo.existsPassTestOnVersion(key, comp.version())) {
                throw ApiException.unprocessable("最终血缘集合内批次 " + key
                        + " 的当前成分版本 v" + comp.version() + " 未检验，阻断放行");
            }
        }
    }

    /**
     * 规范化过敏原代码：去空白、大写、去重、排序；空元素 400。换序不影响结果。
     */
    private List<String> normalizeCodes(List<String> raw) {
        List<String> codes = new ArrayList<>(raw.size());
        for (String code : raw) {
            if (code == null || code.isBlank()) {
                throw ApiException.badRequest("过敏原代码不能为空字符串");
            }
            codes.add(code.trim().toUpperCase(Locale.ROOT));
        }
        return codes.stream().distinct().sorted().toList();
    }

    /**
     * 拆分规范化代码串为列表；空串表示无过敏原。
     */
    private List<String> splitCodes(String csv) {
        if (csv == null || csv.isEmpty()) {
            return List.of();
        }
        return List.of(csv.split(","));
    }

    /**
     * 原放行快照：进入 ALLERGEN_RISK 时的状态与全部批准记录。
     */
    private String buildReleaseSnapshot(String batchKey) {
        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.put("batchKey", batchKey);
        snapshot.put("status", BatchStatus.RELEASED.name());
        var approvalsNode = snapshot.putArray("approvals");
        for (BatchRepository.ApprovalRow a : repo.findApprovals(batchKey)) {
            ObjectNode node = approvalsNode.addObject();
            node.put("actorId", a.actorId());
            node.put("role", a.role());
            node.put("seq", a.seq());
            node.put("createdAt", a.createdAt());
        }
        return snapshot.toString();
    }

    /**
     * 合批 allergenKey 指纹：含目标批次、目标容器、各来源批次当前成分版本、
     * 规范化过敏原代码并集与操作类型；同键同参重放，失败不占键。
     */
    private String mergeFingerprint(MergeRequest req) {
        List<String> parts = new ArrayList<>();
        parts.add("merge");
        parts.add(req.targetBatchKey());
        parts.add(req.containerKey());
        Set<String> unionCodes = new HashSet<>();
        for (String key : req.sourceBatchKeys().stream().sorted().toList()) {
            Optional<BatchRepository.CompositionRow> comp = repo.findLatestComposition(key);
            parts.add(key + "#v" + comp.map(BatchRepository.CompositionRow::version)
                    .orElse(-1));
            comp.ifPresent(c -> unionCodes.addAll(splitCodes(c.allergenCodes())));
        }
        parts.add(unionCodes.stream().sorted().reduce((a, b) -> a + "," + b).orElse(""));
        parts.add("MERGE");
        return fingerprint(parts.toArray(new String[0]));
    }

    /**
     * 容器兼容矩阵未覆盖的不同隔离级别对（形如 SEGREGATED×ISOLATED，按级别序号排序）。
     */
    private List<String> missingCompatibilityPairs(String containerKey,
                                                   List<BatchRepository.CompositionRow> comps) {
        List<SegregationLevel> levels = comps.stream()
                .map(c -> SegregationLevel.valueOf(c.segregationLevel()))
                .distinct()
                .sorted((a, b) -> Integer.compare(a.rank(), b.rank()))
                .toList();
        Set<String> declared = new HashSet<>();
        for (BatchRepository.CompatRow row : repo.findCompatibility(containerKey)) {
            declared.add(row.levelA() + "×" + row.levelB());
        }
        List<String> missing = new ArrayList<>();
        for (int i = 0; i < levels.size(); i++) {
            for (int j = i + 1; j < levels.size(); j++) {
                String pair = levels.get(i).name() + "×" + levels.get(j).name();
                if (!declared.contains(pair)) {
                    missing.add(pair);
                }
            }
        }
        return missing;
    }

    private CompositionVersionResponse toCompositionResponse(BatchRepository.CompositionRow row) {
        return new CompositionVersionResponse(row.batchKey(), row.version(),
                splitCodes(row.allergenCodes()), row.segregationLevel(),
                repo.existsPassTestOnVersion(row.batchKey(), row.version()),
                Instant.parse(row.createdAt()));
    }

    private JsonNode readJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("快照反序列化失败", e);
        }
    }
}
