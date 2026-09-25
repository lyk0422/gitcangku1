package com.example.starter.batch;

import com.example.starter.batch.dto.ApprovalResponse;
import com.example.starter.batch.dto.ApproveRequest;
import com.example.starter.batch.dto.BatchHistoryResponse;
import com.example.starter.batch.dto.BatchResponse;
import com.example.starter.batch.dto.AdjudicateExcursionRequest;
import com.example.starter.batch.dto.CreateBatchRequest;
import com.example.starter.batch.dto.ExcursionAdjudicationResponse;
import com.example.starter.batch.dto.ExcursionResponse;
import com.example.starter.batch.dto.LineageEntryResponse;
import com.example.starter.batch.dto.RecallRequest;
import com.example.starter.batch.dto.RecallResponse;
import com.example.starter.batch.dto.RegisterExcursionsRequest;
import com.example.starter.batch.dto.RegisterExcursionsResponse;
import com.example.starter.batch.dto.ReleaseBlockResponse;
import com.example.starter.batch.dto.RiskEventResponse;
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
import java.util.Comparator;
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
    private static final String CMD_REGISTER_EXCURSIONS = "REGISTER_EXCURSIONS";
    private static final String CMD_ADJUDICATE_EXCURSION = "ADJUDICATE_EXCURSION";

    private static final String RELATION_SPLIT = "SPLIT";
    private static final String RELATION_REWORK = "REWORK";

    private static final String RISK_RELEASED_MAJOR = "RELEASED_MAJOR_EXCURSION";
    private static final String RISK_REJECTED_BY_EXCURSION = "REJECTED_BY_EXCURSION";

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
                req.producedAt().toString(), String.join(SEP, items),
                String.valueOf(req.minStorageTempC()), String.valueOf(req.maxStorageTempC()));
        return executeIdempotent(CMD_CREATE, req.commandKey(), fingerprint, () -> {
            repo.findBatch(req.batchKey()).ifPresent(b -> {
                throw ApiException.conflict("batchKey 已存在: " + req.batchKey());
            });
            String now = now();
            Double minTemp = req.minStorageTempC() == null ? 2.0 : req.minStorageTempC();
            Double maxTemp = req.maxStorageTempC() == null ? 8.0 : req.maxStorageTempC();
            if (minTemp > maxTemp) {
                throw ApiException.badRequest("温度规格下限不得大于上限");
            }
            repo.insertBatch(new BatchRepository.BatchRow(0L, req.batchKey(), req.productCode(),
                    req.batchNo(), req.producedAt().toString(), BatchStatus.QUARANTINED.name(), now,
                    0L, minTemp, maxTemp));
            for (int i = 0; i < items.size(); i++) {
                repo.insertRequiredTest(req.batchKey(), items.get(i), i + 1);
            }
            BatchResponse body = new BatchResponse(req.batchKey(), req.productCode(), req.batchNo(),
                    req.producedAt(), BatchStatus.QUARANTINED, items, Instant.parse(now),
                    0L, minTemp, maxTemp);
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
                    || status == BatchStatus.PENDING_DISPOSITION || status == BatchStatus.REWORKED) {
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
            // 放行持续门禁：未裁决 MAJOR 偏差禁止放行（422 并列标识）；
            // 未确认 MINOR 偏差须质控确认后才可放行。
            assertReleaseNotBlockedByExcursions(batchKey);
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

    // ---------- 储运偏差 ----------

    /**
     * 登记储运温度偏差：一次可登记多条；先对完整最终区间集合（既有 + 本次）做
     * 温度范围与区间重叠校验，并按批次温度规格统一判定严重级别，任一非法整批回滚。
     * 区间为 UTC 左闭右开 [startAt, endAt)，相邻区间端点相接（前段 end=后段 start）不算重叠。
     * 已放行批次出现 MAJOR 偏差时立即转为 PENDING_DISPOSITION 并写入风险记录，历史放行保留。
     *
     * <p>幂等指纹含操作类型、批次版本（首次登记落定后的版本）、规范化区间（按起点排序）与温度；
     * 重放时版本从已登记偏差行恢复，因此同键同参重放仍返回首次结果，失败不占键。
     */
    public StoredResponse registerExcursions(String batchKey, RegisterExcursionsRequest req) {
        List<RegisterExcursionsRequest.ExcursionSpec> specs = req.excursions();
        List<String> keys = specs.stream().map(RegisterExcursionsRequest.ExcursionSpec::excursionKey).toList();
        if (new HashSet<>(keys).size() != keys.size()) {
            throw ApiException.badRequest("excursionKey 在请求内重复");
        }
        // 规范化：按区间起点、再按 excursionKey 排序，消除提交顺序对指纹的影响
        List<RegisterExcursionsRequest.ExcursionSpec> canonical = specs.stream()
                .sorted(Comparator.comparing(RegisterExcursionsRequest.ExcursionSpec::startAt)
                        .thenComparing(RegisterExcursionsRequest.ExcursionSpec::excursionKey))
                .toList();
        for (int attempt = 0; attempt < IDEMPOTENCY_MAX_ATTEMPTS; attempt++) {
            try {
                return tx.execute(status -> {
                    BatchRepository.BatchRow batch = repo.findBatchForUpdate(batchKey)
                            .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
                    List<BatchRepository.ExcursionRow> existingForKey = new ArrayList<>();
                    boolean allExist = true;
                    for (String key : keys) {
                        var row = repo.findExcursion(batchKey, key);
                        if (row.isEmpty()) {
                            allExist = false;
                        } else {
                            existingForKey.add(row.get());
                        }
                    }
                    boolean anyExist = !existingForKey.isEmpty();
                    // 重放：全部偏差键已存在，版本从偏差行恢复，保证与首次指纹一致
                    long versionForFingerprint = allExist
                            ? existingForKey.get(0).batchVersion()
                            : batch.version() + 1;
                    String fingerprint = registerFingerprint(batchKey, versionForFingerprint, canonical);
                    var logged = loggedResponse(CMD_REGISTER_EXCURSIONS, req.commandKey(), fingerprint);
                    if (logged.isPresent()) {
                        return logged.get();
                    }
                    if (anyExist) {
                        throw ApiException.conflict(
                                "部分 excursionKey 已存在且非同一命令重放: " + keys);
                    }
                    BatchStatus state = BatchStatus.valueOf(batch.status());
                    if (state == BatchStatus.REJECTED || state == BatchStatus.RECALLED
                            || state == BatchStatus.REWORKED) {
                        throw ApiException.conflict("批次状态 " + state + " 不允许登记储运偏差");
                    }
                    for (RegisterExcursionsRequest.ExcursionSpec s : specs) {
                        validateSpec(s);
                    }
                    // 完整最终区间集合：既有全部偏差 + 本次全部偏差，统一做重叠校验
                    List<Interval> existing = repo.findExcursions(batchKey).stream()
                            .map(e -> new Interval(e.excursionKey(), Instant.parse(e.startAt()),
                                    Instant.parse(e.endAt())))
                            .toList();
                    List<Interval> incoming = specs.stream()
                            .map(s -> new Interval(s.excursionKey(), s.startAt(), s.endAt()))
                            .toList();
                    assertNoOverlap(existing, incoming);

                    long newVersion = batch.version() + 1;
                    String now = now();
                    List<ExcursionResponse> bodies = new ArrayList<>(specs.size());
                    boolean anyMajor = false;
                    for (RegisterExcursionsRequest.ExcursionSpec s : specs) {
                        ExcursionSeverity severity = severityOf(s, batch.minStorageTempC(),
                                batch.maxStorageTempC());
                        if (severity == ExcursionSeverity.MAJOR) {
                            anyMajor = true;
                        }
                        repo.insertExcursion(new BatchRepository.ExcursionRow(0L, batchKey,
                                s.excursionKey(), s.startAt().toString(), s.endAt().toString(),
                                s.measuredMinTempC(), s.measuredMaxTempC(),
                                severity.name(), ExcursionStatus.OPEN.name(), newVersion, now));
                        bodies.add(new ExcursionResponse(s.excursionKey(), batchKey, s.startAt(),
                                s.endAt(), s.measuredMinTempC(), s.measuredMaxTempC(), severity,
                                ExcursionStatus.OPEN, newVersion, null, Instant.parse(now)));
                    }
                    repo.incrementVersion(batchKey);

                    // 已放行（RELEASED/SPLIT）批次新增 MAJOR：立即待处置并写风险记录，不删除历史放行
                    if (anyMajor && (state == BatchStatus.RELEASED || state == BatchStatus.SPLIT)) {
                        repo.updateStatus(batchKey, BatchStatus.PENDING_DISPOSITION.name());
                        List<String> majorKeys = bodies.stream()
                                .filter(e -> e.severity() == ExcursionSeverity.MAJOR)
                                .map(ExcursionResponse::excursionKey)
                                .toList();
                        repo.insertRiskEvent(new BatchRepository.RiskEventRow(0L, batchKey,
                                RISK_RELEASED_MAJOR,
                                "已放行批次新增未裁决 MAJOR 偏差: " + String.join(",", majorKeys)
                                        + "，批次转为待处置",
                                "system", now));
                    }
                    RegisterExcursionsResponse body = new RegisterExcursionsResponse(
                            batchKey, newVersion, bodies, Instant.parse(now));
                    StoredResponse response = new StoredResponse(201, toJson(body));
                    repo.insertCommand(new BatchRepository.CommandRow(CMD_REGISTER_EXCURSIONS,
                            req.commandKey(), fingerprint, response.status(), response.body()), now);
                    return response;
                });
            } catch (DuplicateKeyException e) {
                // 并发同命令键冲突：回滚后重试，读取对方已提交的命令快照
            }
        }
        throw ApiException.conflict("命令并发冲突，请重试: " + req.commandKey());
    }

    /**
     * 登记命令指纹：操作类型 + 批次 + 批次版本 + 规范化区间与实测温度。
     */
    private String registerFingerprint(String batchKey, long version,
                                       List<RegisterExcursionsRequest.ExcursionSpec> canonical) {
        List<String> parts = new ArrayList<>();
        parts.add("REGISTER_EXCURSIONS");
        parts.add(batchKey);
        parts.add(Long.toString(version));
        for (RegisterExcursionsRequest.ExcursionSpec s : canonical) {
            parts.add(s.excursionKey());
            parts.add(s.startAt().toString());
            parts.add(s.endAt().toString());
            parts.add(formatTemp(s.measuredMinTempC()));
            parts.add(formatTemp(s.measuredMaxTempC()));
        }
        return fingerprint(parts.toArray(new String[0]));
    }

    /**
     * 裁决偏差：MAJOR 只能 REWORK/REJECT，MINOR 只能由质控 CONFIRM；
     * 裁决写入不可变快照，重复裁决返回 409。
     * REWORK 沿返工链创建新返工批（继承产品、生产时间、必做检验项与温度规格，初始 QUARANTINED），
     * 原批次进入 REWORKED；REJECT 批次及其后代按召回口径拦截并写风险记录。
     *
     * <p>裁决指纹含操作类型、偏差登记时锁定的批次版本、裁决结论与操作人；
     * 版本取自偏差不可变行 batch_version，首次与重放一致，同键同参重放首次结果，失败不占键。
     */
    public StoredResponse adjudicateExcursion(String batchKey, String excursionKey, String actorId,
                                              String roleHeader, AdjudicateExcursionRequest req) {
        if (actorId == null || actorId.isBlank()) {
            throw ApiException.badRequest("X-Actor-Id 不能为空");
        }
        String actor = actorId.trim();
        ApprovalRole role = parseRole(roleHeader);
        ExcursionDisposition disposition;
        try {
            disposition = ExcursionDisposition.valueOf(req.disposition().trim());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("disposition 必须为 REWORK、REJECT 或 CONFIRM");
        }
        String reason = req.reason() == null ? null : req.reason().trim();
        String reworkKey = req.reworkBatchKey() == null ? null : req.reworkBatchKey().trim();
        String reworkNo = req.reworkBatchNo() == null ? null : req.reworkBatchNo().trim();
        for (int attempt = 0; attempt < IDEMPOTENCY_MAX_ATTEMPTS; attempt++) {
            try {
                return tx.execute(status -> {
                    BatchRepository.BatchRow batch = repo.findBatchForUpdate(batchKey)
                            .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
                    BatchRepository.ExcursionRow excursion = repo.findExcursion(batchKey, excursionKey)
                            .orElseThrow(() -> ApiException.notFound(
                                    "偏差不存在: " + batchKey + "/" + excursionKey));
                    String fingerprint = adjudicateFingerprint(batchKey, excursionKey,
                            excursion.batchVersion(), disposition, actor, role, reason,
                            reworkKey, reworkNo);
                    var logged = loggedResponse(CMD_ADJUDICATE_EXCURSION, req.commandKey(), fingerprint);
                    if (logged.isPresent()) {
                        return logged.get();
                    }
                    if (ExcursionStatus.ADJUDICATED.name().equals(excursion.status())) {
                        throw ApiException.conflict("偏差已裁决，裁决快照不可变: " + excursionKey);
                    }
                    ExcursionSeverity severity = ExcursionSeverity.valueOf(excursion.severity());
                    BatchStatus state = BatchStatus.valueOf(batch.status());
                    String reworkBatchKey = null;
                    String now = now();
                    if (severity == ExcursionSeverity.MAJOR) {
                        if (disposition != ExcursionDisposition.REWORK
                                && disposition != ExcursionDisposition.REJECT) {
                            throw ApiException.unprocessable(
                                    "MAJOR 偏差只能裁决为 REWORK 或 REJECT: " + excursionKey);
                        }
                        if (reason == null || reason.isBlank()) {
                            throw ApiException.badRequest("REWORK/REJECT 裁决必须提供 reason");
                        }
                        if (state == BatchStatus.RECALLED || state == BatchStatus.REJECTED
                                || state == BatchStatus.REWORKED) {
                            throw ApiException.conflict(
                                    "批次状态 " + state + " 不允许裁决 MAJOR 偏差");
                        }
                        if (disposition == ExcursionDisposition.REWORK) {
                            if (reworkKey == null || reworkKey.isBlank()
                                    || reworkNo == null || reworkNo.isBlank()) {
                                throw ApiException.badRequest(
                                        "REWORK 裁决必须提供 reworkBatchKey 与 reworkBatchNo");
                            }
                            if (repo.findBatch(reworkKey).isPresent()) {
                                throw ApiException.conflict("返工批 batchKey 已存在: " + reworkKey);
                            }
                            // 创建返工批并建立 REWORK 链；原批次置为 REWORKED
                            List<String> required = repo.findRequiredTests(batchKey);
                            repo.insertBatch(new BatchRepository.BatchRow(0L, reworkKey,
                                    batch.productCode(), reworkNo, batch.producedAt(),
                                    BatchStatus.QUARANTINED.name(), now, 0L,
                                    batch.minStorageTempC(), batch.maxStorageTempC()));
                            for (int i = 0; i < required.size(); i++) {
                                repo.insertRequiredTest(reworkKey, required.get(i), i + 1);
                            }
                            repo.insertLineage(new BatchRepository.LineageRow(0L, batchKey,
                                    reworkKey, 1, RELATION_REWORK, now));
                            repo.updateStatus(batchKey, BatchStatus.REWORKED.name());
                            reworkBatchKey = reworkKey;
                        } else {
                            // REJECT：逐行锁定全部后代（按业务键排序保证锁顺序确定），
                            // 与后代上的检验/批准/拆分互斥，按事务提交顺序裁决——
                            // REJECT 先提交则后代新操作看到拦截祖先并返回 422。
                            List<String> descendants = descendantKeys(batchKey);
                            Collections.sort(descendants);
                            for (String descendantKey : descendants) {
                                repo.findBatchForUpdate(descendantKey);
                            }
                            // 批次按召回口径拦截（状态置 REJECTED），后代不改写状态但被拦截
                            repo.updateStatus(batchKey, BatchStatus.REJECTED.name());
                            repo.insertRiskEvent(new BatchRepository.RiskEventRow(0L, batchKey,
                                    RISK_REJECTED_BY_EXCURSION,
                                    "MAJOR 偏差 " + excursionKey
                                            + " 裁决 REJECT，批次及其后代按召回口径拦截",
                                    actor, now));
                        }
                    } else {
                        if (disposition != ExcursionDisposition.CONFIRM) {
                            throw ApiException.unprocessable(
                                    "MINOR 偏差只能由质控 CONFIRM 确认: " + excursionKey);
                        }
                        if (role != ApprovalRole.QUALITY) {
                            throw ApiException.unprocessable("MINOR 偏差必须由 QUALITY 质控角色确认");
                        }
                    }
                    repo.insertAdjudication(new BatchRepository.AdjudicationRow(0L, batchKey,
                            excursionKey, disposition.name(), actor, reason, reworkBatchKey, now));
                    // 标记偏差已裁决（登记行仅状态推进，区间/温度/级别不可变）
                    repo.updateExcursionStatus(batchKey, excursionKey,
                            ExcursionStatus.ADJUDICATED.name());
                    ExcursionAdjudicationResponse snapshot = new ExcursionAdjudicationResponse(
                            disposition, actor, reason, reworkBatchKey, Instant.parse(now));
                    StoredResponse response = new StoredResponse(201, toJson(snapshot));
                    repo.insertCommand(new BatchRepository.CommandRow(CMD_ADJUDICATE_EXCURSION,
                            req.commandKey(), fingerprint, response.status(), response.body()), now);
                    return response;
                });
            } catch (DuplicateKeyException e) {
                // 并发同命令键冲突：回滚后重试，读取对方已提交的命令快照
            }
        }
        throw ApiException.conflict("命令并发冲突，请重试: " + req.commandKey());
    }

    /**
     * 裁决命令指纹：操作类型 + 偏差（含登记时批次版本）+ 裁决结论、操作人、角色与返工批参数。
     */
    private String adjudicateFingerprint(String batchKey, String excursionKey, long batchVersion,
                                         ExcursionDisposition disposition, String actor,
                                         ApprovalRole role, String reason,
                                         String reworkKey, String reworkNo) {
        List<String> parts = new ArrayList<>(List.of("ADJUDICATE_EXCURSION", batchKey, excursionKey,
                Long.toString(batchVersion), disposition.name(), actor, role.name(),
                reason == null ? "" : reason));
        if (disposition == ExcursionDisposition.REWORK) {
            parts.add(reworkKey == null ? "" : reworkKey);
            parts.add(reworkNo == null ? "" : reworkNo);
        }
        return fingerprint(parts.toArray(new String[0]));
    }

    /**
     * 查询批次偏差区间（含裁决快照）。
     */
    public List<ExcursionResponse> listExcursions(String batchKey) {
        repo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        return repo.findExcursions(batchKey).stream()
                .map(this::toExcursionResponse)
                .toList();
    }

    /**
     * 查询批次风险记录（按写入顺序）；风险只增不改，历史放行不删除。
     */
    public List<RiskEventResponse> listRiskEvents(String batchKey) {
        repo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        return repo.findRiskEvents(batchKey).stream()
                .map(this::toRiskEventResponse)
                .toList();
    }

    /**
     * 放行持续门禁查询：列出未裁决 MAJOR 与未确认 MINOR 偏差标识。
     */
    public ReleaseBlockResponse releaseBlock(String batchKey) {
        repo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        List<BatchRepository.ExcursionRow> open = repo.findExcursions(batchKey).stream()
                .filter(e -> ExcursionStatus.OPEN.name().equals(e.status()))
                .toList();
        List<String> majorKeys = open.stream()
                .filter(e -> ExcursionSeverity.MAJOR.name().equals(e.severity()))
                .map(BatchRepository.ExcursionRow::excursionKey)
                .toList();
        List<String> minorKeys = open.stream()
                .filter(e -> ExcursionSeverity.MINOR.name().equals(e.severity()))
                .map(BatchRepository.ExcursionRow::excursionKey)
                .toList();
        return new ReleaseBlockResponse(batchKey, !majorKeys.isEmpty(), majorKeys,
                !minorKeys.isEmpty(), minorKeys);
    }

    /**
     * 放行持续门禁：存在未裁决 MAJOR 偏差抛 422 并列出标识；
     * 存在未确认 MINOR 偏差抛 422 提示需质控确认。
     */
    private void assertReleaseNotBlockedByExcursions(String batchKey) {
        ReleaseBlockResponse block = releaseBlock(batchKey);
        if (block.releaseBlocked()) {
            throw ApiException.unprocessable(
                    "存在未裁决 MAJOR 储运偏差，批次不得放行: "
                            + String.join(",", block.openMajorExcursionKeys()));
        }
        if (block.minorQualityConfirmationPending()) {
            throw ApiException.unprocessable(
                    "存在未由质控确认的 MINOR 储运偏差，确认后方可放行: "
                            + String.join(",", block.openMinorExcursionKeys()));
        }
    }

    private void validateSpec(RegisterExcursionsRequest.ExcursionSpec s) {
        if (!s.endAt().isAfter(s.startAt())) {
            throw ApiException.badRequest(
                    "偏差区间结束必须晚于开始（UTC 左闭右开）: " + s.excursionKey());
        }
        if (s.measuredMinTempC() > s.measuredMaxTempC()) {
            throw ApiException.badRequest(
                    "实测温度下限不得大于上限: " + s.excursionKey());
        }
    }

    /**
     * 严重级别判定：实测最低/最高温任一边界超出批次温度规格（不含边界）即为 MAJOR，否则 MINOR。
     */
    private ExcursionSeverity severityOf(RegisterExcursionsRequest.ExcursionSpec s,
                                         Double minSpec, Double maxSpec) {
        double min = minSpec == null ? 2.0 : minSpec;
        double max = maxSpec == null ? 8.0 : maxSpec;
        if (s.measuredMinTempC() < min || s.measuredMaxTempC() > max) {
            return ExcursionSeverity.MAJOR;
        }
        return ExcursionSeverity.MINOR;
    }

    /**
     * 完整最终区间集合重叠校验：本次区间之间及与既有区间均不得重叠；
     * 左闭右开区间前段 end=后段 start 为相接，允许。
     */
    private void assertNoOverlap(List<Interval> existing, List<Interval> incoming) {
        List<Interval> all = new ArrayList<>(existing.size() + incoming.size());
        all.addAll(existing);
        all.addAll(incoming);
        List<Interval> sorted = all.stream().sorted().toList();
        for (int i = 1; i < sorted.size(); i++) {
            Interval prev = sorted.get(i - 1);
            Interval cur = sorted.get(i);
            if (cur.start.isBefore(prev.end)) {
                throw ApiException.unprocessable(
                        "同批次偏差区间不得重叠: " + prev.key + " 与 " + cur.key);
            }
        }
    }

    private String formatTemp(double temp) {
        return new java.math.BigDecimal(temp).stripTrailingZeros().toPlainString();
    }

    /**
     * 规范化温度偏差区间，按起点排序实现左闭右开重叠判定。
     */
    private record Interval(String key, Instant start, Instant end) implements Comparable<Interval> {
        @Override
        public int compareTo(Interval o) {
            int byStart = start.compareTo(o.start);
            if (byStart != 0) {
                return byStart;
            }
            return key.compareTo(o.key);
        }
    }

    /**
     * 当前可用批次：排除已召回（RECALLED）、已拆分（SPLIT）、待处置（PENDING_DISPOSITION）、
     * 已返工（REWORKED）批次，以及任一祖先被召回或 MAJOR 偏差裁决 REJECT 的后代批次；
     * 后代自身状态不改写。
     */
    public List<BatchResponse> listAvailable() {
        Map<String, String> parentOf = childToParent();
        Set<String> recalled = new HashSet<>(repo.findRecalledKeys());
        Set<String> excursionRejected = new HashSet<>(repo.findExcursionRejectedKeys());
        return repo.findAvailableBatches().stream()
                .filter(b -> !BatchStatus.SPLIT.name().equals(b.status()))
                .filter(b -> !BatchStatus.PENDING_DISPOSITION.name().equals(b.status()))
                .filter(b -> !BatchStatus.REWORKED.name().equals(b.status()))
                .filter(b -> !excursionRejected.contains(b.batchKey()))
                .filter(b -> recalledAncestor(b.batchKey(), parentOf, recalled).isEmpty())
                .filter(b -> excursionRejectAncestor(b.batchKey(), parentOf, excursionRejected).isEmpty())
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
        List<ExcursionResponse> excursions = repo.findExcursions(batchKey).stream()
                .map(this::toExcursionResponse)
                .toList();
        List<RiskEventResponse> riskEvents = repo.findRiskEvents(batchKey).stream()
                .map(this::toRiskEventResponse)
                .toList();
        return new BatchHistoryResponse(toBatchResponse(batch), tests, approvals, recall,
                excursions, riskEvents);
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
                repo.findRequiredTests(row.batchKey()), Instant.parse(row.createdAt()),
                row.version(), row.minStorageTempC(), row.maxStorageTempC());
    }

    private TestResultResponse toTestResponse(BatchRepository.TestRow row, String batchStatus) {
        return new TestResultResponse(row.batchKey(), row.testKey(), row.testItem(),
                TestOutcome.valueOf(row.outcome()), row.inspector(),
                BatchStatus.valueOf(batchStatus), Instant.parse(row.createdAt()));
    }

    private ExcursionResponse toExcursionResponse(BatchRepository.ExcursionRow row) {
        ExcursionAdjudicationResponse adjudication = repo
                .findAdjudication(row.batchKey(), row.excursionKey())
                .map(a -> new ExcursionAdjudicationResponse(
                        ExcursionDisposition.valueOf(a.disposition()), a.actorId(), a.reason(),
                        a.reworkBatchKey(), Instant.parse(a.adjudicatedAt())))
                .orElse(null);
        return new ExcursionResponse(row.excursionKey(), row.batchKey(),
                Instant.parse(row.startAt()), Instant.parse(row.endAt()),
                row.measuredMinTempC(), row.measuredMaxTempC(),
                ExcursionSeverity.valueOf(row.severity()), ExcursionStatus.valueOf(row.status()),
                row.batchVersion(), adjudication, Instant.parse(row.registeredAt()));
    }

    private RiskEventResponse toRiskEventResponse(BatchRepository.RiskEventRow row) {
        return new RiskEventResponse(row.id(), row.batchKey(), row.riskType(), row.detail(),
                row.actorId(), Instant.parse(row.createdAt()));
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
                        spec.batchNo(), parent.producedAt(), BatchStatus.QUARANTINED.name(), now,
                        0L, parent.minStorageTempC(), parent.maxStorageTempC()));
                for (int j = 0; j < required.size(); j++) {
                    repo.insertRequiredTest(spec.batchKey(), required.get(j), j + 1);
                }
                repo.insertLineage(new BatchRepository.LineageRow(0L, parentKey, spec.batchKey(),
                        i + 1, "SPLIT", now));
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
     * 祖先查询：从直接父批逐级向上到根，每项含批次自身状态及导致其不可用的
     * 召回祖先与 MAJOR 偏差 REJECT 祖先。
     */
    public List<LineageEntryResponse> listAncestors(String batchKey) {
        repo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        Map<String, String> parentOf = childToParent();
        Set<String> recalled = new HashSet<>(repo.findRecalledKeys());
        Set<String> excursionRejected = new HashSet<>(repo.findExcursionRejectedKeys());
        List<LineageEntryResponse> result = new ArrayList<>();
        String current = batchKey;
        while (parentOf.containsKey(current)) {
            current = parentOf.get(current);
            result.add(toLineageEntry(current, parentOf, recalled, excursionRejected));
        }
        return result;
    }

    /**
     * 后代查询：按血缘关系（含拆分链与返工链）创建顺序广度优先展开，
     * 每项含批次自身状态及导致其不可用的召回祖先与 MAJOR 偏差 REJECT 祖先。
     */
    public List<LineageEntryResponse> listDescendants(String batchKey) {
        repo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        Map<String, String> parentOf = childToParent();
        Set<String> recalled = new HashSet<>(repo.findRecalledKeys());
        Set<String> excursionRejected = new HashSet<>(repo.findExcursionRejectedKeys());
        List<LineageEntryResponse> result = new ArrayList<>();
        for (String key : descendantKeys(batchKey)) {
            result.add(toLineageEntry(key, parentOf, recalled, excursionRejected));
        }
        return result;
    }

    /**
     * 若任一级祖先已被召回或 MAJOR 偏差裁决 REJECT 则抛 422：
     * 后代批次禁止新增检验、批准和拆分。
     */
    private void assertNoRecalledAncestor(String batchKey) {
        Map<String, String> parentOf = childToParent();
        Set<String> recalled = new HashSet<>(repo.findRecalledKeys());
        Set<String> excursionRejected = new HashSet<>(repo.findExcursionRejectedKeys());
        recalledAncestor(batchKey, parentOf, recalled).ifPresent(key -> {
            throw ApiException.unprocessable(
                    "祖先批次 " + key + " 已召回，禁止新增检验、批准和拆分");
        });
        excursionRejectAncestor(batchKey, parentOf, excursionRejected).ifPresent(key -> {
            throw ApiException.unprocessable(
                    "祖先批次 " + key + " 的 MAJOR 偏差已裁决 REJECT，禁止新增检验、批准和拆分");
        });
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
     * 沿父链（拆分链与返工链）向上查找最近的 MAJOR 偏差裁决 REJECT 祖先；
     * 批次自身 REJECT 裁决不算祖先拦截。
     */
    private Optional<String> excursionRejectAncestor(String batchKey, Map<String, String> parentOf,
                                                     Set<String> excursionRejected) {
        String current = batchKey;
        while (parentOf.containsKey(current)) {
            current = parentOf.get(current);
            if (excursionRejected.contains(current)) {
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
                                                Set<String> recalled,
                                                Set<String> excursionRejected) {
        BatchRepository.BatchRow row = repo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        return new LineageEntryResponse(row.batchKey(), row.batchNo(),
                BatchStatus.valueOf(row.status()),
                recalledAncestor(batchKey, parentOf, recalled).orElse(null),
                excursionRejectAncestor(batchKey, parentOf, excursionRejected).orElse(null));
    }
}
