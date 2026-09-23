package com.example.starter.batch;

import com.example.starter.batch.dto.ApprovalResponse;
import com.example.starter.batch.dto.ApproveRequest;
import com.example.starter.batch.dto.BatchHistoryResponse;
import com.example.starter.batch.dto.BatchResponse;
import com.example.starter.batch.dto.CreateBatchRequest;
import com.example.starter.batch.dto.LineageEntryResponse;
import com.example.starter.batch.dto.MergeRequest;
import com.example.starter.batch.dto.MergeResponse;
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
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
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
    private static final String CMD_MERGE = "MERGE";

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
            // 先做不加锁的存在性与状态预判
            BatchRepository.BatchRow preview = repo.findBatch(batchKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
            BatchStatus previewStatus = BatchStatus.valueOf(preview.status());
            if (previewStatus != BatchStatus.RELEASED && previewStatus != BatchStatus.SPLIT
                    && previewStatus != BatchStatus.MERGED) {
                throw ApiException.conflict("批次状态 " + previewStatus
                        + " 不允许召回，仅 RELEASED、SPLIT 或 MERGED 可召回");
            }
            // 逐行锁定自身与全部后代（合并为一个集合后按业务键排序，保证锁顺序全局确定）：
            // 与后代上的检验/批准/拆分/合批互斥，避免与合批的排序父批锁形成环；
            // 按事务提交顺序裁决——召回先提交则后代新操作看到召回并返回 422。
            Set<String> lockedKeys = new HashSet<>(loadGraph().descendantKeys(batchKey));
            lockedKeys.add(batchKey);
            List<String> orderedKeys = new ArrayList<>(lockedKeys);
            Collections.sort(orderedKeys);
            for (String lockedKey : orderedKeys) {
                repo.findBatchForUpdate(lockedKey);
            }
            // 锁齐后在锁内重查命令快照：并发同键召回在锁等待期间可能已由对方提交
            var loggedAfterLock = loggedResponse(CMD_RECALL, req.commandKey(), fingerprint);
            if (loggedAfterLock.isPresent()) {
                return loggedAfterLock.get();
            }
            // 锁等待期间状态可能已被并发事务改写，锁内复核
            BatchRepository.BatchRow batch = repo.findBatchForUpdate(batchKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
            BatchStatus status = BatchStatus.valueOf(batch.status());
            if (status != BatchStatus.RELEASED && status != BatchStatus.SPLIT
                    && status != BatchStatus.MERGED) {
                throw ApiException.conflict("批次状态 " + status
                        + " 不允许召回，仅 RELEASED、SPLIT 或 MERGED 可召回");
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
     * 以及任一路径祖先被召回的后代批次；后代自身状态不改写。
     */
    public List<BatchResponse> listAvailable() {
        LineageGraph graph = loadGraph();
        Set<String> recalled = new HashSet<>(repo.findRecalledKeys());
        return repo.findAvailableBatches().stream()
                .filter(b -> !BatchStatus.SPLIT.name().equals(b.status()))
                .filter(b -> !BatchStatus.MERGED.name().equals(b.status()))
                .filter(b -> graph.recalledAncestors(b.batchKey(), recalled).isEmpty())
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
     * 合批：2～5 个不同且当前可用的 RELEASED 父批合为一个全新批次。
     * 父批产品编码与必做检验项集合必须相同；新批初始 QUARANTINED，生产 UTC 时间取父批最晚值；
     * 父批全部置为 MERGED 并退出可用集合。父批状态、全部多父边与新批同一事务提交。
     */
    public StoredResponse merge(MergeRequest req) {
        // 父批集合去重后按业务键排序：集合顺序不影响同参判定，也保证并发锁顺序确定
        List<String> parents = new ArrayList<>(new LinkedHashSet<>(req.parentBatchKeys()));
        if (parents.size() != req.parentBatchKeys().size()) {
            throw ApiException.badRequest("parentBatchKeys 存在重复父批");
        }
        if (parents.size() < 2 || parents.size() > 5) {
            throw ApiException.badRequest("parentBatchKeys 必须包含 2～5 个不同父批");
        }
        Collections.sort(parents);
        List<String> parts = new ArrayList<>();
        parts.add("merge");
        parts.add(req.newBatchKey());
        parts.add(req.newBatchNo());
        parts.addAll(parents);
        String fingerprint = fingerprint(parts.toArray(new String[0]));
        return executeIdempotent(CMD_MERGE, req.commandKey(), fingerprint, () -> {
            // 按排序后的父批键逐行加锁：父批只能被消费一次，合批/拆分/召回并发按提交顺序裁决
            List<BatchRepository.BatchRow> parentRows = new ArrayList<>(parents.size());
            for (String parentKey : parents) {
                BatchRepository.BatchRow row = repo.findBatchForUpdate(parentKey)
                        .orElseThrow(() -> ApiException.notFound("批次不存在: " + parentKey));
                parentRows.add(row);
            }
            // 锁齐父批后重查命令快照：并发同键请求在锁等待期间可能已由对方提交，
            // 此时须重放首次结果而不是因父批已 MERGED 误判冲突
            var logged = loggedResponse(CMD_MERGE, req.commandKey(), fingerprint);
            if (logged.isPresent()) {
                return logged.get();
            }
            LineageGraph graph = loadGraph();
            Set<String> recalledKeys = new HashSet<>(repo.findRecalledKeys());
            String productCode = parentRows.get(0).productCode();
            List<String> required = repo.findRequiredTests(parents.get(0));
            Set<String> requiredSet = new HashSet<>(required);
            Instant latestProducedAt = Instant.parse(parentRows.get(0).producedAt());
            for (int i = 0; i < parents.size(); i++) {
                BatchRepository.BatchRow row = parentRows.get(i);
                // 祖先召回拦截优先于自身状态：任一路径存在召回祖先一律 422，不伪造后代自身状态
                List<String> recalledAncestors = new ArrayList<>(
                        graph.recalledAncestors(row.batchKey(), recalledKeys));
                if (!recalledAncestors.isEmpty()) {
                    Collections.sort(recalledAncestors);
                    throw ApiException.unprocessable("批次 " + row.batchKey() + " 的祖先 "
                            + recalledAncestors + " 已召回，该批次不可用，禁止合批");
                }
                if (!BatchStatus.RELEASED.name().equals(row.status())) {
                    throw ApiException.conflict(
                            "批次 " + row.batchKey() + " 状态 " + row.status()
                                    + " 不允许合批，仅当前可用的 RELEASED 批次可合批");
                }
                if (!productCode.equals(row.productCode())) {
                    throw ApiException.unprocessable("父批产品编码不一致: "
                            + productCode + " / " + row.productCode());
                }
                List<String> otherRequired = repo.findRequiredTests(row.batchKey());
                if (otherRequired.size() != requiredSet.size()
                        || !requiredSet.containsAll(otherRequired)) {
                    throw ApiException.unprocessable(
                            "父批必做检验项集合不一致: " + parents.get(0) + " / " + row.batchKey());
                }
                Instant producedAt = Instant.parse(row.producedAt());
                if (producedAt.isAfter(latestProducedAt)) {
                    latestProducedAt = producedAt;
                }
            }
            if (repo.findBatch(req.newBatchKey()).isPresent()) {
                throw ApiException.conflict("新批 batchKey 已存在: " + req.newBatchKey());
            }
            String now = now();
            repo.insertBatch(new BatchRepository.BatchRow(0L, req.newBatchKey(), productCode,
                    req.newBatchNo(), latestProducedAt.toString(),
                    BatchStatus.QUARANTINED.name(), now));
            for (int j = 0; j < required.size(); j++) {
                repo.insertRequiredTest(req.newBatchKey(), required.get(j), j + 1);
            }
            for (int i = 0; i < parents.size(); i++) {
                repo.insertLineage(new BatchRepository.LineageRow(0L, parents.get(i),
                        req.newBatchKey(), "MERGE", i + 1, now));
            }
            for (String parentKey : parents) {
                repo.updateStatus(parentKey, BatchStatus.MERGED.name());
            }
            MergeResponse.MergedChild child = new MergeResponse.MergedChild(
                    req.newBatchKey(), req.newBatchNo(), productCode, latestProducedAt,
                    BatchStatus.QUARANTINED, required, Instant.parse(now));
            MergeResponse body = new MergeResponse(parents, BatchStatus.MERGED, child,
                    Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 祖先查询：沿全部路径从直接父批逐级向上到根，按 batchKey 升序去重展开，
     * 每项含批次自身状态及导致其不可用的全部召回祖先（共享祖先只显示一次）。
     */
    public List<LineageEntryResponse> listAncestors(String batchKey) {
        repo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        LineageGraph graph = loadGraph();
        List<String> ancestorKeys = new ArrayList<>(graph.ancestorKeys(batchKey));
        Collections.sort(ancestorKeys);
        List<LineageEntryResponse> result = new ArrayList<>(ancestorKeys.size());
        for (String key : ancestorKeys) {
            result.add(toLineageEntry(key, graph));
        }
        return result;
    }

    /**
     * 后代查询：按血缘关系创建顺序广度优先展开，按 batchKey 升序去重，
     * 每项含批次自身状态及导致其不可用的全部召回祖先。
     */
    public List<LineageEntryResponse> listDescendants(String batchKey) {
        repo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        LineageGraph graph = loadGraph();
        List<String> descendantKeys = new ArrayList<>(graph.descendantKeys(batchKey));
        Collections.sort(descendantKeys);
        List<LineageEntryResponse> result = new ArrayList<>(descendantKeys.size());
        for (String key : descendantKeys) {
            result.add(toLineageEntry(key, graph));
        }
        return result;
    }

    /**
     * 若任一路径上存在已召回祖先则抛 422：后代批次禁止新增检验、批准、拆分和合批；
     * 召回一条路径不能被另一条未召回路径抵消。
     */
    private void assertNoRecalledAncestor(String batchKey) {
        LineageGraph graph = loadGraph();
        List<String> recalled = new ArrayList<>(graph.recalledAncestors(batchKey,
                new HashSet<>(repo.findRecalledKeys())));
        if (!recalled.isEmpty()) {
            Collections.sort(recalled);
            throw ApiException.unprocessable(
                    "祖先批次 " + recalled + " 已召回，禁止新增检验、批准、拆分和合批");
        }
    }

    private LineageGraph loadGraph() {
        return new LineageGraph(repo.findAllLineage());
    }

    private LineageEntryResponse toLineageEntry(String batchKey, LineageGraph graph) {
        BatchRepository.BatchRow row = repo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        List<String> recalled = new ArrayList<>(
                graph.recalledAncestors(batchKey, new HashSet<>(repo.findRecalledKeys())));
        Collections.sort(recalled);
        return new LineageEntryResponse(row.batchKey(), row.batchNo(),
                BatchStatus.valueOf(row.status()),
                recalled.isEmpty() ? null : recalled.get(0),
                recalled);
    }

    /**
     * 多父血缘有向无环图：边方向 父批→子批。拆分产生单父边，合批为同一子批引入 2～5 条父边，
     * 允许拆分后的兄弟批次重新合批，故同一节点可能经多条路径到达同一祖先（菱形汇合）。
     * 关系不可改写，图只增不改；本对象为单次请求内的只读快照。
     */
    private static final class LineageGraph {

        /**
         * 父批键 → 子批键列表，按边的创建顺序（id 升序）排列。
         */
        private final Map<String, List<String>> childrenOf = new HashMap<>();

        /**
         * 子批键 → 全部直接父批键列表，按边的创建顺序（id 升序）排列。
         */
        private final Map<String, List<String>> parentsOf = new HashMap<>();

        private LineageGraph(List<BatchRepository.LineageRow> edges) {
            for (BatchRepository.LineageRow edge : edges) {
                childrenOf.computeIfAbsent(edge.parentKey(), k -> new ArrayList<>())
                        .add(edge.childKey());
                parentsOf.computeIfAbsent(edge.childKey(), k -> new ArrayList<>())
                        .add(edge.parentKey());
            }
        }

        /**
         * 某节点的全部祖先业务键（不含自身）：沿全部父边向上遍历，汇合节点只计一次。
         */
        private Set<String> ancestorKeys(String childKey) {
            Set<String> visited = new HashSet<>();
            Deque<String> stack = new ArrayDeque<>(parentsOf.getOrDefault(childKey, List.of()));
            while (!stack.isEmpty()) {
                String current = stack.pop();
                if (visited.add(current)) {
                    stack.addAll(parentsOf.getOrDefault(current, List.of()));
                }
            }
            return visited;
        }

        /**
         * 某节点的全部后代业务键（不含自身）：按边创建顺序广度优先展开，汇合节点只计一次。
         */
        private Set<String> descendantKeys(String rootKey) {
            Set<String> visited = new HashSet<>();
            Deque<String> queue = new ArrayDeque<>();
            queue.add(rootKey);
            while (!queue.isEmpty()) {
                String current = queue.poll();
                for (String child : childrenOf.getOrDefault(current, List.of())) {
                    if (visited.add(child)) {
                        queue.add(child);
                    }
                }
            }
            return visited;
        }

        /**
         * 沿全部路径向上收集被直接召回（RECALLED）的祖先键；批次自身召回不算祖先召回。
         * 任一路径存在召回祖先即计入，召回一条路径不能被另一条未召回路径抵消。
         */
        private Set<String> recalledAncestors(String batchKey, Set<String> recalledKeys) {
            Set<String> result = new HashSet<>();
            for (String ancestor : ancestorKeys(batchKey)) {
                if (recalledKeys.contains(ancestor)) {
                    result.add(ancestor);
                }
            }
            return result;
        }
    }
}
