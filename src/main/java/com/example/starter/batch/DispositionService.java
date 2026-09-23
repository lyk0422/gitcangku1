package com.example.starter.batch;

import com.example.starter.batch.dto.ClosureEntryResponse;
import com.example.starter.batch.dto.DispositionBatchResponse;
import com.example.starter.batch.dto.DispositionCancelRequest;
import com.example.starter.batch.dto.DispositionConfirmRequest;
import com.example.starter.batch.dto.DispositionRejectRequest;
import com.example.starter.batch.dto.DispositionResponse;
import com.example.starter.batch.dto.DispositionSubmitRequest;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * 召回血缘闭包的分区处置与双人落账核心服务。
 *
 * <p>提交（质量负责人）时对 RECALLED 祖先计算包含自身的当前后代闭包，冻结每个批次的
 * 版本、状态及到祖先的完整路径；DESTROY/REWORK/HOLD 三集合互斥且并集恰为闭包，否则 422。
 *
 * <p>确认（不同的生产负责人）时在同一事务内：锁定祖先既有处置单、锁定闭包全部批次行、
 * 重算闭包并比对成员/状态/版本/路径，任一变化（期间拆分、再次召回等）返回 409；
 * 全部通过后按分类推进批次（DESTROYED 终态、REWORK_PENDING、HOLD 保持隔离并记录原因），
 * 任一批次不允许该流转或版本 CAS 失败则整单回滚。
 *
 * <p>命令幂等：disposition_command_log 主键 + 规范化参数指纹（三集合排序后参与摘要，换序同参），
 * 同键同参重放返回首次快照，同键异参 409，失败不写入、不占键；dispositionKey 全局唯一。
 */
@Service
public class DispositionService {

    private static final String CMD_SUBMIT = "SUBMIT_DISPOSITION";
    private static final String CMD_CONFIRM = "CONFIRM_DISPOSITION";
    private static final String CMD_REJECT = "REJECT_DISPOSITION";
    private static final String CMD_CANCEL = "CANCEL_DISPOSITION";

    /**
     * 指纹拼接分隔符（NUL）：业务参数不可能包含该字符，避免拼接碰撞。
     */
    private static final String SEP = "\u0000";

    private static final int IDEMPOTENCY_MAX_ATTEMPTS = 3;

    private final BatchRepository repo;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper;

    public DispositionService(BatchRepository repo,
                              PlatformTransactionManager transactionManager,
                              ObjectMapper objectMapper) {
        this.repo = repo;
        this.tx = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    /**
     * 一审提交：祖先必须为 RECALLED；三集合互斥且并集恰为当前闭包。
     * 冻结版本/状态/路径与分类快照，处置单进入 SUBMITTED；不改变任何批次。
     */
    public StoredResponse submit(String actorId, DispositionSubmitRequest request) {
        String actor = requireActor(actorId);
        List<String> destroy = normalized(request.destroy());
        List<String> rework = normalized(request.rework());
        List<String> hold = normalized(request.hold());
        // 指纹对三集合排序后规范化：集合内部换序视为同参。
        String fingerprint = fingerprint("submit", request.dispositionKey(), request.ancestorKey(),
                actor, sortedCsv(destroy), sortedCsv(rework), sortedCsv(hold),
                request.holdReason() == null ? "" : request.holdReason().trim());
        return executeIdempotent(CMD_SUBMIT, request.commandKey(), fingerprint, () -> {
            BatchRepository.BatchRow ancestor = repo.findBatchForUpdate(request.ancestorKey())
                    .orElseThrow(() -> ApiException.notFound("祖先批次不存在: " + request.ancestorKey()));
            // 祖先行锁后重查命令快照：并发同键提交在锁等待期间可能已由对方提交，
            // 此时须重放首次结果而不是误判“已有待二审处置单”。
            Optional<StoredResponse> logged = loggedResponse(CMD_SUBMIT, request.commandKey(), fingerprint);
            if (logged.isPresent()) {
                return logged.get();
            }
            if (!BatchStatus.RECALLED.name().equals(ancestor.status())) {
                throw ApiException.unprocessable(
                        "仅 RECALLED 批次可创建召回处置单，当前状态: " + ancestor.status());
            }
            // 同一祖先只允许一个未终态处置单；已确认/拒绝/取消后可重新提交。
            repo.findDispositionByAncestorForUpdate(request.ancestorKey()).ifPresent(existing -> {
                if (DispositionStatus.SUBMITTED.name().equals(existing.status())) {
                    throw ApiException.conflict(
                            "该祖先已有待二审处置单: " + existing.dispositionKey());
                }
            });
            if (repo.findDisposition(request.dispositionKey()).isPresent()) {
                throw ApiException.conflict("dispositionKey 已存在: " + request.dispositionKey());
            }

            Map<String, List<String>> childrenOf = childrenOf();
            List<String> closure = closureKeys(request.ancestorKey(), childrenOf);
            Map<String, List<String>> pathOf = pathsOf(request.ancestorKey(), closure, childrenOf);
            Map<String, Integer> depthOf = depthOf(request.ancestorKey(), pathOf);

            Map<String, DispositionCategory> classification = validatePartition(closure,
                    destroy, rework, hold, request.holdReason());

            // 行锁冻结闭包全部批次（按业务键排序保证锁顺序确定）。
            List<String> locked = new ArrayList<>(closure);
            Collections.sort(locked);
            Map<String, BatchRepository.BatchRow> rows = new LinkedHashMap<>();
            for (String key : locked) {
                BatchRepository.BatchRow row = repo.findBatchForUpdate(key)
                        .orElseThrow(() -> ApiException.notFound("闭包批次不存在: " + key));
                rows.put(key, row);
            }

            String now = now();
            int version = repo.countDispositionsByAncestor(request.ancestorKey()) + 1;
            String holdReason = hold.isEmpty() ? null : request.holdReason().trim();
            repo.insertDisposition(new BatchRepository.DispositionRow(0L, request.dispositionKey(),
                    request.ancestorKey(), DispositionStatus.SUBMITTED.name(), actor, version,
                    null, null, null, holdReason, now, null, null, null));

            List<DispositionBatchResponse> batches = new ArrayList<>(closure.size());
            for (int i = 0; i < closure.size(); i++) {
                String key = closure.get(i);
                BatchRepository.BatchRow row = rows.get(key);
                DispositionCategory category = classification.get(key);
                List<String> path = pathOf.get(key);
                int seq = i + 1;
                repo.insertDispositionBatch(new BatchRepository.DispositionBatchRow(0L,
                        request.dispositionKey(), key, category.name(), row.status(), row.version(),
                        toJson(path), depthOf.get(key), seq, now));
                batches.add(new DispositionBatchResponse(key, category, row.status(), row.version(),
                        path, depthOf.get(key), seq));
            }
            DispositionResponse body = new DispositionResponse(request.dispositionKey(),
                    request.ancestorKey(), DispositionStatus.SUBMITTED, actor, version,
                    null, null, null, holdReason, Instant.parse(now), null, null, null, batches);
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 二审确认：重算闭包并比对冻结快照，携带 expectedDispositionVersion 与全部批次版本；
     * 任一变化 409；全部通过后在同一事务内按分类落账，任一批次流转非法则整单回滚。
     */
    public StoredResponse confirm(String dispositionKey, String actorId,
                                  DispositionConfirmRequest request) {
        String actor = requireActor(actorId);
        // 全部批次版本以 batchKey 排序后规范化参与指纹。
        String fingerprint = fingerprint("confirm", dispositionKey, actor,
                String.valueOf(request.expectedDispositionVersion()), sortedMapCsv(request.batchVersions()));
        return executeIdempotent(CMD_CONFIRM, request.commandKey(), fingerprint, () -> {
            BatchRepository.DispositionRow order = lockOrder(dispositionKey);
            // 处置单行锁后立即重查命令快照：并发同键确认在锁等待期间可能已由对方落账提交，
            // 此时须重放首次结果，而不是因状态已 CONFIRMED 误判 409。
            Optional<StoredResponse> earlyLogged = loggedResponse(CMD_CONFIRM, request.commandKey(), fingerprint);
            if (earlyLogged.isPresent()) {
                return earlyLogged.get();
            }
            if (!DispositionStatus.SUBMITTED.name().equals(order.status())) {
                throw ApiException.conflict("处置单状态 " + order.status() + " 不允许确认");
            }
            if (order.submittedBy().equals(actor)) {
                throw ApiException.unprocessable("二审确认人必须不同于提交人: " + actor);
            }
            if (order.dispositionVersion() != request.expectedDispositionVersion()) {
                throw ApiException.conflict("expectedDispositionVersion 不匹配：期望 "
                        + order.dispositionVersion() + "，请求 " + request.expectedDispositionVersion());
            }
            List<BatchRepository.DispositionBatchRow> frozen =
                    repo.findDispositionBatches(dispositionKey);

            // 锁定祖先行：与祖先上的再次召回互斥；随后锁定闭包全部批次行，与拆分/状态流转互斥。
            repo.findBatchForUpdate(order.ancestorKey());
            List<String> lockKeys = frozen.stream().map(BatchRepository.DispositionBatchRow::batchKey)
                    .sorted().toList();
            Map<String, BatchRepository.BatchRow> currentRows = new LinkedHashMap<>();
            for (String key : lockKeys) {
                currentRows.put(key, repo.findBatchForUpdate(key)
                        .orElseThrow(() -> ApiException.notFound("闭包批次不存在: " + key)));
            }

            // 行锁后重查命令快照：锁等待期间并发同键确认可能已提交。
            Optional<StoredResponse> logged = loggedResponse(CMD_CONFIRM, request.commandKey(), fingerprint);
            if (logged.isPresent()) {
                return logged.get();
            }

            // 重算闭包：成员（是否发生拆分）、状态/版本、路径任一变化均 409。
            Map<String, List<String>> childrenOf = childrenOf();
            List<String> currentClosure = closureKeys(order.ancestorKey(), childrenOf);
            if (!new HashSet<>(currentClosure).equals(
                    new HashSet<>(frozen.stream().map(BatchRepository.DispositionBatchRow::batchKey).toList()))) {
                throw ApiException.conflict("提交后闭包成员发生变化（可能期间发生拆分），请重新提交处置单");
            }
            for (BatchRepository.DispositionBatchRow snap : frozen) {
                BatchRepository.BatchRow current = currentRows.get(snap.batchKey());
                if (current.version() != snap.frozenVersion()) {
                    throw ApiException.conflict(
                            "批次 " + snap.batchKey() + " 版本已变化：提交时 " + snap.frozenVersion()
                                    + "，当前 " + current.version());
                }
                if (!current.status().equals(snap.frozenStatus())) {
                    throw ApiException.conflict(
                            "批次 " + snap.batchKey() + " 状态已变化：提交时 " + snap.frozenStatus()
                                    + "，当前 " + current.status());
                }
                List<String> currentPath = pathsOf(order.ancestorKey(), currentClosure, childrenOf)
                        .get(snap.batchKey());
                List<String> frozenPath = fromJsonList(snap.path());
                if (!currentPath.equals(frozenPath)) {
                    throw ApiException.conflict(
                            "批次 " + snap.batchKey() + " 到祖先的路径已变化，请重新提交处置单");
                }
                Long submittedVersion = request.batchVersions().get(snap.batchKey());
                if (submittedVersion == null) {
                    throw ApiException.conflict(
                            "batchVersions 缺少闭包批次版本: " + snap.batchKey());
                }
                if (submittedVersion != current.version()) {
                    throw ApiException.conflict(
                            "批次 " + snap.batchKey() + " 携带版本 " + submittedVersion
                                    + " 与当前版本 " + current.version() + " 不一致");
                }
            }
            if (request.batchVersions().size() != frozen.size()) {
                throw ApiException.conflict("batchVersions 包含非闭包批次或存在多余条目");
            }

            // 同一事务内按分类推进全部批次；任一 CAS 失败（并发改动）整单回滚。
            String now = now();
            for (BatchRepository.DispositionBatchRow snap : frozen) {
                DispositionCategory category = DispositionCategory.valueOf(snap.category());
                long expected = snap.frozenVersion();
                int affected;
                switch (category) {
                    case DESTROY -> {
                        assertTransition(snap, BatchStatus.DESTROYED);
                        affected = repo.applyDispositionStatus(snap.batchKey(), expected,
                                BatchStatus.DESTROYED.name());
                    }
                    case REWORK -> {
                        assertTransition(snap, BatchStatus.REWORK_PENDING);
                        affected = repo.applyDispositionStatus(snap.batchKey(), expected,
                                BatchStatus.REWORK_PENDING.name());
                    }
                    case HOLD -> affected = repo.bumpVersion(snap.batchKey(), expected);
                    default -> throw new IllegalStateException("未知分类: " + category);
                }
                if (affected == 0) {
                    throw ApiException.conflict(
                            "批次 " + snap.batchKey() + " 版本已被并发修改，处置整单回滚");
                }
            }
            repo.updateDispositionConfirmed(dispositionKey, actor, now);
            DispositionResponse body = toResponse(
                    new BatchRepository.DispositionRow(order.id(), order.dispositionKey(),
                            order.ancestorKey(), DispositionStatus.CONFIRMED.name(), order.submittedBy(),
                            order.dispositionVersion(), actor, null, null, order.holdReason(),
                            order.submittedAt(), now, null, null),
                    frozen);
            return new StoredResponse(200, toJson(body));
        });
    }

    /**
     * 二审拒绝：仅待二审处置单可由非提交人拒绝；拒绝不改任何批次。
     */
    public StoredResponse reject(String dispositionKey, String actorId,
                                 DispositionRejectRequest request) {
        String actor = requireActor(actorId);
        String fingerprint = fingerprint("reject", dispositionKey, actor, request.reason().trim());
        return executeIdempotent(CMD_REJECT, request.commandKey(), fingerprint, () -> {
            BatchRepository.DispositionRow order = lockOrder(dispositionKey);
            // 处置单行锁后重查命令快照：并发同键拒绝在锁等待期间可能已由对方提交。
            Optional<StoredResponse> logged = loggedResponse(CMD_REJECT, request.commandKey(), fingerprint);
            if (logged.isPresent()) {
                return logged.get();
            }
            if (!DispositionStatus.SUBMITTED.name().equals(order.status())) {
                throw ApiException.conflict("处置单状态 " + order.status() + " 不允许拒绝");
            }
            if (order.submittedBy().equals(actor)) {
                throw ApiException.unprocessable("二审拒绝人必须不同于提交人: " + actor);
            }
            String now = now();
            repo.updateDispositionRejected(dispositionKey, actor, request.reason().trim(), now);
            DispositionResponse body = toResponse(
                    new BatchRepository.DispositionRow(order.id(), order.dispositionKey(),
                            order.ancestorKey(), DispositionStatus.REJECTED.name(), order.submittedBy(),
                            order.dispositionVersion(), null, actor, request.reason().trim(),
                            order.holdReason(), order.submittedAt(), null, now, null),
                    repo.findDispositionBatches(dispositionKey));
            return new StoredResponse(200, toJson(body));
        });
    }

    /**
     * 确认前取消：仅 SUBMITTED 处置单可由提交人本人取消；取消不改任何批次。
     */
    public StoredResponse cancel(String dispositionKey, String actorId,
                                 DispositionCancelRequest request) {
        String actor = requireActor(actorId);
        String fingerprint = fingerprint("cancel", dispositionKey, actor);
        return executeIdempotent(CMD_CANCEL, request.commandKey(), fingerprint, () -> {
            BatchRepository.DispositionRow order = lockOrder(dispositionKey);
            // 处置单行锁后重查命令快照：并发同键取消在锁等待期间可能已由对方提交。
            Optional<StoredResponse> logged = loggedResponse(CMD_CANCEL, request.commandKey(), fingerprint);
            if (logged.isPresent()) {
                return logged.get();
            }
            if (!DispositionStatus.SUBMITTED.name().equals(order.status())) {
                throw ApiException.conflict("处置单状态 " + order.status() + " 不允许取消");
            }
            if (!order.submittedBy().equals(actor)) {
                throw ApiException.unprocessable("仅提交人可在确认前取消处置单: " + actor);
            }
            String now = now();
            repo.updateDispositionCancelled(dispositionKey, now);
            DispositionResponse body = toResponse(
                    new BatchRepository.DispositionRow(order.id(), order.dispositionKey(),
                            order.ancestorKey(), DispositionStatus.CANCELLED.name(), order.submittedBy(),
                            order.dispositionVersion(), null, null, null, order.holdReason(),
                            order.submittedAt(), null, null, now),
                    repo.findDispositionBatches(dispositionKey));
            return new StoredResponse(200, toJson(body));
        });
    }

    /**
     * 处置单只读查询：含冻结的闭包批次版本/状态/路径与分类快照。
     */
    public DispositionResponse getDisposition(String dispositionKey) {
        BatchRepository.DispositionRow order = repo.findDisposition(dispositionKey)
                .orElseThrow(() -> ApiException.notFound("处置单不存在: " + dispositionKey));
        return toResponse(order, repo.findDispositionBatches(dispositionKey));
    }

    /**
     * 当前后代闭包（含祖先自身）只读查询：祖先须为 RECALLED；按血缘创建顺序广度优先展开。
     */
    public List<ClosureEntryResponse> closure(String ancestorKey) {
        BatchRepository.BatchRow ancestor = repo.findBatch(ancestorKey)
                .orElseThrow(() -> ApiException.notFound("祖先批次不存在: " + ancestorKey));
        if (!BatchStatus.RECALLED.name().equals(ancestor.status())) {
            throw ApiException.unprocessable(
                    "仅 RECALLED 批次可查询召回闭包，当前状态: " + ancestor.status());
        }
        Map<String, List<String>> childrenOf = childrenOf();
        List<String> closure = closureKeys(ancestorKey, childrenOf);
        Map<String, List<String>> pathOf = pathsOf(ancestorKey, closure, childrenOf);
        Map<String, Integer> depthOf = depthOf(ancestorKey, pathOf);
        List<ClosureEntryResponse> result = new ArrayList<>(closure.size());
        for (int i = 0; i < closure.size(); i++) {
            String key = closure.get(i);
            BatchRepository.BatchRow row = repo.findBatch(key)
                    .orElseThrow(() -> ApiException.notFound("闭包批次不存在: " + key));
            result.add(new ClosureEntryResponse(row.batchKey(), row.batchNo(),
                    BatchStatus.valueOf(row.status()), row.version(), pathOf.get(key),
                    depthOf.get(key), i + 1));
        }
        return result;
    }

    // ---------- 闭包与路径 ----------

    /**
     * 全部血缘边的 父批→子批列表 映射；子批按血缘关系创建顺序排列，关系只增不改。
     */
    private Map<String, List<String>> childrenOf() {
        Map<String, List<String>> childrenOf = new LinkedHashMap<>();
        for (BatchRepository.LineageRow row : repo.findAllLineage()) {
            childrenOf.computeIfAbsent(row.parentKey(), k -> new ArrayList<>()).add(row.childKey());
        }
        return childrenOf;
    }

    /**
     * 祖先自身 + 全部后代，按血缘创建顺序广度优先展开，祖先排第 1。
     */
    private List<String> closureKeys(String ancestorKey, Map<String, List<String>> childrenOf) {
        List<String> result = new ArrayList<>();
        List<String> queue = new ArrayList<>();
        result.add(ancestorKey);
        queue.add(ancestorKey);
        int head = 0;
        while (head < queue.size()) {
            List<String> children = childrenOf.get(queue.get(head));
            if (children != null) {
                for (String child : children) {
                    result.add(child);
                    queue.add(child);
                }
            }
            head++;
        }
        return result;
    }

    /**
     * 每个闭包成员到祖先的完整路径：祖先为 [祖先]，子代为 父路径 + 自身。
     */
    private Map<String, List<String>> pathsOf(String ancestorKey, List<String> closure,
                                              Map<String, List<String>> childrenOf) {
        Map<String, String> parentOf = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : childrenOf.entrySet()) {
            for (String child : e.getValue()) {
                parentOf.put(child, e.getKey());
            }
        }
        Map<String, List<String>> pathOf = new LinkedHashMap<>();
        for (String key : closure) {
            List<String> reversed = new ArrayList<>();
            String current = key;
            reversed.add(current);
            while (parentOf.containsKey(current)) {
                current = parentOf.get(current);
                reversed.add(current);
            }
            Collections.reverse(reversed);
            if (!ancestorKey.equals(reversed.get(0))) {
                throw ApiException.unprocessable("批次 " + key + " 不在祖先 " + ancestorKey + " 的血缘链上");
            }
            pathOf.put(key, List.copyOf(reversed));
        }
        return pathOf;
    }

    private Map<String, Integer> depthOf(String ancestorKey, Map<String, List<String>> pathOf) {
        Map<String, Integer> depthOf = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : pathOf.entrySet()) {
            depthOf.put(e.getKey(), e.getValue().size() - 1);
        }
        depthOf.putIfAbsent(ancestorKey, 0);
        return depthOf;
    }

    // ---------- 校验 ----------

    /**
     * 校验三集合互斥、并集恰为闭包、只含闭包成员；HOLD 非空时必须给出原因。
     * 重复、遗漏、多余、跨链分类或互斥被破坏均 422。
     */
    private Map<String, DispositionCategory> validatePartition(List<String> closure,
                                                                List<String> destroy,
                                                                List<String> rework,
                                                                List<String> hold,
                                                                String holdReason) {
        if (!hold.isEmpty() && (holdReason == null || holdReason.isBlank())) {
            throw ApiException.unprocessable("存在 HOLD 批次时 holdReason 不能为空");
        }
        if (new HashSet<>(destroy).size() != destroy.size()
                || new HashSet<>(rework).size() != rework.size()
                || new HashSet<>(hold).size() != hold.size()) {
            throw ApiException.unprocessable("DESTROY/REWORK/HOLD 集合内部不允许重复批次");
        }
        Set<String> closureSet = new HashSet<>(closure);
        Set<String> union = new HashSet<>();
        Map<String, DispositionCategory> classification = new LinkedHashMap<>();
        putClassification(union, classification, destroy, DispositionCategory.DESTROY, closureSet);
        putClassification(union, classification, rework, DispositionCategory.REWORK, closureSet);
        putClassification(union, classification, hold, DispositionCategory.HOLD, closureSet);
        if (!union.equals(closureSet)) {
            Set<String> missing = new HashSet<>(closureSet);
            missing.removeAll(union);
            Set<String> extra = new HashSet<>(union);
            extra.removeAll(closureSet);
            throw ApiException.unprocessable("三集合并集必须恰好等于闭包；遗漏: " + missing
                    + "，多余/非召回链: " + extra);
        }
        return classification;
    }

    private void putClassification(Set<String> union, Map<String, DispositionCategory> classification,
                                   List<String> keys, DispositionCategory category, Set<String> closureSet) {
        for (String key : keys) {
            if (!closureSet.contains(key)) {
                throw ApiException.unprocessable(
                        category + " 包含非召回链闭包批次: " + key);
            }
            if (!union.add(key)) {
                throw ApiException.unprocessable(
                        "批次 " + key + " 被多个分类重复划分，DESTROY/REWORK/HOLD 必须互斥");
            }
            classification.put(key, category);
        }
    }

    /**
     * 落账前校验批次当前状态是否允许进入目标状态；不允许则抛 409 触发整单回滚。
     * DESTROYED 为终态；REWORK_PENDING 仅允许从非终态批次进入。
     */
    private void assertTransition(BatchRepository.DispositionBatchRow snap, BatchStatus target) {
        BatchStatus current;
        try {
            current = BatchStatus.valueOf(snap.frozenStatus());
        } catch (IllegalArgumentException e) {
            throw ApiException.conflict(
                    "批次 " + snap.batchKey() + " 状态 " + snap.frozenStatus() + " 无法处置");
        }
        boolean allowed = switch (target) {
            case DESTROYED -> current != BatchStatus.DESTROYED && current != BatchStatus.REWORK_PENDING;
            case REWORK_PENDING -> current != BatchStatus.DESTROYED
                    && current != BatchStatus.REWORK_PENDING
                    && current != BatchStatus.REJECTED;
            default -> false;
        };
        if (!allowed) {
            throw ApiException.conflict(
                    "批次 " + snap.batchKey() + " 状态 " + current + " 不允许流转到 " + target);
        }
    }

    // ---------- 幂等 ----------

    /**
     * 幂等执行：事务内先查命令日志，命中则按指纹返回快照或 409；
     * 未命中执行业务动作并写入快照。并发同键插入冲突时回滚重试，读取已提交结果。
     */
    private StoredResponse executeIdempotent(String type, String commandKey, String fingerprint,
                                             Supplier<StoredResponse> action) {
        for (int attempt = 0; attempt < IDEMPOTENCY_MAX_ATTEMPTS; attempt++) {
            try {
                return tx.execute(status -> {
                    Optional<StoredResponse> logged = loggedResponse(type, commandKey, fingerprint);
                    if (logged.isPresent()) {
                        return logged.get();
                    }
                    StoredResponse response = action.get();
                    repo.insertDispositionCommand(new BatchRepository.DispositionCommandRow(
                            type, commandKey, fingerprint, response.status(), response.body()), now());
                    return response;
                });
            } catch (DuplicateKeyException e) {
                // 并发同键冲突：回滚后重试，读取对方已提交的命令快照
            }
        }
        throw ApiException.conflict("命令并发冲突，请重试: " + commandKey);
    }

    private Optional<StoredResponse> loggedResponse(String type, String commandKey, String fingerprint) {
        Optional<BatchRepository.DispositionCommandRow> existing =
                repo.findDispositionCommand(type, commandKey);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        BatchRepository.DispositionCommandRow row = existing.get();
        if (!row.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("commandKey 已以不同参数使用: " + commandKey);
        }
        return Optional.of(new StoredResponse(row.responseStatus(), row.responseBody()));
    }

    // ---------- 辅助 ----------

    private BatchRepository.DispositionRow lockOrder(String dispositionKey) {
        return repo.findDispositionForUpdate(dispositionKey)
                .orElseThrow(() -> ApiException.notFound("处置单不存在: " + dispositionKey));
    }

    private DispositionResponse toResponse(BatchRepository.DispositionRow order,
                                           List<BatchRepository.DispositionBatchRow> rows) {
        List<DispositionBatchResponse> batches = rows.stream()
                .map(r -> new DispositionBatchResponse(r.batchKey(),
                        DispositionCategory.valueOf(r.category()), r.frozenStatus(), r.frozenVersion(),
                        fromJsonList(r.path()), r.depth(), r.seq()))
                .toList();
        return new DispositionResponse(order.dispositionKey(), order.ancestorKey(),
                DispositionStatus.valueOf(order.status()), order.submittedBy(),
                order.dispositionVersion(), order.confirmedBy(), order.rejectedBy(),
                order.rejectReason(), order.holdReason(), parseInstant(order.submittedAt()),
                parseInstant(order.confirmedAt()), parseInstant(order.rejectedAt()),
                parseInstant(order.cancelledAt()), batches);
    }

    private Instant parseInstant(String value) {
        return value == null ? null : Instant.parse(value);
    }

    private String requireActor(String actorId) {
        if (actorId == null || actorId.isBlank()) {
            throw ApiException.badRequest("X-Actor-Id 不能为空");
        }
        return actorId.trim();
    }

    private List<String> normalized(List<String> source) {
        if (source == null) {
            return List.of();
        }
        return source.stream().filter(s -> s != null && !s.isBlank()).map(String::trim).toList();
    }

    private String sortedCsv(List<String> keys) {
        return String.join(",", new TreeSet<>(keys));
    }

    private String sortedMapCsv(Map<String, Long> versions) {
        List<String> parts = new ArrayList<>();
        for (String key : new TreeSet<>(versions.keySet())) {
            parts.add(key + "=" + versions.get(key));
        }
        return String.join(",", parts);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    private List<String> fromJsonList(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("路径快照反序列化失败", e);
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
}
