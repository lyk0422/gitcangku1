package com.example.starter.batch;

import com.example.starter.batch.dto.ClosureEntryResponse;
import com.example.starter.batch.dto.DispositionCancelRequest;
import com.example.starter.batch.dto.DispositionConfirmRequest;
import com.example.starter.batch.dto.DispositionOrderResponse;
import com.example.starter.batch.dto.DispositionRejectRequest;
import com.example.starter.batch.dto.DispositionSnapshotResponse;
import com.example.starter.batch.dto.DispositionSubmitRequest;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 召回血缘闭包分区处置核心服务。
 *
 * <p>提交（QUALITY）：对 RECALLED 祖先计算含自身的当前后代闭包，校验 DESTROY/REWORK/HOLD
 * 三个互斥集合并集恰好等于闭包（重复/遗漏/多余/非召回链均 422），冻结每个批次的版本、状态
 * 及到祖先的完整路径，处置单进入 SUBMITTED（版本 1）。
 *
 * <p>确认（OPERATIONS，且与提交人不同）：行锁处置单与闭包全部批次后重新计算闭包，
 * 闭包集合、任一批次版本/状态、任一路径或处置单版本变化均 409；通过后在同一事务内
 * DESTROY→DESTROYED、REWORK→REWORK_PENDING、HOLD 保持状态并记录原因，写入不可变快照与
 * 批次级落账结论；任一批次不允许流转则整单回滚。拒绝/取消不改批次。
 *
 * <p>并发：处置单行锁 + 闭包批次行锁（按业务键排序）与拆分/放行/召回互斥，按提交顺序裁决，
 * 保证不漏后代、不部分落账；requestId 经 command_log 幂等，三集合内部换序为同参，异参 409，
 * 失败不占键；dispositionKey 全局唯一。
 */
@Service
public class DispositionService {

    private static final String CMD_SUBMIT = "DISPOSITION_SUBMIT";
    private static final String CMD_CONFIRM = "DISPOSITION_CONFIRM";
    private static final String CMD_REJECT = "DISPOSITION_REJECT";
    private static final String CMD_CANCEL = "DISPOSITION_CANCEL";

    /**
     * 指纹拼接分隔符（NUL）：业务参数不可能包含该字符，避免拼接碰撞。
     */
    private static final String SEP = "";

    /**
     * 路径存储分隔符；批次业务键不允许包含该字符。
     */
    private static final String PATH_SEP = "->";

    private static final int IDEMPOTENCY_MAX_ATTEMPTS = 3;

    private final BatchRepository batchRepo;
    private final DispositionRepository dispositionRepo;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper;

    public DispositionService(BatchRepository batchRepository,
                              DispositionRepository dispositionRepository,
                              PlatformTransactionManager transactionManager,
                              ObjectMapper objectMapper) {
        this.batchRepo = batchRepository;
        this.dispositionRepo = dispositionRepository;
        this.tx = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    /**
     * 提交召回处置单。
     */
    public StoredResponse submit(String ancestorKey, String actorId, String roleHeader,
                                 DispositionSubmitRequest req) {
        String actor = requireActor(actorId);
        ApprovalRole role = requireRole(roleHeader);
        if (role != ApprovalRole.QUALITY) {
            throw ApiException.badRequest("召回处置单仅可由质量负责人 QUALITY 提交");
        }
        // 三集合各自去重（顺序无关）；集合间互斥
        List<String> destroy = normalized(req.destroy());
        List<String> rework = normalized(req.rework());
        List<String> hold = normalized(req.hold());
        assertDistinctAndDisjoint(destroy, rework, hold);

        List<String> fp = new ArrayList<>();
        fp.add(ancestorKey);
        fp.add(actor);
        fp.add(req.dispositionKey());
        fp.add(req.holdReason());
        fp.add("DESTROY");
        fp.addAll(sorted(destroy));
        fp.add("REWORK");
        fp.addAll(sorted(rework));
        fp.add("HOLD");
        fp.addAll(sorted(hold));
        String fingerprint = fingerprint(fp.toArray(new String[0]));

        return executeIdempotent(CMD_SUBMIT, req.requestId(), fingerprint, () -> {
            // 先锁祖先，再锁闭包其余批次：与拆分/召回/放行及并发处置互斥，按提交顺序裁决
            BatchRepository.BatchRow ancestor = batchRepo.findBatchForUpdate(ancestorKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + ancestorKey));
            if (!BatchStatus.RECALLED.name().equals(ancestor.status())) {
                throw ApiException.unprocessable(
                        "仅可对 RECALLED 祖先提交召回处置单，当前状态: " + ancestor.status());
            }
            if (dispositionRepo.findOrder(req.dispositionKey()).isPresent()) {
                throw ApiException.conflict("dispositionKey 已存在: " + req.dispositionKey());
            }

            Map<String, List<String>> childrenOf = childrenOf();
            List<String> closure = closureKeys(ancestorKey, childrenOf);
            Map<String, String> parentOf = parentOf(childrenOf);
            // 闭包批次按业务键排序后逐行锁定，保证与其他事务的锁顺序一致
            for (String key : sorted(closure)) {
                if (!key.equals(ancestorKey)) {
                    batchRepo.findBatchForUpdate(key)
                            .orElseThrow(() -> ApiException.notFound("批次不存在: " + key));
                }
            }

            Map<String, DispositionCategory> classification = classify(destroy, rework, hold);
            assertClassificationEqualsClosure(classification.keySet(), closure, ancestorKey);

            String now = now();
            dispositionRepo.insertOrder(new DispositionRepository.OrderRow(0L,
                    req.dispositionKey(), ancestorKey, DispositionStatus.SUBMITTED.name(), 1,
                    actor, null, req.holdReason(), now, null));

            List<DispositionOrderResponse.FrozenBatch> frozen = new ArrayList<>(closure.size());
            int seq = 1;
            for (String key : closure) {
                BatchRepository.BatchRow row = batchRepo.findBatch(key)
                        .orElseThrow(() -> ApiException.notFound("批次不存在: " + key));
                List<String> path = pathToAncestor(key, ancestorKey, parentOf);
                String pathStored = joinPath(path);
                DispositionCategory category = classification.get(key);
                dispositionRepo.insertFrozenBatch(new DispositionRepository.FrozenBatchRow(0L,
                        req.dispositionKey(), key, category.name(), row.version(), row.status(),
                        pathStored, seq, now));
                frozen.add(new DispositionOrderResponse.FrozenBatch(key, category, row.version(),
                        BatchStatus.valueOf(row.status()), path, seq));
                seq++;
            }

            DispositionOrderResponse body = new DispositionOrderResponse(req.dispositionKey(),
                    ancestorKey, DispositionStatus.SUBMITTED.name(), 1, actor, null,
                    req.holdReason(), Instant.parse(now), null, frozen);
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 生产负责人二审确认：重校闭包/版本/状态/路径后，单事务按分类推进全部批次。
     */
    public StoredResponse confirm(String dispositionKey, String actorId, String roleHeader,
                                  DispositionConfirmRequest req) {
        String actor = requireActor(actorId);
        ApprovalRole role = requireRole(roleHeader);
        if (role != ApprovalRole.OPERATIONS) {
            throw ApiException.badRequest("召回处置单仅可由生产负责人 OPERATIONS 二审确认");
        }
        Map<String, Long> expectedVersions = new LinkedHashMap<>();
        for (DispositionConfirmRequest.BatchVersion bv : req.batchVersions()) {
            if (bv.version() == null || bv.version() <= 0) {
                throw ApiException.badRequest("version 必须为正数: " + bv.batchKey());
            }
            if (expectedVersions.put(bv.batchKey(), bv.version()) != null) {
                throw ApiException.unprocessable("batchVersions 存在重复批次: " + bv.batchKey());
            }
        }

        // 指纹对 batchVersions 的键排序后拼接，集合内部换序为同参；含处置单期望版本
        List<String> sortedFp = new ArrayList<>();
        sortedFp.add(dispositionKey);
        sortedFp.add(actor);
        sortedFp.add(String.valueOf(req.expectedDispositionVersion()));
        for (String key : sorted(expectedVersions.keySet())) {
            sortedFp.add(key);
            sortedFp.add(String.valueOf(expectedVersions.get(key)));
        }
        String fingerprint = fingerprint(sortedFp.toArray(new String[0]));

        return executeIdempotent(CMD_CONFIRM, req.requestId(), fingerprint, () -> {
            DispositionRepository.OrderRow order = lockOrder(dispositionKey);
            if (order.status() != DispositionStatus.SUBMITTED.name()) {
                throw ApiException.conflict(
                        "处置单状态 " + order.status() + " 不允许确认，仅 SUBMITTED 可确认");
            }
            if (order.version() != req.expectedDispositionVersion()) {
                throw ApiException.conflict("处置单版本已变化，期望 " + req.expectedDispositionVersion()
                        + "，实际 " + order.version());
            }
            if (order.submitActor().equals(actor)) {
                throw ApiException.unprocessable("二审确认人必须与提交人不同: " + actor);
            }

            List<DispositionRepository.FrozenBatchRow> frozenRows =
                    dispositionRepo.findFrozenBatches(dispositionKey);
            Map<String, DispositionRepository.FrozenBatchRow> frozen = new LinkedHashMap<>();
            for (DispositionRepository.FrozenBatchRow row : frozenRows) {
                frozen.put(row.batchKey(), row);
            }

            // 锁顺序确定：处置单 -> 闭包全部批次按业务键排序（祖先也在闭包内）
            List<String> lockedKeys = sorted(frozen.keySet());
            Map<String, BatchRepository.BatchRow> current = new LinkedHashMap<>();
            for (String key : lockedKeys) {
                BatchRepository.BatchRow row = batchRepo.findBatchForUpdate(key)
                        .orElseThrow(() -> ApiException.conflict(
                                "闭包批次已不存在: " + key));
                current.put(key, row);
            }

            Map<String, List<String>> childrenOf = childrenOf();
            String ancestorKey = order.ancestorKey();
            // 二审重新计算闭包：期间拆分新增/移除后代、血缘变化均在此暴露
            List<String> currentClosure = closureKeys(ancestorKey, childrenOf);
            if (!new HashSet<>(currentClosure).equals(frozen.keySet())) {
                throw ApiException.conflict("召回闭包在提交后发生变化（拆分或血缘改变），请重新提交处置单");
            }
            if (!expectedVersions.keySet().equals(frozen.keySet())) {
                throw ApiException.conflict(
                        "batchVersions 必须且仅包含冻结的全部闭包批次（含祖先自身）");
            }
            // 祖先必须仍为 RECALLED（再次召回/状态变化会体现为版本或状态差异）
            BatchRepository.BatchRow ancestorRow = current.get(ancestorKey);
            if (!BatchStatus.RECALLED.name().equals(ancestorRow.status())) {
                throw ApiException.conflict("召回祖先状态已变化: " + ancestorRow.status());
            }

            Map<String, String> parentOf = parentOf(childrenOf);
            // 逐批重校版本（携带版本 + 冻结版本）、状态与到祖先的完整路径
            for (String key : lockedKeys) {
                DispositionRepository.FrozenBatchRow f = frozen.get(key);
                BatchRepository.BatchRow c = current.get(key);
                long carried = expectedVersions.get(key);
                if (carried != f.frozenVersion() || c.version() != f.frozenVersion()) {
                    throw ApiException.conflict("批次 " + key + " 版本已变化，冻结版本 "
                            + f.frozenVersion() + "，携带版本 " + carried + "，当前版本 "
                            + c.version());
                }
                if (!c.status().equals(f.frozenStatus())) {
                    throw ApiException.conflict(
                            "批次 " + key + " 状态已变化，冻结 " + f.frozenStatus()
                                    + "，当前 " + c.status());
                }
                List<String> currentPath = pathToAncestor(key, ancestorKey, parentOf);
                if (!joinPath(currentPath).equals(f.frozenPath())) {
                    throw ApiException.conflict("批次 " + key + " 到召回祖先的路径已变化");
                }
            }

            String now = now();
            List<DispositionSnapshotResponse> snapshots = new ArrayList<>(lockedKeys.size());
            // 按冻结顺序落账，任一不允许流转则抛异常，整单回滚
            for (DispositionRepository.FrozenBatchRow f : frozenRows) {
                BatchRepository.BatchRow c = current.get(f.batchKey());
                DispositionCategory category = DispositionCategory.valueOf(f.category());
                BatchStatus previous = BatchStatus.valueOf(c.status());
                // 一个批次只能被处置落账一次：已被（本单或并发先提交的他单）落账则不允许再次流转
                if (dispositionRepo.existsBatchDisposition(f.batchKey())) {
                    throw ApiException.conflict("批次 " + f.batchKey()
                            + " 已被处置落账，不允许重复处置");
                }
                BatchStatus finalStatus = switch (category) {
                    case DESTROY -> {
                        assertTransitionAllowed(previous, DispositionCategory.DESTROY, f.batchKey());
                        batchRepo.updateStatus(f.batchKey(), BatchStatus.DESTROYED.name());
                        yield BatchStatus.DESTROYED;
                    }
                    case REWORK -> {
                        assertTransitionAllowed(previous, DispositionCategory.REWORK, f.batchKey());
                        batchRepo.updateStatus(f.batchKey(), BatchStatus.REWORK_PENDING.name());
                        yield BatchStatus.REWORK_PENDING;
                    }
                    case HOLD -> {
                        // 保持隔离：状态不变，仅版本自增并记录暂挂原因
                        batchRepo.incrementVersion(f.batchKey());
                        yield previous;
                    }
                };
                // 批次级落账结论：一个批次只能落账一次；并发已由行锁串行，唯一约束兜底
                dispositionRepo.insertBatchDisposition(f.batchKey(), dispositionKey,
                        category.name(), finalStatus.name(), now);
                long landedVersion = batchRepo.findBatch(f.batchKey())
                        .orElseThrow(() -> ApiException.notFound("批次不存在: " + f.batchKey()))
                        .version();
                String reason = category == DispositionCategory.HOLD ? order.holdReason() : null;
                dispositionRepo.insertSnapshot(new DispositionRepository.SnapshotRow(0L,
                        dispositionKey, f.batchKey(), category.name(), previous.name(),
                        finalStatus.name(), landedVersion, f.frozenPath(), reason, now));
                snapshots.add(new DispositionSnapshotResponse(dispositionKey, f.batchKey(),
                        category, previous, finalStatus, landedVersion, splitPath(f.frozenPath()),
                        reason, Instant.parse(now)));
            }

            dispositionRepo.decideOrder(dispositionKey, DispositionStatus.CONFIRMED.name(),
                    actor, now);
            DispositionOrderResponse orderView = loadOrder(dispositionKey);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("order", orderView);
            body.put("snapshots", snapshots);
            return new StoredResponse(200, toJson(body));
        });
    }

    /**
     * 生产负责人拒绝处置单；不改任何批次。
     */
    public StoredResponse reject(String dispositionKey, String actorId, String roleHeader,
                                 DispositionRejectRequest req) {
        String actor = requireActor(actorId);
        ApprovalRole role = requireRole(roleHeader);
        if (role != ApprovalRole.OPERATIONS) {
            throw ApiException.badRequest("召回处置单仅可由生产负责人 OPERATIONS 拒绝");
        }
        String fingerprint = fingerprint("reject", dispositionKey, actor, req.reason());
        return executeIdempotent(CMD_REJECT, req.requestId(), fingerprint, () -> {
            DispositionRepository.OrderRow order = lockOrder(dispositionKey);
            if (order.status() != DispositionStatus.SUBMITTED.name()) {
                throw ApiException.conflict(
                        "处置单状态 " + order.status() + " 不允许拒绝，仅 SUBMITTED 可拒绝");
            }
            if (order.submitActor().equals(actor)) {
                throw ApiException.unprocessable("拒绝人必须与提交人不同: " + actor);
            }
            String now = now();
            dispositionRepo.decideOrder(dispositionKey, DispositionStatus.REJECTED.name(),
                    actor, now);
            return new StoredResponse(200, toJson(actionBody(dispositionKey,
                    DispositionStatus.REJECTED.name(), now)));
        });
    }

    /**
     * 确认/拒绝前由提交人本人取消处置单；不改任何批次。
     */
    public StoredResponse cancel(String dispositionKey, String actorId, String roleHeader,
                                 DispositionCancelRequest req) {
        String actor = requireActor(actorId);
        ApprovalRole role = requireRole(roleHeader);
        if (role != ApprovalRole.QUALITY) {
            throw ApiException.badRequest("召回处置单仅可由质量负责人 QUALITY 取消");
        }
        String fingerprint = fingerprint("cancel", dispositionKey, actor);
        return executeIdempotent(CMD_CANCEL, req.requestId(), fingerprint, () -> {
            DispositionRepository.OrderRow order = lockOrder(dispositionKey);
            if (order.status() != DispositionStatus.SUBMITTED.name()) {
                throw ApiException.conflict(
                        "处置单状态 " + order.status() + " 不允许取消，仅 SUBMITTED 可取消");
            }
            if (!order.submitActor().equals(actor)) {
                throw ApiException.unprocessable("仅处置单提交人本人可以取消: " + actor);
            }
            String now = now();
            dispositionRepo.decideOrder(dispositionKey, DispositionStatus.CANCELLED.name(),
                    actor, now);
            return new StoredResponse(200, toJson(actionBody(dispositionKey,
                    DispositionStatus.CANCELLED.name(), now)));
        });
    }

    /**
     * 处置单只读查询。
     */
    public DispositionOrderResponse getOrder(String dispositionKey) {
        return loadOrder(dispositionKey);
    }

    /**
     * 不可变路径与分类快照查询：仅 CONFIRMED 处置单存在。
     */
    public List<DispositionSnapshotResponse> paths(String dispositionKey) {
        DispositionRepository.OrderRow order = dispositionRepo.findOrder(dispositionKey)
                .orElseThrow(() -> ApiException.notFound("处置单不存在: " + dispositionKey));
        if (!DispositionStatus.CONFIRMED.name().equals(order.status())) {
            throw ApiException.unprocessable(
                    "处置单状态 " + order.status() + " 尚无落账路径快照，仅 CONFIRMED 可查");
        }
        return dispositionRepo.findSnapshots(dispositionKey).stream()
                .map(r -> new DispositionSnapshotResponse(r.dispositionKey(), r.batchKey(),
                        DispositionCategory.valueOf(r.category()),
                        BatchStatus.valueOf(r.previousStatus()),
                        BatchStatus.valueOf(r.finalStatus()), r.batchVersion(),
                        splitPath(r.path()), r.reason(), Instant.parse(r.createdAt())))
                .toList();
    }

    /**
     * 召回闭包只读查询：含祖先自身，按血缘创建顺序广度展开。
     */
    public List<ClosureEntryResponse> closure(String ancestorKey) {
        BatchRepository.BatchRow ancestor = batchRepo.findBatch(ancestorKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + ancestorKey));
        if (!BatchStatus.RECALLED.name().equals(ancestor.status())) {
            throw ApiException.unprocessable(
                    "仅可查询 RECALLED 祖先的召回闭包，当前状态: " + ancestor.status());
        }
        Map<String, List<String>> childrenOf = childrenOf();
        Map<String, String> parentOf = parentOf(childrenOf);
        List<ClosureEntryResponse> result = new ArrayList<>();
        for (String key : closureKeys(ancestorKey, childrenOf)) {
            BatchRepository.BatchRow row = batchRepo.findBatch(key)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + key));
            result.add(new ClosureEntryResponse(row.batchKey(), row.batchNo(),
                    BatchStatus.valueOf(row.status()), row.version(),
                    pathToAncestor(key, ancestorKey, parentOf)));
        }
        return result;
    }

    // ---------- 内部辅助 ----------

    /**
     * 流转白名单：已被处置过的终态（DESTROYED/REWORK_PENDING）不允许再次 DESTROY/REWORK；
     * 其余闭包成员（含 RECALLED 祖先、QUARANTINED/RELEASED/SPLIT/REJECTED 等后代）均可处置。
     * 正常路径下重复落账先被版本重校与 batch_disposition 唯一约束拦截，此处为语义兜底。
     */
    private void assertTransitionAllowed(BatchStatus previous, DispositionCategory category,
                                         String batchKey) {
        if (previous == BatchStatus.DESTROYED || previous == BatchStatus.REWORK_PENDING) {
            throw ApiException.conflict("批次 " + batchKey + " 状态 " + previous
                    + " 不允许 " + category + " 流转");
        }
    }

    private DispositionRepository.OrderRow lockOrder(String dispositionKey) {
        return dispositionRepo.findOrderForUpdate(dispositionKey)
                .orElseThrow(() -> ApiException.notFound("处置单不存在: " + dispositionKey));
    }

    private DispositionOrderResponse loadOrder(String dispositionKey) {
        DispositionRepository.OrderRow order = dispositionRepo.findOrder(dispositionKey)
                .orElseThrow(() -> ApiException.notFound("处置单不存在: " + dispositionKey));
        List<DispositionOrderResponse.FrozenBatch> batches =
                dispositionRepo.findFrozenBatches(dispositionKey).stream()
                        .map(r -> new DispositionOrderResponse.FrozenBatch(r.batchKey(),
                                DispositionCategory.valueOf(r.category()), r.frozenVersion(),
                                BatchStatus.valueOf(r.frozenStatus()), splitPath(r.frozenPath()),
                                r.seq()))
                        .toList();
        return new DispositionOrderResponse(order.dispositionKey(), order.ancestorKey(),
                order.status(), order.version(), order.submitActor(), order.decideActor(),
                order.holdReason(), Instant.parse(order.submittedAt()),
                order.decidedAt() == null ? null : Instant.parse(order.decidedAt()), batches);
    }

    private Map<String, Object> actionBody(String dispositionKey, String status, String decidedAt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dispositionKey", dispositionKey);
        body.put("status", status);
        body.put("decidedAt", Instant.parse(decidedAt));
        return body;
    }

    /**
     * 幂等执行：同事务内先查 command_log，命中则按指纹返回快照或 409；
     * 未命中执行业务动作并写入快照。并发同键插入冲突时重试，读取已提交结果。
     * 业务失败在写 command_log 前抛异常并随事务回滚，不占用 requestId。
     */
    private StoredResponse executeIdempotent(String type, String requestId, String fingerprint,
                                             Supplier<StoredResponse> action) {
        for (int attempt = 0; attempt < IDEMPOTENCY_MAX_ATTEMPTS; attempt++) {
            try {
                return tx.execute(status -> {
                    Optional<StoredResponse> logged = loggedResponse(type, requestId, fingerprint);
                    if (logged.isPresent()) {
                        return logged.get();
                    }
                    StoredResponse response = action.get();
                    batchRepo.insertCommand(new BatchRepository.CommandRow(type, requestId,
                            fingerprint, response.status(), response.body()), now());
                    return response;
                });
            } catch (DuplicateKeyException e) {
                // 并发同 requestId 冲突（command_log 或 dispositionKey）：
                // command_log 冲突回滚后重试读取对方快照；dispositionKey 冲突会在重试时变为 409
            }
        }
        throw ApiException.conflict("命令并发冲突，请重试: " + requestId);
    }

    private Optional<StoredResponse> loggedResponse(String type, String requestId,
                                                    String fingerprint) {
        var existing = batchRepo.findCommand(type, requestId);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        BatchRepository.CommandRow row = existing.get();
        if (!row.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId 已以不同参数使用: " + requestId);
        }
        return Optional.of(new StoredResponse(row.responseStatus(), row.responseBody()));
    }

    private String requireActor(String actorId) {
        if (actorId == null || actorId.isBlank()) {
            throw ApiException.badRequest("X-Actor-Id 不能为空");
        }
        return actorId.trim();
    }

    private ApprovalRole requireRole(String roleHeader) {
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
     * 去除空白项并校验集合内无重复；保留原列表用于请求诊断，分类以去重后集合为准。
     */
    private List<String> normalized(List<String> input) {
        List<String> result = new ArrayList<>();
        for (String raw : input) {
            if (raw == null || raw.isBlank()) {
                throw ApiException.badRequest("分类集合不允许包含空批次键");
            }
            result.add(raw.trim());
        }
        return result;
    }

    private void assertDistinctAndDisjoint(List<String> destroy, List<String> rework,
                                           List<String> hold) {
        assertNoDuplicate(destroy, "DESTROY");
        assertNoDuplicate(rework, "REWORK");
        assertNoDuplicate(hold, "HOLD");
        Set<String> seen = new HashSet<>();
        for (String key : destroy) {
            seen.add(key);
        }
        for (String key : rework) {
            if (!seen.add(key)) {
                throw ApiException.unprocessable(
                        "批次 " + key + " 同时出现在 DESTROY 与 REWORK 集合中，分类必须互斥");
            }
        }
        for (String key : hold) {
            if (!seen.add(key)) {
                throw ApiException.unprocessable(
                        "批次 " + key + " 出现在多个分类集合中，分类必须互斥");
            }
        }
    }

    private void assertNoDuplicate(List<String> keys, String category) {
        if (new HashSet<>(keys).size() != keys.size()) {
            throw ApiException.unprocessable(category + " 集合内存在重复批次");
        }
    }

    private Map<String, DispositionCategory> classify(List<String> destroy, List<String> rework,
                                                      List<String> hold) {
        Map<String, DispositionCategory> classification = new LinkedHashMap<>();
        for (String key : destroy) {
            classification.put(key, DispositionCategory.DESTROY);
        }
        for (String key : rework) {
            classification.put(key, DispositionCategory.REWORK);
        }
        for (String key : hold) {
            classification.put(key, DispositionCategory.HOLD);
        }
        return classification;
    }

    /**
     * 三集合并集必须恰好等于闭包：遗漏、多余（含不存在或非召回链批次）均 422。
     */
    private void assertClassificationEqualsClosure(Set<String> classified, List<String> closure,
                                                   String ancestorKey) {
        Set<String> closureSet = new HashSet<>(closure);
        List<String> missing = new ArrayList<>();
        for (String key : closure) {
            if (!classified.contains(key)) {
                missing.add(key);
            }
        }
        List<String> extra = new ArrayList<>();
        for (String key : classified) {
            if (!closureSet.contains(key)) {
                extra.add(key);
            }
        }
        if (!missing.isEmpty() || !extra.isEmpty()) {
            String detail = "分类并集必须恰好等于召回闭包（含祖先 " + ancestorKey + " 自身）";
            if (!missing.isEmpty()) {
                detail += "；遗漏: " + sorted(missing);
            }
            if (!extra.isEmpty()) {
                detail += "；多余或非召回链批次: " + sorted(extra);
            }
            throw ApiException.unprocessable(detail);
        }
    }

    /**
     * 全部血缘边的 父批->子批列表 映射；关系按 id（创建顺序）排列。
     */
    private Map<String, List<String>> childrenOf() {
        Map<String, List<String>> childrenOf = new HashMap<>();
        for (BatchRepository.LineageRow row : batchRepo.findAllLineage()) {
            childrenOf.computeIfAbsent(row.parentKey(), k -> new ArrayList<>()).add(row.childKey());
        }
        return childrenOf;
    }

    private Map<String, String> parentOf(Map<String, List<String>> childrenOf) {
        Map<String, String> parentOf = new HashMap<>();
        for (Map.Entry<String, List<String>> e : childrenOf.entrySet()) {
            for (String child : e.getValue()) {
                parentOf.put(child, e.getKey());
            }
        }
        return parentOf;
    }

    /**
     * 含祖先自身的闭包：祖先排第一，其余按血缘创建顺序广度优先展开。
     */
    private List<String> closureKeys(String ancestorKey, Map<String, List<String>> childrenOf) {
        List<String> result = new ArrayList<>();
        result.add(ancestorKey);
        Deque<String> queue = new ArrayDeque<>();
        queue.add(ancestorKey);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (String child : childrenOf.getOrDefault(current, List.of())) {
                result.add(child);
                queue.add(child);
            }
        }
        return result;
    }

    /**
     * 批次到召回祖先的完整祖先链业务键（祖先在前、不含批次自身）；祖先自身为空列表。
     */
    private List<String> pathToAncestor(String batchKey, String ancestorKey,
                                        Map<String, String> parentOf) {
        List<String> reversed = new ArrayList<>();
        String current = batchKey;
        while (!current.equals(ancestorKey)) {
            String parent = parentOf.get(current);
            if (parent == null) {
                throw ApiException.unprocessable(
                        "批次 " + batchKey + " 不在召回祖先 " + ancestorKey + " 的血缘链上");
            }
            reversed.add(parent);
            current = parent;
        }
        Collections.reverse(reversed);
        return reversed;
    }

    private String joinPath(List<String> path) {
        return path.isEmpty() ? "" : String.join(PATH_SEP, path);
    }

    private List<String> splitPath(String stored) {
        if (stored == null || stored.isEmpty()) {
            return List.of();
        }
        return List.of(stored.split(escapeForSplit(PATH_SEP), -1));
    }

    private String escapeForSplit(String sep) {
        return sep.equals("->") ? "->" : java.util.regex.Pattern.quote(sep);
    }

    private List<String> sorted(java.util.Collection<String> keys) {
        List<String> list = new ArrayList<>(keys);
        Collections.sort(list);
        return list;
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
        String canonical = String.join(Character.toString('\u0000'), parts);
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
