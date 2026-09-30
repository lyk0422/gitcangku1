package com.example.starter.batch;

import com.example.starter.batch.dto.AllergensResponse;
import com.example.starter.batch.dto.ApprovalResponse;
import com.example.starter.batch.dto.ApproveRequest;
import com.example.starter.batch.dto.AdvanceGenerationRequest;
import com.example.starter.batch.dto.BatchHistoryResponse;
import com.example.starter.batch.dto.BatchResponse;
import com.example.starter.batch.dto.CreateBatchRequest;
import com.example.starter.batch.dto.GenerationResponse;
import com.example.starter.batch.dto.LineageEntryResponse;
import com.example.starter.batch.dto.MergeLookupResponse;
import com.example.starter.batch.dto.MergeRequest;
import com.example.starter.batch.dto.MergeResponse;
import com.example.starter.batch.dto.RecallRequest;
import com.example.starter.batch.dto.RecallResponse;
import com.example.starter.batch.dto.SplitRequest;
import com.example.starter.batch.dto.SplitResponse;
import com.example.starter.batch.dto.SubmitTestRequest;
import com.example.starter.batch.dto.TestResultResponse;
import com.example.starter.batch.dto.UpdateAllergensRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * 批次隔离与放行核心服务。
 *
 * <p>并发策略：同一批次的检验/批准/召回/清洁在事务内对 batch 行 SELECT ... FOR UPDATE 串行化，
 * 按事务提交顺序生效；合批在同一事务内按业务键排序后锁定全部父批行，
 * 与父批上的清洁代次推进、其他合批互斥，因此不可能读到“一半新代次一半旧代次”的混合版本。
 * commandKey/requestId 幂等通过 command_log 主键 + 参数指纹实现，
 * 同键同参重放返回首次响应快照，同键改参返回 409；仅成功命令写入 command_log。
 *
 * <p>过敏原集合一律规范化（trim、去空白、去重、字典序稳定排序）后逐行结构化存储，
 * 集合相等判断基于 List 逐项比较，不依赖字符串拼接；合批父批过敏原与清洁代次在落定时快照固化，
 * 后续过敏原保存或清洁推进均不改写历史依据。合批前置条件不满足返回 422，
 * 拒绝原因在独立事务落库（业务事务回滚，父批状态与数量不变），拒绝不占用 requestId。
 */
@Service
public class BatchService {

    private static final String CMD_CREATE = "CREATE_BATCH";
    private static final String CMD_TEST = "SUBMIT_TEST";
    private static final String CMD_APPROVE = "APPROVE";
    private static final String CMD_RECALL = "RECALL";
    private static final String CMD_SPLIT = "SPLIT";
    private static final String CMD_MERGE = "MERGE";
    private static final String CMD_ALLERGENS = "SAVE_ALLERGENS";
    private static final String CMD_GENERATION = "ADVANCE_GENERATION";

    private static final String RELATION_SPLIT = "SPLIT";
    private static final String RELATION_MERGE = "MERGE";

    /**
     * 指纹拼接分隔符（NUL）：业务参数不可能包含该字符，避免拼接碰撞。
     */
    private static final String SEP = "";

    private static final int IDEMPOTENCY_MAX_ATTEMPTS = 3;

    private final BatchRepository repo;
    private final TransactionTemplate tx;
    private final TransactionTemplate txRequiresNew;
    private final ObjectMapper objectMapper;

    public BatchService(BatchRepository repo,
                        PlatformTransactionManager transactionManager,
                        ObjectMapper objectMapper) {
        this.repo = repo;
        this.tx = new TransactionTemplate(transactionManager);
        this.txRequiresNew = new TransactionTemplate(transactionManager);
        this.txRequiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.objectMapper = objectMapper;
    }

    /**
     * 创建批次：初始状态 QUARANTINED；batchKey 全局唯一，重复返回 409。
     * 过敏原与清洁代次在落库前规范化（过敏原去重并字典序排序，代次缺省为 1）。
     */
    public StoredResponse createBatch(CreateBatchRequest req) {
        List<String> items = req.requiredTests().stream().map(String::trim).toList();
        if (new HashSet<>(items).size() != items.size()) {
            throw ApiException.badRequest("requiredTests 存在重复检验项");
        }
        List<String> allergens = normalizeAllergens(req.allergens());
        int generation = req.cleaningGeneration() == null ? 1 : req.cleaningGeneration();
        String fingerprint = fingerprint("create", req.batchKey(), req.productCode(), req.batchNo(),
                req.producedAt().toString(), String.join(SEP, items),
                String.join(SEP, allergens), Integer.toString(generation));
        return executeIdempotent(CMD_CREATE, req.commandKey(), fingerprint, () -> {
            repo.findBatch(req.batchKey()).ifPresent(b -> {
                throw ApiException.conflict("batchKey 已存在: " + req.batchKey());
            });
            String now = now();
            repo.insertBatch(new BatchRepository.BatchRow(0L, req.batchKey(), req.productCode(),
                    req.batchNo(), req.producedAt().toString(), BatchStatus.QUARANTINED.name(),
                    generation, now));
            for (int i = 0; i < items.size(); i++) {
                repo.insertRequiredTest(req.batchKey(), items.get(i), i + 1);
            }
            for (int i = 0; i < allergens.size(); i++) {
                repo.insertAllergen(req.batchKey(), allergens.get(i), i + 1);
            }
            BatchResponse body = new BatchResponse(req.batchKey(), req.productCode(), req.batchNo(),
                    req.producedAt(), BatchStatus.QUARANTINED, items, allergens, generation,
                    Instant.parse(now));
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
     * 当前可用批次：排除已召回（RECALLED）、已拆分（SPLIT）、已合批消耗（MERGED）批次，
     * 以及任一祖先被召回的后代批次；后代自身状态不改写。
     */
    public List<BatchResponse> listAvailable() {
        Map<String, List<String>> parentsOf = childToParents();
        Set<String> recalled = new HashSet<>(repo.findRecalledKeys());
        return repo.findAvailableBatches().stream()
                .filter(b -> !BatchStatus.SPLIT.name().equals(b.status()))
                .filter(b -> !BatchStatus.MERGED.name().equals(b.status()))
                .filter(b -> recalledAncestors(b.batchKey(), parentsOf, recalled).isEmpty())
                .map(this::toBatchResponse)
                .toList();
    }

    /**
     * 批次完整历史：批次概要 + 全部检验 + 全部批准 + 召回记录 + 合批冻结依据（仅合批子批），
     * 历史不因召回、后续过敏原保存或清洁推进而删除或改写。
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
        MergeResponse merge = repo.findMergeByChild(batchKey)
                .map(m -> toMergeResponse(m.requestId()))
                .orElse(null);
        return new BatchHistoryResponse(toBatchResponse(batch), tests, approvals, recall, merge);
    }

    /**
     * 保存批次过敏原：规范化（trim、丢弃空白、去重、字典序稳定排序）后整体替换。
     * 已合批固化的快照不受影响；空集合显式表示不含任何登记过敏原。
     */
    public StoredResponse saveAllergens(String batchKey, UpdateAllergensRequest req) {
        List<String> allergens = normalizeAllergens(req.allergens());
        String fingerprint = fingerprint("allergens", batchKey, String.join(SEP, allergens));
        return executeIdempotent(CMD_ALLERGENS, req.commandKey(), fingerprint, () -> {
            BatchRepository.BatchRow batch = repo.findBatchForUpdate(batchKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
            // 行锁后重查命令快照：并发同键请求在锁等待期间可能已由对方提交
            var logged = loggedResponse(CMD_ALLERGENS, req.commandKey(), fingerprint);
            if (logged.isPresent()) {
                return logged.get();
            }
            repo.deleteAllergens(batchKey);
            for (int i = 0; i < allergens.size(); i++) {
                repo.insertAllergen(batchKey, allergens.get(i), i + 1);
            }
            AllergensResponse body = new AllergensResponse(batchKey, allergens,
                    batch.cleaningGeneration(), Instant.parse(now()));
            return new StoredResponse(200, toJson(body));
        });
    }

    /**
     * 推进生产线清洁代次：只能严格向前；与合批在 batch 行锁上串行。
     * 推进不影响任何已固化的合批快照。
     */
    public StoredResponse advanceGeneration(String batchKey, AdvanceGenerationRequest req) {
        int target = req.newGeneration();
        String fingerprint = fingerprint("generation", batchKey, Integer.toString(target));
        return executeIdempotent(CMD_GENERATION, req.commandKey(), fingerprint, () -> {
            BatchRepository.BatchRow batch = repo.findBatchForUpdate(batchKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
            var logged = loggedResponse(CMD_GENERATION, req.commandKey(), fingerprint);
            if (logged.isPresent()) {
                return logged.get();
            }
            if (target <= batch.cleaningGeneration()) {
                throw ApiException.unprocessable("清洁代次只能向前推进：当前代次 "
                        + batch.cleaningGeneration() + "，请求代次 " + target);
            }
            repo.updateCleaningGeneration(batchKey, target);
            GenerationResponse body = new GenerationResponse(batchKey, target, Instant.parse(now()));
            return new StoredResponse(200, toJson(body));
        });
    }

    /**
     * 合批：2～10 个 RELEASED 父批合并为一个全新子批。前置条件（任一不满足整单 422，
     * 不生成子批、不改变任何父批状态与数量）：
     * <ul>
     *   <li>请求内父批键不重复；父批均存在；</li>
     *   <li>所有父批当前均为 RELEASED（任一仍隔离或已被消耗/拒绝/召回均拒绝）；</li>
     *   <li>所有父批均无已召回祖先；</li>
     *   <li>所有父批产品编码一致；</li>
     *   <li>规范化过敏原集合完全相同（结构化逐项比较，不拼接）；</li>
     *   <li>所有父批当前清洁代次相同（实际使用并固化的代次）。</li>
     * </ul>
     * 父批键换序视为同参（排序后参与指纹）；requestId 同键同参重放首次子批快照，
     * 换父批集合（不同父批键）返回 409；拒绝不占用 requestId。
     */
    public StoredResponse merge(MergeRequest req) {
        List<String> rawKeys = req.parentKeys().stream().map(String::trim).toList();
        if (new HashSet<>(rawKeys).size() != rawKeys.size()) {
            List<MergeRejection> rejections = List.of(new MergeRejection("PARENT_DUPLICATED",
                    "合批请求内父批 batchKey 重复: " + rawKeys));
            persistRejections(req.requestId(), rejections);
            throw ApiException.unprocessable(rejections.get(0).detail());
        }
        // 规范化父批集合：业务键排序，换序同参
        List<String> parentKeys = rawKeys.stream().sorted().toList();
        String fingerprint = fingerprint("merge", req.childBatchKey(), req.childBatchNo(),
                String.join(SEP, parentKeys));
        return executeIdempotent(CMD_MERGE, req.requestId(), fingerprint, () -> {
            // 按业务键排序加锁：与父批清洁推进、其他合批互斥，锁顺序全局确定避免死锁
            List<BatchRepository.BatchRow> parents = new ArrayList<>(parentKeys.size());
            for (String parentKey : parentKeys) {
                BatchRepository.BatchRow parent = repo.findBatchForUpdate(parentKey).orElse(null);
                if (parent == null) {
                    throw ApiException.notFound("父批不存在: " + parentKey);
                }
                parents.add(parent);
            }
            // 行锁后重查命令快照：并发同键请求在锁等待期间可能已由对方提交
            var logged = loggedResponse(CMD_MERGE, req.requestId(), fingerprint);
            if (logged.isPresent()) {
                return logged.get();
            }
            if (repo.findBatch(req.childBatchKey()).isPresent()) {
                throw ApiException.conflict("子批 batchKey 已存在: " + req.childBatchKey());
            }

            List<MergeRejection> rejections = validateMergeParents(req.requestId(), parents);
            if (!rejections.isEmpty()) {
                // 独立事务持久化拒绝原因：当前业务事务随 422 回滚，父批状态/数量与子批均不变
                persistRejections(req.requestId(), rejections);
                throw ApiException.unprocessable(rejections.stream()
                        .map(r -> r.code() + ": " + r.detail())
                        .reduce((a, b) -> a + "; " + b)
                        .orElse("合批前置条件不满足"));
            }

            BatchRepository.BatchRow first = parents.get(0);
            List<String> frozenAllergens = repo.findAllergens(first.batchKey());
            // 同产品父批的必做检验项一致，子批继承首父必做项，需独立完成检验与双角色放行
            List<String> requiredTests = repo.findRequiredTests(first.batchKey());
            int frozenGeneration = first.cleaningGeneration();
            String now = now();

            // 子批固化父批过敏原集合与所用清洁代次，初始 QUARANTINED，需独立完成检验放行
            repo.insertBatch(new BatchRepository.BatchRow(0L, req.childBatchKey(), first.productCode(),
                    req.childBatchNo(), now, BatchStatus.QUARANTINED.name(), frozenGeneration, now));
            for (int i = 0; i < requiredTests.size(); i++) {
                repo.insertRequiredTest(req.childBatchKey(), requiredTests.get(i), i + 1);
            }
            for (int i = 0; i < frozenAllergens.size(); i++) {
                repo.insertAllergen(req.childBatchKey(), frozenAllergens.get(i), i + 1);
            }
            // 合批单头与逐父快照（过敏原逐行固化），后续清洁/保存不改写
            repo.insertMerge(new BatchRepository.MergeRow(req.requestId(), req.childBatchKey(),
                    req.childBatchNo(), first.productCode(), frozenGeneration, now));
            for (int i = 0; i < parents.size(); i++) {
                BatchRepository.BatchRow p = parents.get(i);
                int seq = i + 1;
                repo.insertLineage(new BatchRepository.LineageRow(0L, p.batchKey(),
                        req.childBatchKey(), RELATION_MERGE, seq, now));
                repo.insertMergeParent(new BatchRepository.MergeParentRow(0L, req.requestId(),
                        p.batchKey(), p.batchNo(), p.productCode(), p.cleaningGeneration(),
                        p.status(), seq, now));
                List<String> parentAllergens = repo.findAllergens(p.batchKey());
                for (int j = 0; j < parentAllergens.size(); j++) {
                    repo.insertMergeParentAllergen(new BatchRepository.MergeParentAllergenRow(0L,
                            req.requestId(), p.batchKey(), parentAllergens.get(j), j + 1));
                }
                repo.updateStatus(p.batchKey(), BatchStatus.MERGED.name());
            }

            MergeResponse body = toMergeResponse(req.requestId());
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 按 requestId 查询合批结果：成功返回首次子批与过敏原冻结快照；
     * 仅有拒绝记录返回 REJECTED 与全部具体原因；两者皆无返回 404。
     */
    public MergeLookupResponse lookupMerge(String requestId) {
        Optional<BatchRepository.MergeRow> merged = repo.findMerge(requestId);
        if (merged.isPresent()) {
            return new MergeLookupResponse(requestId, "MERGED", toMergeResponse(requestId), List.of());
        }
        List<MergeLookupResponse.Rejection> rejections = repo.findMergeRejections(requestId).stream()
                .map(r -> new MergeLookupResponse.Rejection(r.reasonCode(), r.reasonDetail(),
                        Instant.parse(r.createdAt())))
                .toList();
        if (rejections.isEmpty()) {
            throw ApiException.notFound("合批请求不存在: " + requestId);
        }
        return new MergeLookupResponse(requestId, "REJECTED", null, rejections);
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
                row.cleaningGeneration(), Instant.parse(row.createdAt()));
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

    /**
     * 过敏原规范化：逐项 trim、丢弃空白、去重并按字典序稳定排序。
     */
    private List<String> normalizeAllergens(List<String> raw) {
        if (raw == null) {
            return List.of();
        }
        TreeSet<String> sorted = new TreeSet<>();
        for (String item : raw) {
            if (item != null && !item.isBlank()) {
                sorted.add(item.trim());
            }
        }
        return List.copyOf(sorted);
    }

    /**
     * 合批父批前置条件校验：收集全部不满足项（含涉及父批键与实际/期望值），不空判定。
     * 过敏原比较基于规范化 List 逐项 equals；清洁代次比较基于锁定行的整数值。
     */
    private List<MergeRejection> validateMergeParents(String requestId,
                                                      List<BatchRepository.BatchRow> parents) {
        List<MergeRejection> rejections = new ArrayList<>();
        Map<String, List<String>> parentsOf = childToParents();
        Set<String> recalled = new HashSet<>(repo.findRecalledKeys());

        for (BatchRepository.BatchRow p : parents) {
            BatchStatus status = BatchStatus.valueOf(p.status());
            if (status == BatchStatus.QUARANTINED
                    || status == BatchStatus.PENDING_RELEASE
                    || status == BatchStatus.RELEASE_REVIEW) {
                rejections.add(new MergeRejection("PARENT_QUARANTINED",
                        "父批 " + p.batchKey() + " 仍处于隔离/待放行状态 " + status + "，不能合批"));
            } else if (status == BatchStatus.RECALLED) {
                rejections.add(new MergeRejection("PARENT_RECALLED",
                        "父批 " + p.batchKey() + " 已召回，不能合批"));
            } else if (status == BatchStatus.REJECTED) {
                rejections.add(new MergeRejection("PARENT_REJECTED",
                        "父批 " + p.batchKey() + " 已检验拒绝，不能合批"));
            } else if (status == BatchStatus.SPLIT || status == BatchStatus.MERGED) {
                rejections.add(new MergeRejection("PARENT_ALREADY_CONSUMED",
                        "父批 " + p.batchKey() + " 已被 " + status + " 消耗，不能再次合批"));
            }
            List<String> recalledAncestors = recalledAncestors(p.batchKey(), parentsOf, recalled);
            if (!recalledAncestors.isEmpty()) {
                rejections.add(new MergeRejection("RECALLED_ANCESTOR",
                        "父批 " + p.batchKey() + " 存在已召回祖先 " + recalledAncestors + "，不能合批"));
            }
        }

        Set<String> products = new LinkedHashSet<>();
        for (BatchRepository.BatchRow p : parents) {
            products.add(p.productCode());
        }
        if (products.size() > 1) {
            rejections.add(new MergeRejection("PRODUCT_MISMATCH",
                    "父批产品编码不一致: " + products));
        }

        // 过敏原集合：以首个父批的规范化集合为基准逐项比较，不做字符串拼接
        List<String> baseline = repo.findAllergens(parents.get(0).batchKey());
        Map<String, List<String>> allergenSets = new LinkedHashMap<>();
        boolean allergenMismatch = false;
        for (BatchRepository.BatchRow p : parents) {
            List<String> set = repo.findAllergens(p.batchKey());
            allergenSets.put(p.batchKey(), set);
            if (!baseline.equals(set)) {
                allergenMismatch = true;
            }
        }
        if (allergenMismatch) {
            rejections.add(new MergeRejection("ALLERGEN_MISMATCH",
                    "父批过敏原集合不完全相同: " + allergenSets));
        }

        int baselineGeneration = parents.get(0).cleaningGeneration();
        boolean generationMismatch = parents.stream()
                .anyMatch(p -> p.cleaningGeneration() != baselineGeneration);
        if (generationMismatch) {
            Map<String, Integer> generations = new LinkedHashMap<>();
            for (BatchRepository.BatchRow p : parents) {
                generations.put(p.batchKey(), p.cleaningGeneration());
            }
            rejections.add(new MergeRejection("GENERATION_MISMATCH",
                    "父批清洁代次不一致，需处于同一有效代次: " + generations));
        }
        return rejections;
    }

    /**
     * 在独立新事务中落拒绝原因，保证随后业务事务回滚时拒绝记录仍然保留。
     */
    private void persistRejections(String requestId, List<MergeRejection> rejections) {
        txRequiresNew.executeWithoutResult(s -> {
            String now = now();
            for (MergeRejection rejection : rejections) {
                repo.insertMergeRejection(new BatchRepository.MergeRejectionRow(0L, requestId,
                        rejection.code(), rejection.detail(), now));
            }
        });
    }

    /**
     * 从快照表组装合批响应（冻结依据）：过敏原与代次读合批落定时的快照，不回算父批当前值。
     */
    private MergeResponse toMergeResponse(String requestId) {
        BatchRepository.MergeRow merge = repo.findMerge(requestId)
                .orElseThrow(() -> ApiException.notFound("合批记录不存在: " + requestId));
        Map<String, List<String>> allergenByParent = new LinkedHashMap<>();
        for (BatchRepository.MergeParentAllergenRow row : repo.findMergeParentAllergens(requestId)) {
            allergenByParent.computeIfAbsent(row.parentKey(), k -> new ArrayList<>()).add(row.allergen());
        }
        List<MergeResponse.MergeParent> parentBodies = repo.findMergeParents(requestId).stream()
                .map(p -> new MergeResponse.MergeParent(p.parentKey(), p.parentBatchNo(),
                        p.productCode(),
                        List.copyOf(allergenByParent.getOrDefault(p.parentKey(), List.of())),
                        p.cleaningGeneration(), BatchStatus.valueOf(p.status()), p.seq()))
                .toList();
        // 合批落定时全部父批过敏原集合相同，子批固化的即该集合
        List<String> frozenAllergens = parentBodies.isEmpty()
                ? List.of()
                : parentBodies.get(0).allergens();
        return new MergeResponse(merge.requestId(), merge.childKey(), merge.childBatchNo(),
                merge.productCode(), frozenAllergens, merge.cleaningGeneration(),
                BatchStatus.QUARANTINED, parentBodies, Instant.parse(merge.createdAt()));
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
     * 合批拒绝原因（内存收集后独立事务批量落库）。
     */
    private record MergeRejection(String code, String detail) {
    }

    /**
     * 拆分：仅当前可用的 RELEASED 批次可一次拆成 2～5 个全新子批。
     * 子批继承产品编码、生产 UTC 时间、必做检验项、规范化过敏原集合与当前清洁代次，
     * 初始 QUARANTINED，不继承检验或批准记录；父批置为 SPLIT 不再可用。
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
                        parent.cleaningGeneration(), now));
                for (int j = 0; j < required.size(); j++) {
                    repo.insertRequiredTest(spec.batchKey(), required.get(j), j + 1);
                }
                for (int j = 0; j < allergens.size(); j++) {
                    repo.insertAllergen(spec.batchKey(), allergens.get(j), j + 1);
                }
                repo.insertLineage(new BatchRepository.LineageRow(0L, parentKey, spec.batchKey(),
                        RELATION_SPLIT, i + 1, now));
                childBodies.add(new SplitResponse.SplitChild(spec.batchKey(), spec.batchNo(),
                        parent.productCode(), Instant.parse(parent.producedAt()),
                        BatchStatus.QUARANTINED, required, allergens,
                        parent.cleaningGeneration(), Instant.parse(now)));
            }
            repo.updateStatus(parentKey, BatchStatus.SPLIT.name());
            SplitResponse body = new SplitResponse(parentKey, BatchStatus.SPLIT, childBodies,
                    Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 祖先查询：从直接父批逐级向上到根（合批子批可有多个父批，按发现顺序去重），
     * 每项含批次自身状态及导致其不可用的召回祖先。
     */
    public List<LineageEntryResponse> listAncestors(String batchKey) {
        repo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        Map<String, List<String>> parentsOf = childToParents();
        Set<String> recalled = new HashSet<>(repo.findRecalledKeys());
        List<LineageEntryResponse> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(batchKey);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (String parent : parentsOf.getOrDefault(current, List.of())) {
                if (seen.add(parent)) {
                    result.add(toLineageEntry(parent, parentsOf, recalled));
                    queue.add(parent);
                }
            }
        }
        return result;
    }

    /**
     * 后代查询：按血缘关系创建顺序广度优先展开（合批子批挂多个父批时只出现一次），
     * 每项含批次自身状态及导致其不可用的召回祖先。
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
     * 若任一级祖先已被召回则抛 422：后代批次禁止新增检验、批准和拆分。
     */
    private void assertNoRecalledAncestor(String batchKey) {
        List<String> recalled = recalledAncestors(batchKey, childToParents(),
                new HashSet<>(repo.findRecalledKeys()));
        if (!recalled.isEmpty()) {
            throw ApiException.unprocessable(
                    "祖先批次 " + recalled + " 已召回，禁止新增检验、批准和拆分");
        }
    }

    /**
     * 沿父链（合批子批为多父）向上查找全部被直接召回（RECALLED）的祖先，按发现顺序去重；
     * 批次自身召回不算祖先召回。
     */
    private List<String> recalledAncestors(String batchKey, Map<String, List<String>> parentsOf,
                                          Set<String> recalled) {
        List<String> found = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(batchKey);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (String parent : parentsOf.getOrDefault(current, List.of())) {
                if (seen.add(parent)) {
                    if (recalled.contains(parent)) {
                        found.add(parent);
                    }
                    queue.add(parent);
                }
            }
        }
        return found;
    }

    /**
     * 全部血缘边的 子批→父批列表 映射；关系不可改写，只增不改。
     * 拆分子批只有一个父批；合批子批按 seq 排序有多个父批。
     */
    private Map<String, List<String>> childToParents() {
        Map<String, List<String>> parentsOf = new HashMap<>();
        for (BatchRepository.LineageRow row : repo.findAllLineage()) {
            parentsOf.computeIfAbsent(row.childKey(), k -> new ArrayList<>()).add(row.parentKey());
        }
        return parentsOf;
    }

    /**
     * 某批次的全部后代业务键，按血缘关系创建顺序广度优先展开（不含自身，去重）。
     */
    private List<String> descendantKeys(String rootKey) {
        Map<String, List<String>> childrenOf = new LinkedHashMap<>();
        for (BatchRepository.LineageRow row : repo.findAllLineage()) {
            childrenOf.computeIfAbsent(row.parentKey(), k -> new ArrayList<>()).add(row.childKey());
        }
        List<String> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(rootKey);
        seen.add(rootKey);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (String child : childrenOf.getOrDefault(current, List.of())) {
                if (seen.add(child)) {
                    result.add(child);
                    queue.add(child);
                }
            }
        }
        return result;
    }

    private LineageEntryResponse toLineageEntry(String batchKey, Map<String, List<String>> parentsOf,
                                                Set<String> recalled) {
        BatchRepository.BatchRow row = repo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        List<String> recalledAncestors = recalledAncestors(batchKey, parentsOf, recalled);
        return new LineageEntryResponse(row.batchKey(), row.batchNo(),
                BatchStatus.valueOf(row.status()),
                recalledAncestors.isEmpty() ? null : recalledAncestors.get(0));
    }
}
