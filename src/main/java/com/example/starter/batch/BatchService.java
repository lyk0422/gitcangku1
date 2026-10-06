package com.example.starter.batch;

import com.example.starter.batch.dto.ApprovalResponse;
import com.example.starter.batch.dto.ApproveRequest;
import com.example.starter.batch.dto.AssayRoundResponse;
import com.example.starter.batch.dto.BatchHistoryResponse;
import com.example.starter.batch.dto.BatchResponse;
import com.example.starter.batch.dto.CreateBatchRequest;
import com.example.starter.batch.dto.LineageEntryResponse;
import com.example.starter.batch.dto.OpenAssayRoundRequest;
import com.example.starter.batch.dto.RecallRequest;
import com.example.starter.batch.dto.RecallResponse;
import com.example.starter.batch.dto.SplitRequest;
import com.example.starter.batch.dto.SplitResponse;
import com.example.starter.batch.dto.SubmitTestRequest;
import com.example.starter.batch.dto.TestResultResponse;
import com.example.starter.batch.dto.WriteAssayReadingRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.MathContext;
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
    private static final String CMD_OPEN_ROUND = "OPEN_ASSAY_ROUND";
    private static final String CMD_WRITE_READING = "WRITE_ASSAY_READING";

    /**
     * 平行测定轮次状态：OPEN 进行中；PASSED/FAILED 为封结后的终态。
     */
    private static final String ROUND_OPEN = "OPEN";
    private static final String ROUND_PASSED = "PASSED";
    private static final String ROUND_FAILED = "FAILED";

    /**
     * 每轮固定读数条数与每项最大轮次数。
     */
    private static final int READINGS_PER_ROUND = 3;
    private static final int MAX_ROUNDS_PER_ITEM = 3;

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
            if (!repo.findAssayRounds(batchKey, req.testItem()).isEmpty()) {
                throw ApiException.conflict("该检验项已建立平行测定轮次，按轮次判定: " + req.testItem());
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
            // 平行测定门禁：凡建立轮次的必做项，其当前轮必须已封结合格，否则 422
            for (String item : repo.findRequiredTests(batchKey)) {
                List<BatchRepository.AssayRoundRow> rounds = repo.findAssayRounds(batchKey, item);
                if (!rounds.isEmpty() && !ROUND_PASSED.equals(rounds.get(rounds.size() - 1).status())) {
                    throw ApiException.unprocessable(
                            "检验项 " + item + " 当前平行测定轮次未齐或不合格，不能批准");
                }
            }

            List<BatchRepository.ApprovalRow> approvals = repo.findValidApprovals(batchKey);
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
                    actor, role.name(), seq, assaySnapshotJson(batchKey), 1, now));
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
        List<AssayRoundResponse> assayRounds = repo.findAllAssayRounds(batchKey).stream()
                .map(r -> toAssayRoundResponse(r, BatchStatus.valueOf(batch.status())))
                .toList();
        return new BatchHistoryResponse(toBatchResponse(batch), tests, approvals, recall, assayRounds);
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
        // 平行测定项：当前轮（序号最大）封结合格才算通过；开新轮后旧轮合格不再作数
        for (String item : required) {
            List<BatchRepository.AssayRoundRow> rounds = repo.findAssayRounds(batchKey, item);
            if (!rounds.isEmpty()) {
                if (ROUND_PASSED.equals(rounds.get(rounds.size() - 1).status())) {
                    passed.add(item);
                } else {
                    passed.remove(item);
                }
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
     * 开启平行测定轮次：仅隔离流程中（QUARANTINED/PENDING_RELEASE/RELEASE_REVIEW）批次可为
     * 尚未判定的必做检验项开轮；expectedVersion 须等于该项已有轮次数；最多 3 轮；
     * 新轮开启使该批次旧批准失效并回退批次状态；已放行批次不可开轮。
     */
    public StoredResponse openAssayRound(String batchKey, String actorId, OpenAssayRoundRequest req) {
        String actor = requireActor(actorId);
        BigDecimal meanMin = new BigDecimal(req.meanMin());
        BigDecimal meanMax = new BigDecimal(req.meanMax());
        BigDecimal maxRange = new BigDecimal(req.maxRange());
        if (meanMin.compareTo(meanMax) > 0) {
            throw ApiException.badRequest("meanMin 不能大于 meanMax");
        }
        if (maxRange.signum() < 0) {
            throw ApiException.badRequest("maxRange 不能为负数");
        }
        String fingerprint = structuredFingerprint("assay.open", actor, batchKey, req.testItem(),
                "meanMin", req.meanMin(), "meanMax", req.meanMax(), "maxRange", req.maxRange(),
                "expectedVersion", String.valueOf(req.expectedVersion()));
        return executeIdempotent(CMD_OPEN_ROUND, req.requestId(), fingerprint, () -> {
            BatchRepository.BatchRow batch = repo.findBatchForUpdate(batchKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
            // 批次行锁后重查命令快照：并发同键请求在锁等待期间可能已由对方提交，须重放首次结果
            var logged = loggedResponse(CMD_OPEN_ROUND, req.requestId(), fingerprint);
            if (logged.isPresent()) {
                return logged.get();
            }
            BatchStatus status = BatchStatus.valueOf(batch.status());
            assertNoRecalledAncestor(batchKey);
            if (status == BatchStatus.REJECTED || status == BatchStatus.RELEASED
                    || status == BatchStatus.RECALLED || status == BatchStatus.SPLIT) {
                throw ApiException.conflict("批次状态 " + status + " 不允许开启平行测定轮次");
            }
            List<String> required = repo.findRequiredTests(batchKey);
            if (!required.contains(req.testItem())) {
                throw ApiException.unprocessable("检验项不属于该批次必做项: " + req.testItem());
            }
            boolean itemTested = repo.findTests(batchKey).stream()
                    .anyMatch(t -> t.testItem().equals(req.testItem()));
            if (itemTested) {
                throw ApiException.conflict("该检验项已存在检验结果，不能建立平行测定轮次: " + req.testItem());
            }
            List<BatchRepository.AssayRoundRow> rounds = repo.findAssayRounds(batchKey, req.testItem());
            if (rounds.size() != req.expectedVersion()) {
                throw ApiException.conflict("expectedVersion 不符: 期望 " + req.expectedVersion()
                        + "，当前轮次数 " + rounds.size());
            }
            if (rounds.size() >= MAX_ROUNDS_PER_ITEM) {
                throw ApiException.conflict("该检验项已达最大轮次数 " + MAX_ROUNDS_PER_ITEM);
            }
            if (!rounds.isEmpty() && ROUND_OPEN.equals(rounds.get(rounds.size() - 1).status())) {
                throw ApiException.conflict("当前轮次未封结，不能开启新轮次");
            }
            String now = now();
            int roundNo = rounds.size() + 1;
            long roundId = repo.insertAssayRound(new BatchRepository.AssayRoundRow(0L, batchKey,
                    req.testItem(), roundNo, req.meanMin(), req.meanMax(), req.maxRange(),
                    ROUND_OPEN, 0, null, null, actor, now, null));
            // 新轮开始：该项旧批准失效，不能用旧轮合格授权新轮；批次状态随之回退
            repo.invalidateApprovals(batchKey);
            BatchStatus newStatus = recomputeAssayStatus(batchKey);
            if (newStatus != status) {
                repo.updateStatus(batchKey, newStatus.name());
            }
            BatchRepository.AssayRoundRow opened = new BatchRepository.AssayRoundRow(roundId,
                    batchKey, req.testItem(), roundNo, req.meanMin(), req.meanMax(), req.maxRange(),
                    ROUND_OPEN, 0, null, null, actor, now, null);
            return new StoredResponse(201, toJson(toAssayRoundResponse(opened, newStatus)));
        });
    }

    /**
     * 写入平行测定读数：写入该检验项当前 OPEN 轮次；expectedVersion 须等于该轮已写入读数条数；
     * 序号 1～3 轮内唯一，封结后不能覆写；写满 3 条即封结判定——均值（不舍入）落在闭区间
     * 且最大减最小不超过极差才合格。判定通过可能推动批次进入 PENDING_RELEASE。
     */
    public StoredResponse writeAssayReading(String batchKey, String actorId,
                                            WriteAssayReadingRequest req) {
        String actor = requireActor(actorId);
        String fingerprint = structuredFingerprint("assay.read", actor, batchKey, req.testItem(),
                "seq", String.valueOf(req.seq()), "value", req.value(),
                "expectedVersion", String.valueOf(req.expectedVersion()));
        return executeIdempotent(CMD_WRITE_READING, req.requestId(), fingerprint, () -> {
            BatchRepository.BatchRow batch = repo.findBatchForUpdate(batchKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
            // 批次行锁后重查命令快照：并发同键请求在锁等待期间可能已由对方提交，须重放首次结果
            var logged = loggedResponse(CMD_WRITE_READING, req.requestId(), fingerprint);
            if (logged.isPresent()) {
                return logged.get();
            }
            BatchStatus status = BatchStatus.valueOf(batch.status());
            assertNoRecalledAncestor(batchKey);
            if (status == BatchStatus.REJECTED || status == BatchStatus.RELEASED
                    || status == BatchStatus.RECALLED || status == BatchStatus.SPLIT) {
                throw ApiException.conflict("批次状态 " + status + " 不允许写入平行测定读数");
            }
            List<BatchRepository.AssayRoundRow> rounds = repo.findAssayRounds(batchKey, req.testItem());
            if (rounds.isEmpty()) {
                throw ApiException.conflict("该检验项不存在平行测定轮次: " + req.testItem());
            }
            BatchRepository.AssayRoundRow current = rounds.get(rounds.size() - 1);
            if (!ROUND_OPEN.equals(current.status())) {
                throw ApiException.conflict("当前轮次已封结，读数不能覆写");
            }
            if (current.version() != req.expectedVersion()) {
                throw ApiException.conflict("expectedVersion 不符: 期望 " + req.expectedVersion()
                        + "，当前轮次版本 " + current.version());
            }
            List<BatchRepository.AssayReadingRow> readings = repo.findAssayReadings(current.id());
            boolean seqTaken = readings.stream().anyMatch(r -> r.seq() == req.seq());
            if (seqTaken) {
                throw ApiException.conflict("读数序号已写入，不能覆写: " + req.seq());
            }
            String now = now();
            repo.insertAssayReading(new BatchRepository.AssayReadingRow(0L, current.id(), batchKey,
                    req.testItem(), req.seq(), req.value(), actor, now));
            int newVersion = current.version() + 1;
            BatchRepository.AssayRoundRow updated = new BatchRepository.AssayRoundRow(current.id(),
                    current.batchKey(), current.testItem(), current.roundNo(), current.meanMin(),
                    current.meanMax(), current.maxRange(), current.status(), newVersion,
                    current.meanValue(), current.rangeValue(), current.createdBy(),
                    current.createdAt(), current.sealedAt());
            BatchStatus newStatus = status;
            if (newVersion >= READINGS_PER_ROUND) {
                List<BigDecimal> values = new ArrayList<>(readings.size() + 1);
                for (BatchRepository.AssayReadingRow r : readings) {
                    values.add(new BigDecimal(r.value()));
                }
                values.add(new BigDecimal(req.value()));
                BigDecimal sum = BigDecimal.ZERO;
                BigDecimal max = values.get(0);
                BigDecimal min = values.get(0);
                for (BigDecimal v : values) {
                    sum = sum.add(v);
                    max = max.max(v);
                    min = min.min(v);
                }
                // 均值不舍入、不取整，按全精度与闭区间比较；极差为最大减最小
                BigDecimal mean = sum.divide(BigDecimal.valueOf(READINGS_PER_ROUND),
                        MathContext.DECIMAL128);
                BigDecimal range = max.subtract(min);
                boolean passed = mean.compareTo(new BigDecimal(current.meanMin())) >= 0
                        && mean.compareTo(new BigDecimal(current.meanMax())) <= 0
                        && range.compareTo(new BigDecimal(current.maxRange())) <= 0;
                String sealStatus = passed ? ROUND_PASSED : ROUND_FAILED;
                String meanText = mean.stripTrailingZeros().toPlainString();
                String rangeText = range.stripTrailingZeros().toPlainString();
                repo.sealAssayRound(current.id(), sealStatus, newVersion, meanText, rangeText, now);
                updated = new BatchRepository.AssayRoundRow(current.id(), current.batchKey(),
                        current.testItem(), current.roundNo(), current.meanMin(), current.meanMax(),
                        current.maxRange(), sealStatus, newVersion, meanText, rangeText,
                        current.createdBy(), current.createdAt(), now);
                newStatus = recomputeAssayStatus(batchKey);
                if (newStatus != status) {
                    repo.updateStatus(batchKey, newStatus.name());
                }
            } else {
                repo.bumpAssayRoundVersion(current.id(), newVersion);
            }
            return new StoredResponse(201, toJson(toAssayRoundResponse(updated, newStatus)));
        });
    }

    /**
     * 批次全部平行测定轮次（含读数原值），按检验项与轮次序号排序。
     */
    public List<AssayRoundResponse> listAssayRounds(String batchKey) {
        BatchRepository.BatchRow batch = repo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        BatchStatus status = BatchStatus.valueOf(batch.status());
        return repo.findAllAssayRounds(batchKey).stream()
                .map(r -> toAssayRoundResponse(r, status))
                .toList();
    }

    /**
     * 平行测定变更后的批次状态重算：必做项未全部通过回退 QUARANTINED；
     * 全部通过后按有效批准数推导 PENDING_RELEASE/RELEASE_REVIEW/RELEASED。
     */
    private BatchStatus recomputeAssayStatus(String batchKey) {
        List<String> required = repo.findRequiredTests(batchKey);
        if (!allRequiredPassed(batchKey, required)) {
            return BatchStatus.QUARANTINED;
        }
        int validApprovals = repo.findValidApprovals(batchKey).size();
        if (validApprovals >= 2) {
            return BatchStatus.RELEASED;
        }
        if (validApprovals == 1) {
            return BatchStatus.RELEASE_REVIEW;
        }
        return BatchStatus.PENDING_RELEASE;
    }

    /**
     * 轮次响应装配：附带该轮全部读数原值（按序号升序）。
     */
    private AssayRoundResponse toAssayRoundResponse(BatchRepository.AssayRoundRow round,
                                                    BatchStatus batchStatus) {
        List<AssayRoundResponse.ReadingView> readings = repo.findAssayReadings(round.id()).stream()
                .map(r -> new AssayRoundResponse.ReadingView(r.seq(), r.value()))
                .toList();
        return new AssayRoundResponse(round.batchKey(), round.testItem(), round.roundNo(),
                round.status(), round.version(), round.meanMin(), round.meanMax(), round.maxRange(),
                readings, round.meanValue(), round.rangeValue(), batchStatus.name(),
                Instant.parse(round.createdAt()),
                round.sealedAt() == null ? null : Instant.parse(round.sealedAt()));
    }

    /**
     * 批准时冻结的平行测定快照：各平行项当前轮次号、三条原值、均值与极差；无平行项返回 null。
     */
    private String assaySnapshotJson(String batchKey) {
        List<BatchRepository.AssayRoundRow> rounds = repo.findAllAssayRounds(batchKey);
        if (rounds.isEmpty()) {
            return null;
        }
        var root = objectMapper.createArrayNode();
        for (BatchRepository.AssayRoundRow round : rounds) {
            var node = root.addObject();
            node.put("testItem", round.testItem());
            node.put("roundNo", round.roundNo());
            node.put("status", round.status());
            if (round.meanValue() != null) {
                node.put("mean", round.meanValue());
                node.put("range", round.rangeValue());
            }
            var values = node.putArray("readings");
            for (BatchRepository.AssayReadingRow r : repo.findAssayReadings(round.id())) {
                values.addObject().put("seq", r.seq()).put("value", r.value());
            }
        }
        return root.toString();
    }

    /**
     * 结构化命令指纹：操作、操作者、资源与全部参数（含期望版本）按键值对写入 JSON 再取摘要，
     * 避免字符串拼接的分隔符碰撞。
     */
    private String structuredFingerprint(String operation, String actor, String batchKey,
                                         String testItem, String... params) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("operation", operation);
        node.put("actor", actor);
        node.put("batchKey", batchKey);
        node.put("testItem", testItem);
        for (int i = 0; i + 1 < params.length; i += 2) {
            node.put(params[i], params[i + 1]);
        }
        return fingerprint(node.toString());
    }

    private String requireActor(String actorId) {
        if (actorId == null || actorId.isBlank()) {
            throw ApiException.badRequest("X-Actor-Id 不能为空");
        }
        return actorId.trim();
    }
}
