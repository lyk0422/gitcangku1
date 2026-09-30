package com.example.starter.batch;

import com.example.starter.batch.dto.AdvanceCleaningRequest;
import com.example.starter.batch.dto.ApprovalResponse;
import com.example.starter.batch.dto.ApproveRequest;
import com.example.starter.batch.dto.BatchHistoryResponse;
import com.example.starter.batch.dto.BatchResponse;
import com.example.starter.batch.dto.CleaningResponse;
import com.example.starter.batch.dto.CreateBatchRequest;
import com.example.starter.batch.dto.LineageEntryResponse;
import com.example.starter.batch.dto.MergeBasisResponse;
import com.example.starter.batch.dto.MergeRequest;
import com.example.starter.batch.dto.MergeResponse;
import com.example.starter.batch.dto.RecallRequest;
import com.example.starter.batch.dto.RecallResponse;
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
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * 批次隔离与放行核心服务。
 *
 * <p>并发策略：同一批次的检验/批准/召回/合批/清洁推进在事务内对 batch 行 SELECT ... FOR UPDATE
 * 串行化，按事务提交顺序生效；合批按父批业务键排序后逐行加锁，杜绝混合版本子批。
 * commandKey 幂等通过 command_log 主键 + 参数指纹实现（集合类参数先规范化排序），
 * 同键同参重放返回首次响应快照，同键改参返回 409；仅成功命令写入 command_log。
 *
 * <p>过敏原隔离：批次保存时规范化过敏原集合（trim、去重、字典序稳定排序）与清洁代次；
 * 合批要求全部父批过敏原集合完全相同（Set 比较，不做未转义字符串拼接）、
 * 当前清洁代次一致且均处于未隔离的 RELEASED 状态，否则整单 422 回滚；
 * 子批固化父批集合与所用代次，后续清洁推进不改写该历史依据。
 */
@Service
public class BatchService {

    private static final String CMD_CREATE = "CREATE_BATCH";
    private static final String CMD_TEST = "SUBMIT_TEST";
    private static final String CMD_APPROVE = "APPROVE";
    private static final String CMD_RECALL = "RECALL";
    private static final String CMD_SPLIT = "SPLIT";
    private static final String CMD_MERGE = "MERGE";
    private static final String CMD_ADVANCE_CLEANING = "ADVANCE_CLEANING";

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
     * 过敏原集合与清洁代次在此规范化落库。
     */
    public StoredResponse createBatch(CreateBatchRequest req) {
        List<String> items = req.requiredTests().stream().map(String::trim).toList();
        if (new HashSet<>(items).size() != items.size()) {
            throw ApiException.badRequest("requiredTests 存在重复检验项");
        }
        List<String> allergens = normalizeAllergens(req.allergens());
        int cleanGeneration = req.cleanGeneration() == null ? 1 : req.cleanGeneration();
        String fingerprint = fingerprint("create", req.batchKey(), req.productCode(), req.batchNo(),
                req.producedAt().toString(), String.join(SEP, items),
                String.join(SEP, allergens), Integer.toString(cleanGeneration));
        return executeIdempotent(CMD_CREATE, req.commandKey(), fingerprint, () -> {
            repo.findBatch(req.batchKey()).ifPresent(b -> {
                throw ApiException.conflict("batchKey 已存在: " + req.batchKey());
            });
            String now = now();
            repo.insertBatch(new BatchRepository.BatchRow(0L, req.batchKey(), req.productCode(),
                    req.batchNo(), req.producedAt().toString(), BatchStatus.QUARANTINED.name(),
                    cleanGeneration, now));
            for (int i = 0; i < items.size(); i++) {
                repo.insertRequiredTest(req.batchKey(), items.get(i), i + 1);
            }
            for (int i = 0; i < allergens.size(); i++) {
                repo.insertAllergen(new BatchRepository.AllergenRow(req.batchKey(), allergens.get(i), i + 1));
            }
            BatchResponse body = new BatchResponse(req.batchKey(), req.productCode(), req.batchNo(),
                    req.producedAt(), BatchStatus.QUARANTINED, items, allergens, cleanGeneration,
                    null, Instant.parse(now));
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
     * 召回：RELEASED、SPLIT 或 MERGED 批次可召回，召回后进入 RECALLED 并不再出现在可用批次查询中。
     * 召回提交后其全部后代立即不可用（排除出可用查询、禁止新增检验/批准/拆分/合批），
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
                    && status != BatchStatus.MERGED) {
                throw ApiException.conflict(
                        "批次状态 " + status + " 不允许召回，仅 RELEASED、SPLIT 或 MERGED 可召回");
            }
            // 逐行锁定全部后代（按业务键排序保证锁顺序确定）：与后代上的检验/批准/拆分/合批
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
     * 以及任一祖先被召回的后代批次；后代自身状态不改写。
     */
    public List<BatchResponse> listAvailable() {
        Set<String> recalled = new HashSet<>(repo.findRecalledKeys());
        return repo.findAvailableBatches().stream()
                .filter(b -> !BatchStatus.SPLIT.name().equals(b.status())
                        && !BatchStatus.MERGED.name().equals(b.status()))
                .filter(b -> recalledAncestor(b.batchKey(), recalled).isEmpty())
                .map(this::toBatchResponse)
                .toList();
    }

    /**
     * 批次完整历史：批次概要 + 全部检验 + 全部批准 + 召回记录 + 合批冻结依据，历史不因召回而删除或改写。
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
        MergeBasisResponse mergeBasis = mergeBasis(batchKey).orElse(null);
        String mergedIntoChildKey = parentEdgesOfType(batchKey, "MERGE").stream()
                .map(BatchRepository.LineageRow::childKey).findFirst().orElse(null);
        return new BatchHistoryResponse(toBatchResponse(batch), tests, approvals, recall,
                mergeBasis, mergedIntoChildKey);
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
                repo.findRequiredTests(row.batchKey()), repo.findAllergens(row.batchKey()),
                row.cleanGeneration(), mergeBasis(row.batchKey()).orElse(null),
                Instant.parse(row.createdAt()));
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

    private List<String> fromJsonList(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("过敏原快照反序列化失败", e);
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
     * 过敏原集合规范化：逐项 trim、丢弃空白项、去重并按字典序稳定排序；null 视为空集合。
     * 比较一律基于该集合（Set#equals），不做未转义的字符串拼接比较。
     */
    private List<String> normalizeAllergens(List<String> raw) {
        if (raw == null) {
            return List.of();
        }
        TreeSet<String> normalized = new TreeSet<>();
        for (String item : raw) {
            if (item == null) {
                continue;
            }
            String value = item.trim();
            if (!value.isEmpty()) {
                normalized.add(value);
            }
        }
        return List.copyOf(normalized);
    }

    /**
     * 拆分：仅当前可用的 RELEASED 批次可一次拆成 2～5 个全新子批。
     * 子批继承产品编码、生产 UTC 时间、必做检验项、过敏原集合与当前清洁代次，初始 QUARANTINED，
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
            List<String> allergens = repo.findAllergens(parentKey);
            List<SplitResponse.SplitChild> childBodies = new ArrayList<>(children.size());
            for (int i = 0; i < children.size(); i++) {
                SplitRequest.ChildSpec spec = children.get(i);
                repo.insertBatch(new BatchRepository.BatchRow(0L, spec.batchKey(), parent.productCode(),
                        spec.batchNo(), parent.producedAt(), BatchStatus.QUARANTINED.name(),
                        parent.cleanGeneration(), now));
                for (int j = 0; j < required.size(); j++) {
                    repo.insertRequiredTest(spec.batchKey(), required.get(j), j + 1);
                }
                for (int j = 0; j < allergens.size(); j++) {
                    repo.insertAllergen(new BatchRepository.AllergenRow(spec.batchKey(),
                            allergens.get(j), j + 1));
                }
                repo.insertLineage(new BatchRepository.LineageRow(0L, "SPLIT", parentKey,
                        spec.batchKey(), i + 1, now));
                childBodies.add(new SplitResponse.SplitChild(spec.batchKey(), spec.batchNo(),
                        parent.productCode(), Instant.parse(parent.producedAt()),
                        BatchStatus.QUARANTINED, required, allergens, parent.cleanGeneration(),
                        Instant.parse(now)));
            }
            repo.updateStatus(parentKey, BatchStatus.SPLIT.name());
            SplitResponse body = new SplitResponse(parentKey, BatchStatus.SPLIT, childBodies,
                    Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 合批：2～5 个当前可用（RELEASED、无召回祖先）父批合为一个全新子批。
     *
     * <p>兼容条件（任一不满足整单 422，不生成子批、不改变任何父批状态与数量）：
     * 全部父批规范化过敏原集合完全相同（基于 Set#equals 比较，集合已去重并稳定排序，
     * 不做未转义字符串拼接）；全部父批当前清洁代次相同且等于请求声明的有效代次；
     * 全部父批均处于未隔离的 RELEASED 状态且无召回祖先。
     *
     * <p>并发：按父批业务键排序后逐行 SELECT ... FOR UPDATE，与清洁代次推进互斥，
     * 合批事务内读到的全部父批代次必然是同一已提交版本，不会形成混合版本子批。
     * 幂等：父批键集合排序去重后参与指纹，换序视为同参重放首次子批与过敏原快照；换成员/换集合 409。
     */
    public StoredResponse merge(MergeRequest req) {
        List<String> rawKeys = req.parentBatchKeys().stream()
                .filter(k -> k != null && !k.isBlank())
                .map(String::trim)
                .toList();
        TreeSet<String> orderedKeys = new TreeSet<>(rawKeys);
        if (orderedKeys.size() != rawKeys.size()) {
            throw ApiException.conflict("父批 batchKey 在请求内重复");
        }
        List<String> parentKeys = List.copyOf(orderedKeys);
        String childKey = req.childBatchKey().trim();
        String childBatchNo = req.childBatchNo().trim();
        int requestedGeneration = req.cleanGeneration();

        List<String> parts = new ArrayList<>();
        parts.add("merge");
        parts.addAll(parentKeys);
        parts.add(childKey);
        parts.add(childBatchNo);
        parts.add(Integer.toString(requestedGeneration));
        String fingerprint = fingerprint(parts.toArray(new String[0]));
        return executeIdempotent(CMD_MERGE, req.commandKey(), fingerprint, () -> {
            // 按业务键排序逐行加锁：与并发合批、清洁推进形成全局一致的锁顺序，杜绝死锁与混合版本读取
            List<BatchRepository.BatchRow> parents = new ArrayList<>(parentKeys.size());
            List<String> missing = new ArrayList<>();
            for (String key : parentKeys) {
                var parent = repo.findBatchForUpdate(key);
                if (parent.isPresent()) {
                    parents.add(parent.get());
                } else {
                    missing.add(key);
                }
            }
            if (!missing.isEmpty()) {
                throw ApiException.notFound("父批不存在: " + String.join(", ", missing));
            }
            // 全部父批行锁后重查命令快照：并发同键请求在锁等待期间可能已由对方提交，
            // 此时须重放首次子批快照而不是因父批已 MERGED 误判 422
            var logged = loggedResponse(CMD_MERGE, req.commandKey(), fingerprint);
            if (logged.isPresent()) {
                return logged.get();
            }
            if (parentKeys.contains(childKey)) {
                throw ApiException.unprocessable("合批子批不能同时是父批: " + childKey);
            }
            if (repo.findBatch(childKey).isPresent()) {
                throw ApiException.conflict("子批 batchKey 已存在: " + childKey);
            }

            // 逐父批收集全部不兼容原因后一次性返回 422，调用方可看到每个被拒父批的具体原因
            Map<String, List<String>> allergenSets = new HashMap<>();
            for (BatchRepository.BatchRow parent : parents) {
                allergenSets.put(parent.batchKey(), repo.findAllergens(parent.batchKey()));
            }
            List<String> referenceAllergens = allergenSets.get(parents.get(0).batchKey());
            List<String> reasons = new ArrayList<>();
            Set<String> recalled = new HashSet<>(repo.findRecalledKeys());
            for (BatchRepository.BatchRow parent : parents) {
                String key = parent.batchKey();
                BatchStatus status = BatchStatus.valueOf(parent.status());
                if (status != BatchStatus.RELEASED) {
                    reasons.add("父批 " + key + " 当前状态为 " + status + "，仍处于隔离/终态，不可合批");
                } else {
                    recalledAncestor(key, recalled).ifPresent(ancestor ->
                            reasons.add("父批 " + key + " 的祖先批次 " + ancestor + " 已召回，不可合批"));
                }
                if (parent.cleanGeneration() != requestedGeneration) {
                    reasons.add("父批 " + key + " 当前清洁代次为 " + parent.cleanGeneration()
                            + "，与请求使用的有效代次 " + requestedGeneration + " 不一致（代次过期或超前）");
                }
                // 集合比较基于 Set#equals：去重与排序在规范化阶段完成，不依赖未转义拼接
                Set<String> current = new HashSet<>(allergenSets.get(key));
                if (!current.equals(new HashSet<>(referenceAllergens))) {
                    reasons.add("父批 " + key + " 过敏原集合 " + allergenSets.get(key)
                            + " 与基准父批 " + parents.get(0).batchKey() + " 的集合 "
                            + referenceAllergens + " 不兼容（合批要求所有父批过敏原集合完全相同）");
                }
            }
            if (!reasons.isEmpty()) {
                throw ApiException.unprocessable("合批被拒绝: " + String.join("; ", reasons));
            }

            BatchRepository.BatchRow first = parents.get(0);
            String now = now();
            List<String> required = repo.findRequiredTests(first.batchKey());
            // 子批固化父批共同的过敏原集合与所用清洁代次
            repo.insertBatch(new BatchRepository.BatchRow(0L, childKey, first.productCode(),
                    childBatchNo, now, BatchStatus.QUARANTINED.name(), requestedGeneration, now));
            for (int i = 0; i < required.size(); i++) {
                repo.insertRequiredTest(childKey, required.get(i), i + 1);
            }
            for (int i = 0; i < referenceAllergens.size(); i++) {
                repo.insertAllergen(new BatchRepository.AllergenRow(childKey,
                        referenceAllergens.get(i), i + 1));
            }
            long mergeId = repo.insertMerge(new BatchRepository.MergeRow(0L, req.commandKey(),
                    childKey, requestedGeneration, now));
            List<MergeResponse.ParentBasis> parentBodies = new ArrayList<>(parents.size());
            for (int i = 0; i < parents.size(); i++) {
                BatchRepository.BatchRow parent = parents.get(i);
                List<String> snapshot = allergenSets.get(parent.batchKey());
                repo.insertLineage(new BatchRepository.LineageRow(0L, "MERGE", parent.batchKey(),
                        childKey, i + 1, now));
                repo.insertMergeParent(new BatchRepository.MergeParentRow(0L, mergeId,
                        parent.batchKey(), i + 1, toJson(snapshot), parent.cleanGeneration()));
                parentBodies.add(new MergeResponse.ParentBasis(parent.batchKey(), snapshot,
                        parent.cleanGeneration(), BatchStatus.RELEASED));
                repo.updateStatus(parent.batchKey(), BatchStatus.MERGED.name());
            }
            MergeResponse body = new MergeResponse(childKey, childBatchNo, BatchStatus.QUARANTINED,
                    parentKeys, referenceAllergens, requestedGeneration, parentBodies,
                    Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 推进生产线清洁代次：必须大于当前代次（只增不减），整单不满足返回 422。
     * 仅更新批次当前代次；已合批子批在 batch_merge/batch_merge_parent 中固化的历史依据不被改写。
     */
    public StoredResponse advanceCleaning(String batchKey, AdvanceCleaningRequest req) {
        int newGeneration = req.newGeneration();
        String fingerprint = fingerprint("cleaning", batchKey, Integer.toString(newGeneration));
        return executeIdempotent(CMD_ADVANCE_CLEANING, req.commandKey(), fingerprint, () -> {
            BatchRepository.BatchRow batch = repo.findBatchForUpdate(batchKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
            var logged = loggedResponse(CMD_ADVANCE_CLEANING, req.commandKey(), fingerprint);
            if (logged.isPresent()) {
                return logged.get();
            }
            if (newGeneration <= batch.cleanGeneration()) {
                throw ApiException.unprocessable("清洁代次只增不减：批次 " + batchKey + " 当前代次为 "
                        + batch.cleanGeneration() + "，不能回退或停留到 " + newGeneration);
            }
            repo.updateCleanGeneration(batchKey, newGeneration);
            CleaningResponse body = new CleaningResponse(batchKey, batch.cleanGeneration(),
                    newGeneration);
            return new StoredResponse(200, toJson(body));
        });
    }

    /**
     * 祖先查询：广度优先逐级向上到根（合批子批的多个父批全部展开），
     * 每项含批次自身状态、过敏原、清洁代次及导致不可用的召回祖先，按层级与 seq 稳定排序。
     */
    public List<LineageEntryResponse> listAncestors(String batchKey) {
        repo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        Set<String> recalled = new HashSet<>(repo.findRecalledKeys());
        List<LineageEntryResponse> result = new ArrayList<>();
        Deque<String> queue = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        queue.add(batchKey);
        seen.add(batchKey);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            List<BatchRepository.LineageRow> edges = repo.findParentEdges(current);
            for (BatchRepository.LineageRow edge : edges) {
                String parent = edge.parentKey();
                if (seen.add(parent)) {
                    result.add(toLineageEntry(parent, edge.relationType(), recalled));
                    queue.add(parent);
                }
            }
        }
        return result;
    }

    /**
     * 后代查询：按血缘关系创建顺序广度优先展开（含 SPLIT/MERGE 两类边），
     * 每项含批次自身状态、过敏原、清洁代次及导致不可用的召回祖先。
     */
    public List<LineageEntryResponse> listDescendants(String batchKey) {
        repo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        Map<String, String> parentRelation = childToParentRelation();
        Set<String> recalled = new HashSet<>(repo.findRecalledKeys());
        List<LineageEntryResponse> result = new ArrayList<>();
        for (String key : descendantKeys(batchKey)) {
            result.add(toLineageEntry(key, parentRelation.get(key), recalled));
        }
        return result;
    }

    /**
     * 若任一级祖先已被召回则抛 422：后代批次禁止新增检验、批准、拆分和合批。
     */
    private void assertNoRecalledAncestor(String batchKey) {
        recalledAncestor(batchKey, new HashSet<>(repo.findRecalledKeys())).ifPresent(recalled -> {
            throw ApiException.unprocessable(
                    "祖先批次 " + recalled + " 已召回，禁止新增检验、批准、拆分和合批");
        });
    }

    /**
     * 沿全部父链（合批子批可有多个父批）广度向上查找最近的被直接召回（RECALLED）祖先；
     * 批次自身召回不算祖先召回。
     */
    private Optional<String> recalledAncestor(String batchKey, Set<String> recalled) {
        Map<String, List<BatchRepository.LineageRow>> incoming = new HashMap<>();
        for (BatchRepository.LineageRow edge : repo.findAllLineage()) {
            incoming.computeIfAbsent(edge.childKey(), k -> new ArrayList<>()).add(edge);
        }
        Deque<String> queue = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        queue.add(batchKey);
        seen.add(batchKey);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (BatchRepository.LineageRow edge : incoming.getOrDefault(current, List.of())) {
                String parent = edge.parentKey();
                if (recalled.contains(parent)) {
                    return Optional.of(parent);
                }
                if (seen.add(parent)) {
                    queue.add(parent);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * 全部血缘边的 子批→关系类型 映射；关系不可改写，只增不改。
     */
    private Map<String, String> childToParentRelation() {
        Map<String, String> relationOf = new HashMap<>();
        for (BatchRepository.LineageRow row : repo.findAllLineage()) {
            relationOf.putIfAbsent(row.childKey(), row.relationType());
        }
        return relationOf;
    }

    /**
     * 某批次作为子批参与的指定类型血缘边（合批父批反查其并入的子批时用 MERGE）。
     */
    private List<BatchRepository.LineageRow> parentEdgesOfType(String parentKey, String relationType) {
        return repo.findAllLineage().stream()
                .filter(e -> e.parentKey().equals(parentKey) && e.relationType().equals(relationType))
                .toList();
    }

    /**
     * 某批次的全部后代业务键，按血缘关系创建顺序广度优先展开（不含自身），同时支持拆分与合批边。
     */
    private List<String> descendantKeys(String rootKey) {
        Map<String, LinkedHashSet<String>> childrenOf = new HashMap<>();
        for (BatchRepository.LineageRow row : repo.findAllLineage()) {
            childrenOf.computeIfAbsent(row.parentKey(), k -> new LinkedHashSet<>()).add(row.childKey());
        }
        List<String> result = new ArrayList<>();
        Deque<String> queue = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        queue.add(rootKey);
        seen.add(rootKey);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (String child : childrenOf.getOrDefault(current, new LinkedHashSet<>())) {
                if (seen.add(child)) {
                    result.add(child);
                    queue.add(child);
                }
            }
        }
        return result;
    }

    /**
     * 读取合批子批的冻结依据；非合批子批返回空。依据取自 batch_merge 快照，不随后续清洁改写。
     */
    private Optional<MergeBasisResponse> mergeBasis(String childKey) {
        Optional<BatchRepository.MergeRow> merge = repo.findMergeByChild(childKey);
        if (merge.isEmpty()) {
            return Optional.empty();
        }
        BatchRepository.MergeRow row = merge.get();
        List<MergeResponse.ParentBasis> parents = repo.findMergeParents(row.id()).stream()
                .map(p -> new MergeResponse.ParentBasis(p.parentKey(), fromJsonList(p.allergensJson()),
                        p.cleanGeneration(), BatchStatus.MERGED))
                .toList();
        List<String> frozenAllergens = parents.isEmpty()
                ? List.of()
                : parents.get(0).allergens();
        return Optional.of(new MergeBasisResponse(row.frozenCleanGeneration(), frozenAllergens,
                parents, Instant.parse(row.createdAt())));
    }

    private LineageEntryResponse toLineageEntry(String batchKey, String relationType,
                                                Set<String> recalled) {
        BatchRepository.BatchRow row = repo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        return new LineageEntryResponse(row.batchKey(), row.batchNo(),
                BatchStatus.valueOf(row.status()), repo.findAllergens(batchKey),
                row.cleanGeneration(), relationType,
                recalledAncestor(batchKey, recalled).orElse(null));
    }
}
