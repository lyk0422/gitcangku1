package com.example.starter.batch;

import com.example.starter.batch.dto.ApprovalResponse;
import com.example.starter.batch.dto.ApproveRequest;
import com.example.starter.batch.dto.BatchHistoryResponse;
import com.example.starter.batch.dto.BatchResponse;
import com.example.starter.batch.dto.CreateBatchRequest;
import com.example.starter.batch.dto.LineageEntryResponse;
import com.example.starter.batch.dto.PressureCurveResponse;
import com.example.starter.batch.dto.RecallRequest;
import com.example.starter.batch.dto.RecallResponse;
import com.example.starter.batch.dto.SplitRequest;
import com.example.starter.batch.dto.SplitResponse;
import com.example.starter.batch.dto.SubmitPressureCurveRequest;
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
    private static final String OP_PRESSURE_CURVE = "SUBMIT_PRESSURE_CURVE";

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
        BatchType batchType = req.batchType() == null ? BatchType.STANDARD : req.batchType();
        String fingerprint = fingerprint("create", req.batchKey(), req.productCode(), req.batchNo(),
                req.producedAt().toString(), String.join(SEP, items), batchType.name());
        return executeIdempotent(CMD_CREATE, req.commandKey(), fingerprint, () -> {
            repo.findBatch(req.batchKey()).ifPresent(b -> {
                throw ApiException.conflict("batchKey 已存在: " + req.batchKey());
            });
            String now = now();
            repo.insertBatch(new BatchRepository.BatchRow(0L, req.batchKey(), req.productCode(),
                    req.batchNo(), req.producedAt().toString(), BatchStatus.QUARANTINED.name(),
                    batchType.name(), 0L, now));
            for (int i = 0; i < items.size(); i++) {
                repo.insertRequiredTest(req.batchKey(), items.get(i), i + 1);
            }
            BatchResponse body = new BatchResponse(req.batchKey(), req.productCode(), req.batchNo(),
                    req.producedAt(), BatchStatus.QUARANTINED, items, Instant.parse(now),
                    batchType, 0L);
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
                BatchType batchType = BatchType.valueOf(batch.batchType());
                BatchStatus snapshot = snapshotAfter(row, repo.findTests(batchKey), required,
                        batchType == BatchType.SEALED, curvePassedAt(batchKey, batchType));
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
            } else if (status == BatchStatus.QUARANTINED && allRequiredPassed(batchKey, required)
                    && sealedCurvePassed(batchKey, BatchType.valueOf(batch.batchType()))) {
                // 密封批次除原检验项全部 PASS 外，还须压力曲线 PASS，才可进入待放行；
                // 曲线尚未提交或 FAIL 时继续停留在 QUARANTINED（FAIL 已直接 REJECTED）。
                newStatus = BatchStatus.PENDING_RELEASE;
            }
            // 即使状态未改变也推进版本：版本是整批实体的并发门禁，任何成功检验提交都算一次变更。
            repo.updateStatus(batchKey, newStatus.name());
            TestResultResponse body = new TestResultResponse(batchKey, req.testKey(), req.testItem(),
                    req.result(), req.inspector(), newStatus, Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 密封批次压力曲线检验提交。
     *
     * <p>与检验提交、批准、拆批及祖先召回共用 batch 行锁串行裁决：先校验输入（非法 400，不留检验、
     * 不占 requestId），再锁批次行核对类型、期望版本与状态；合法但门禁 FAIL 的曲线持久化保留并使
     * 批次进入 REJECTED，且永远不能改成 PASS。同 X-Request-Id 同参重放返回首次完整响应，异参 409；
     * 业务失败不占用 requestId，业务写入与去重记录在同一事务提交。
     */
    public StoredResponse submitPressureCurve(String batchKey, String requestIdHeader,
                                              String actorIdHeader, SubmitPressureCurveRequest req) {
        if (requestIdHeader == null || requestIdHeader.isBlank()) {
            throw ApiException.badRequest("X-Request-Id 不能为空");
        }
        if (actorIdHeader == null || actorIdHeader.isBlank()) {
            throw ApiException.badRequest("X-Actor-Id 不能为空");
        }
        String requestId = requestIdHeader.trim();
        String actor = actorIdHeader.trim();
        long expectedVersion = req.expectedVersion() == null ? -1L : req.expectedVersion();

        // 先做无副作用的输入校验与精确评估：非法结构/数值在此抛 400，不进入事务、不留检验、不占键。
        List<PressureCurveEvaluator.Point> rawPoints = new ArrayList<>(req.points().size());
        for (SubmitPressureCurveRequest.PressurePoint p : req.points()) {
            rawPoints.add(PressureCurveEvaluator.raw(p.second(), p.pressureKpa()));
        }
        PressureCurveEvaluator.Evaluation evaluation = PressureCurveEvaluator.evaluate(rawPoints);

        // 结构化指纹：操作 + 资源 + 全参数（含期望版本与操作者）。秒数等整数按精确值，
        // 压力等十进制按数值归一化（9.50 与 9.5 等值视为同参），操作者/资源等文本字段原样比较。
        List<String> fpParts = new ArrayList<>();
        fpParts.add(OP_PRESSURE_CURVE);
        fpParts.add(batchKey);
        fpParts.add(actor);
        fpParts.add(Long.toString(expectedVersion));
        for (PressureCurveEvaluator.Point p : evaluation.points()) {
            fpParts.add(Long.toString(p.second()));
            fpParts.add(p.pressure().stripTrailingZeros().toPlainString());
        }
        String fingerprint = fingerprint(fpParts.toArray(new String[0]));

        return executeRequestIdempotent(requestId, OP_PRESSURE_CURVE, batchKey, actor, fingerprint, () -> {
            BatchRepository.BatchRow batch = repo.findBatchForUpdate(batchKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
            // 行锁等待期间并发同 requestId 请求可能已提交：锁后重查快照，同参重放首次响应，
            // 异参 409，避免因曲线唯一键冲突把重放误判为业务失败。
            var loggedAfterLock = repo.findRequestLog(requestId);
            if (loggedAfterLock.isPresent()) {
                BatchRepository.RequestLogRow loggedRow = loggedAfterLock.get();
                if (!loggedRow.fingerprint().equals(fingerprint)) {
                    throw ApiException.conflict(
                            "requestId 已以不同参数使用（操作/资源/全参数/期望版本不一致）: "
                                    + requestId);
                }
                return new StoredResponse(loggedRow.responseStatus(), loggedRow.responseBody());
            }
            if (!BatchType.SEALED.name().equals(batch.batchType())) {
                throw ApiException.unprocessable("普通批次不支持压力曲线检验: " + batchKey);
            }
            if (batch.version() != expectedVersion) {
                throw ApiException.conflict("期望版本 " + expectedVersion
                        + " 与批次当前版本 " + batch.version() + " 不一致");
            }
            assertNoRecalledAncestor(batchKey);
            BatchStatus status = BatchStatus.valueOf(batch.status());
            if (status == BatchStatus.REJECTED || status == BatchStatus.RELEASED
                    || status == BatchStatus.RECALLED || status == BatchStatus.SPLIT) {
                throw ApiException.conflict("批次状态 " + status + " 不允许提交压力曲线");
            }
            if (repo.findPressureCurve(batchKey).isPresent()) {
                // PASS/FAIL 曲线都保留不可改写；尤其禁止把合法 FAIL 曲线重提为 PASS。
                throw ApiException.conflict("该批次压力曲线已提交，结论保留不可改写；"
                        + "FAIL 曲线不能改成 PASS");
            }

            boolean passed = evaluation.passed();
            String now = now();
            long batchVersionAfter = batch.version() + 1;
            long curveId = repo.insertPressureCurve(new BatchRepository.PressureCurveRow(0L, batchKey,
                    requestId, actor, passed ? TestOutcome.PASS.name() : TestOutcome.FAIL.name(),
                    evaluation.points().size(), evaluation.totalDecline().toPlainString(),
                    evaluation.maxSegmentRate().toPlainString(), expectedVersion,
                    batchVersionAfter, now));
            for (PressureCurveEvaluator.Point p : evaluation.points()) {
                repo.insertPressureCurveNode(new BatchRepository.PressureCurveNodeRow(0L, curveId,
                        p.seq(), p.second(), p.pressureRaw(), now));
            }
            // 逐段下降与速率冻结入库：批准放行后以此为准，不随后续任何操作改写。
            for (PressureCurveEvaluator.Segment s : evaluation.segments()) {
                repo.insertPressureCurveSegment(new BatchRepository.PressureCurveSegmentRow(0L, curveId,
                        s.seq(), s.fromSecond(), s.toSecond(), s.decline().toPlainString(),
                        s.rate().toPlainString(), s.rateExceeded(), now));
            }
            BatchStatus newStatus = status;
            if (!passed) {
                newStatus = BatchStatus.REJECTED;
            } else if (status == BatchStatus.QUARANTINED
                    && allRequiredPassed(batchKey, repo.findRequiredTests(batchKey))) {
                // 原检验项已全部 PASS 时，曲线 PASS 一并把批次推进待放行；否则等待剩余检验。
                newStatus = BatchStatus.PENDING_RELEASE;
            }
            // 无论 PASS/FAIL、状态是否改变都推进版本：期望版本持续充当并发裁决门禁。
            repo.updateStatus(batchKey, newStatus.name());

            PressureCurveResponse body = buildCurveResponse(batchKey, requestId, actor, evaluation,
                    passed, expectedVersion, batchVersionAfter, newStatus, Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * requestId 全局幂等执行：事务内先查 request_log，命中且指纹一致返回首次完整响应，
     * 指纹不一致抛 409；未命中执行业务并同事务写入快照。并发同键插入冲突时回滚重试读取，
     * 不向调用方泄漏唯一键异常，失败业务不占键。
     */
    private StoredResponse executeRequestIdempotent(String requestId, String operation,
                                                    String resourceKey, String actor,
                                                    String fingerprint,
                                                    Supplier<StoredResponse> action) {
        for (int attempt = 0; attempt < IDEMPOTENCY_MAX_ATTEMPTS; attempt++) {
            try {
                return tx.execute(status -> {
                    var existing = repo.findRequestLog(requestId);
                    if (existing.isPresent()) {
                        BatchRepository.RequestLogRow row = existing.get();
                        if (!row.fingerprint().equals(fingerprint)) {
                            throw ApiException.conflict(
                                    "requestId 已以不同参数使用（操作/资源/全参数/期望版本不一致）: "
                                            + requestId);
                        }
                        return new StoredResponse(row.responseStatus(), row.responseBody());
                    }
                    StoredResponse response = action.get();
                    repo.insertRequestLog(new BatchRepository.RequestLogRow(requestId, operation,
                            resourceKey, fingerprint, response.status(), response.body(), actor), now());
                    return response;
                });
            } catch (DuplicateKeyException e) {
                // 并发同 requestId 冲突：回滚后重试，读取对方已提交的首次响应或按指纹判 409
            }
        }
        throw ApiException.conflict("请求并发冲突，请重试: " + requestId);
    }

    private PressureCurveResponse buildCurveResponse(String batchKey, String requestId, String actor,
                                                     PressureCurveEvaluator.Evaluation evaluation,
                                                     boolean passed, long submittedVersion,
                                                     long batchVersion, BatchStatus batchStatus,
                                                     Instant createdAt) {
        List<PressureCurveResponse.NodeView> nodes = evaluation.points().stream()
                .map(p -> new PressureCurveResponse.NodeView(p.seq(), p.second(), p.pressureRaw()))
                .toList();
        List<PressureCurveResponse.SegmentView> segments = evaluation.segments().stream()
                .map(s -> new PressureCurveResponse.SegmentView(s.seq(), s.fromSecond(),
                        s.toSecond(), s.decline(), s.rate(), s.rateExceeded()))
                .toList();
        return new PressureCurveResponse(batchKey, requestId, actor,
                passed ? TestOutcome.PASS : TestOutcome.FAIL,
                passed ? null : evaluation.rejectReason(),
                evaluation.points().size(), evaluation.totalDecline(),
                evaluation.maxSegmentRate(), submittedVersion, batchVersion,
                batchStatus, nodes, segments, createdAt);
    }

    /**
     * 由已持久化的曲线与节点重建响应：节点按身份顺序读取并重新精确计算逐段信息，
     * 结论与版本以落库快照为准；batchStatus 为曲线提交落定瞬间的批次状态快照
     * （FAIL→REJECTED；PASS 时若原检验项此前已全部 PASS→PENDING_RELEASE，否则仍 QUARANTINED），
     * 不随后续批准/召回改写。
     */
    private PressureCurveResponse toPressureCurveResponse(BatchRepository.PressureCurveRow row) {
        List<PressureCurveEvaluator.Point> rawPoints = new ArrayList<>();
        for (BatchRepository.PressureCurveNodeRow n : repo.findPressureCurveNodes(row.id())) {
            rawPoints.add(new PressureCurveEvaluator.Point(n.seq(), n.pointSecond(),
                    n.pressureKpa(), new java.math.BigDecimal(n.pressureKpa())));
        }
        PressureCurveEvaluator.Evaluation evaluation = PressureCurveEvaluator.evaluate(rawPoints);
        boolean passed = TestOutcome.PASS.name().equals(row.outcome());
        String snapshotStatus;
        if (!passed) {
            snapshotStatus = BatchStatus.REJECTED.name();
        } else {
            java.time.Instant curveAt = Instant.parse(row.createdAt());
            Set<String> passedBefore = new HashSet<>();
            for (BatchRepository.TestRow t : repo.findTests(row.batchKey())) {
                if (TestOutcome.PASS.name().equals(t.outcome())
                        && !Instant.parse(t.createdAt()).isAfter(curveAt)) {
                    passedBefore.add(t.testItem());
                }
            }
            snapshotStatus = passedBefore.containsAll(repo.findRequiredTests(row.batchKey()))
                    ? BatchStatus.PENDING_RELEASE.name()
                    : BatchStatus.QUARANTINED.name();
        }
        List<PressureCurveResponse.NodeView> nodes = evaluation.points().stream()
                .map(p -> new PressureCurveResponse.NodeView(p.seq(), p.second(), p.pressureRaw()))
                .toList();
        // 逐段下降/速率以冻结的段表快照为准（非即时重算），批准放行后保持不变。
        List<PressureCurveResponse.SegmentView> segments = repo.findPressureCurveSegments(row.id())
                .stream()
                .map(s -> new PressureCurveResponse.SegmentView(s.seq(), s.fromSecond(),
                        s.toSecond(), new java.math.BigDecimal(s.declineKpa()),
                        new java.math.BigDecimal(s.rateKpaPerSecond()), s.rateExceeded()))
                .toList();
        return new PressureCurveResponse(row.batchKey(), row.requestId(), row.actorId(),
                passed ? TestOutcome.PASS : TestOutcome.FAIL,
                passed ? null : evaluation.rejectReason(),
                row.nodeCount(), new java.math.BigDecimal(row.totalDecline()),
                new java.math.BigDecimal(row.maxSegmentRate()), row.submittedVersion(),
                row.batchVersion(), BatchStatus.valueOf(snapshotStatus), nodes, segments,
                Instant.parse(row.createdAt()));
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
        BatchType batchType = BatchType.valueOf(batch.batchType());
        java.time.Instant curvePassedAt = curvePassedAt(batchKey, batchType);
        // 按提交顺序推导每条检验结果落定后的批次状态快照：FAIL→REJECTED；
        // 必做项全部 PASS（密封批次还要求压力曲线已 PASS）时该条→PENDING_RELEASE；其余→QUARANTINED。
        List<TestResultResponse> tests = new java.util.ArrayList<>(testRows.size());
        for (BatchRepository.TestRow t : testRows) {
            tests.add(toTestResponse(t,
                    snapshotAfter(t, testRows, required, batchType == BatchType.SEALED,
                            curvePassedAt).name()));
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
        PressureCurveResponse curve = BatchType.valueOf(batch.batchType()) == BatchType.SEALED
                ? repo.findPressureCurve(batchKey).map(this::toPressureCurveResponse).orElse(null)
                : null;
        return new BatchHistoryResponse(toBatchResponse(batch), tests, approvals, recall, curve);
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
                BatchType.valueOf(row.batchType()), row.version());
    }

    private TestResultResponse toTestResponse(BatchRepository.TestRow row, String batchStatus) {
        return new TestResultResponse(row.batchKey(), row.testKey(), row.testItem(),
                TestOutcome.valueOf(row.outcome()), row.inspector(),
                BatchStatus.valueOf(batchStatus), Instant.parse(row.createdAt()));
    }

    /**
     * 推导某条检验结果提交落定后的批次状态快照（按检验结果提交顺序）：
     * FAIL→REJECTED；此前已有 PASS 覆盖全部必做项，且密封批次的压力曲线已 PASS（曲线 PASS
     * 时刻不晚于当前检验提交时刻）→PENDING_RELEASE；其余→QUARANTINED。
     */
    private BatchStatus snapshotAfter(BatchRepository.TestRow target,
                                      List<BatchRepository.TestRow> ordered,
                                      List<String> required,
                                      boolean curveRequired,
                                      java.time.Instant curvePassedAt) {
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
        boolean curveReady = !curveRequired
                || (curvePassedAt != null
                && !curvePassedAt.isAfter(java.time.Instant.parse(target.createdAt())));
        return passed.containsAll(required) && curveReady
                ? BatchStatus.PENDING_RELEASE
                : BatchStatus.QUARANTINED;
    }

    /**
     * 密封批次压力曲线 PASS 的时刻；非密封批次或曲线不存在/FAIL 返回 null。
     * null 在普通批次语境下表示“无曲线要求”，在密封批次语境下表示“曲线尚未 PASS”。
     */
    private java.time.Instant curvePassedAt(String batchKey, BatchType batchType) {
        if (batchType != BatchType.SEALED) {
            return null;
        }
        return repo.findPressureCurve(batchKey)
                .filter(c -> TestOutcome.PASS.name().equals(c.outcome()))
                .map(c -> java.time.Instant.parse(c.createdAt()))
                .orElse(null);
    }

    /**
     * 密封批次是否已有 PASS 曲线；普通批次恒为 true（无曲线要求）。
     */
    private boolean sealedCurvePassed(String batchKey, BatchType batchType) {
        return batchType != BatchType.SEALED
                || repo.findPressureCurve(batchKey)
                .map(c -> TestOutcome.PASS.name().equals(c.outcome()))
                .orElse(false);
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
                        spec.batchNo(), parent.producedAt(), BatchStatus.QUARANTINED.name(),
                        parent.batchType(), 0L, now));
                for (int j = 0; j < required.size(); j++) {
                    repo.insertRequiredTest(spec.batchKey(), required.get(j), j + 1);
                }
                repo.insertLineage(new BatchRepository.LineageRow(0L, parentKey, spec.batchKey(),
                        i + 1, now));
                childBodies.add(new SplitResponse.SplitChild(spec.batchKey(), spec.batchNo(),
                        parent.productCode(), Instant.parse(parent.producedAt()),
                        BatchStatus.QUARANTINED, required, Instant.parse(now),
                        BatchType.valueOf(parent.batchType()), 0L));
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
}
