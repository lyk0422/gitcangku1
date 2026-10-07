package com.example.starter.batch;

import com.example.starter.batch.dto.ApprovalResponse;
import com.example.starter.batch.dto.ApproveRequest;
import com.example.starter.batch.dto.BatchHistoryResponse;
import com.example.starter.batch.dto.BatchResponse;
import com.example.starter.batch.dto.CreateBatchRequest;
import com.example.starter.batch.dto.LineageEntryResponse;
import com.example.starter.batch.dto.MeasurementEntryResponse;
import com.example.starter.batch.dto.MeasurementResponse;
import com.example.starter.batch.dto.RebindResponse;
import com.example.starter.batch.dto.RebindSpecRequest;
import com.example.starter.batch.dto.RecallRequest;
import com.example.starter.batch.dto.RecallResponse;
import com.example.starter.batch.dto.SplitRequest;
import com.example.starter.batch.dto.SplitResponse;
import com.example.starter.batch.dto.SubmitMeasurementRequest;
import com.example.starter.batch.dto.SubmitTestRequest;
import com.example.starter.batch.dto.TestResultResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
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
import java.util.TreeMap;
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

    private final BatchRepository repo;
    private final SpecRepository specRepo;
    private final IdempotentExecutor idem;
    private final ObjectMapper objectMapper;

    public BatchService(BatchRepository repo,
                        SpecRepository specRepo,
                        IdempotentExecutor idem,
                        ObjectMapper objectMapper) {
        this.repo = repo;
        this.specRepo = specRepo;
        this.idem = idem;
        this.objectMapper = objectMapper;
    }

    /**
     * 创建批次：初始状态 QUARANTINED；batchKey 全局唯一，重复返回 409。
     * specKey/specVersion 同时提供时绑定一版已存在的耦合公差规范（不存在返回 404），
     * 同时缺省则不绑定；绑定批次放行前还需当前版本测量向量 PASS。
     */
    public StoredResponse createBatch(CreateBatchRequest req) {
        List<String> items = req.requiredTests().stream().map(String::trim).toList();
        if (new HashSet<>(items).size() != items.size()) {
            throw ApiException.badRequest("requiredTests 存在重复检验项");
        }
        if ((req.specKey() == null) != (req.specVersion() == null)) {
            throw ApiException.badRequest("specKey 与 specVersion 必须同时提供或同时缺省");
        }
        String fingerprint = fingerprint("create", req.batchKey(), req.productCode(), req.batchNo(),
                req.producedAt().toString(), String.join("\u0000", items),
                String.valueOf(req.specKey()), String.valueOf(req.specVersion()));
        return executeIdempotent(CMD_CREATE, req.commandKey(), fingerprint, () -> {
            repo.findBatch(req.batchKey()).ifPresent(b -> {
                throw ApiException.conflict("batchKey 已存在: " + req.batchKey());
            });
            if (req.specKey() != null) {
                specRepo.findSpec(req.specKey(), req.specVersion())
                        .orElseThrow(() -> ApiException.notFound("规范版本不存在: "
                                + req.specKey() + "@" + req.specVersion()));
            }
            String now = now();
            repo.insertBatch(new BatchRepository.BatchRow(0L, req.batchKey(), req.productCode(),
                    req.batchNo(), req.producedAt().toString(), BatchStatus.QUARANTINED.name(),
                    req.specKey(), req.specVersion(), now));
            for (int i = 0; i < items.size(); i++) {
                repo.insertRequiredTest(req.batchKey(), items.get(i), i + 1);
            }
            BatchResponse body = new BatchResponse(req.batchKey(), req.productCode(), req.batchNo(),
                    req.producedAt(), BatchStatus.QUARANTINED, items, req.specKey(),
                    req.specVersion(), Instant.parse(now));
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
            } else if (status == BatchStatus.QUARANTINED && allRequiredPassed(batchKey, required)
                    && measurementSatisfied(batch)) {
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
     * 提交测量向量：仅绑定规范的批次可用。向量必须恰好覆盖当前绑定版本的全部特性，
     * 每值最多 6 位小数；缺特性、多特性或超精度返回 400 且不留检验记录。
     * 合法向量按 BigDecimal 精确评估全部线性约束（等号合格）：全部满足记录 PASS，
     * 任一越界记录 FAIL 并使批次进入 REJECTED。expectedVersion 必须等于当前绑定版本。
     */
    public StoredResponse submitMeasurement(String batchKey, SubmitMeasurementRequest req) {
        Map<String, BigDecimal> values = req.values();
        List<String> fingerprintParts = new ArrayList<>();
        fingerprintParts.add("measure");
        fingerprintParts.add(batchKey);
        fingerprintParts.add(String.valueOf(req.expectedVersion()));
        fingerprintParts.add(req.operator().trim());
        for (Map.Entry<String, BigDecimal> entry : new TreeMap<>(values).entrySet()) {
            fingerprintParts.add(entry.getKey());
            fingerprintParts.add(entry.getValue() == null ? "null"
                    : SpecDefinition.canonicalDecimal(entry.getValue()));
        }
        String fingerprint = fingerprint(fingerprintParts.toArray(new String[0]));
        return executeIdempotent(SpecService.CMD_GLOBAL, req.requestId(), fingerprint, () -> {
            BatchRepository.BatchRow batch = repo.findBatchForUpdate(batchKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
            if (batch.specKey() == null) {
                throw ApiException.unprocessable("批次未绑定规范，不能提交测量向量: " + batchKey);
            }
            if (!batch.specVersion().equals(req.expectedVersion())) {
                throw ApiException.conflict("期望版本 " + req.expectedVersion()
                        + " 与当前绑定版本 " + batch.specVersion() + " 不一致");
            }
            assertNoRecalledAncestor(batchKey);
            BatchStatus status = BatchStatus.valueOf(batch.status());
            if (status != BatchStatus.QUARANTINED && status != BatchStatus.PENDING_RELEASE) {
                throw ApiException.conflict("批次状态 " + status + " 不允许提交测量向量");
            }
            SpecRepository.SpecRow specRow = specRepo.findSpec(batch.specKey(), batch.specVersion())
                    .orElseThrow(() -> new IllegalStateException("绑定规范版本缺失: "
                            + batch.specKey() + "@" + batch.specVersion()));
            SpecDefinition def = SpecDefinition.fromJson(specRow.featuresJson(),
                    specRow.constraintsJson(), objectMapper);
            validateVector(def, values);

            List<Integer> violated = def.evaluate(values);
            TestOutcome outcome = violated.isEmpty() ? TestOutcome.PASS : TestOutcome.FAIL;
            String now = now();
            Map<String, BigDecimal> ordered = new LinkedHashMap<>();
            for (String feature : def.features()) {
                ordered.put(feature, values.get(feature));
            }
            specRepo.insertMeasurement(new SpecRepository.MeasurementRow(0L, batchKey,
                    batch.specVersion(), toJson(ordered), outcome.name(),
                    req.operator().trim(), req.requestId(), now));

            BatchStatus newStatus = status;
            if (outcome == TestOutcome.FAIL) {
                newStatus = BatchStatus.REJECTED;
            } else if (status == BatchStatus.QUARANTINED
                    && allRequiredPassed(batchKey, repo.findRequiredTests(batchKey))) {
                newStatus = BatchStatus.PENDING_RELEASE;
            }
            if (newStatus != status) {
                repo.updateStatus(batchKey, newStatus.name());
            }
            MeasurementResponse body = new MeasurementResponse(batchKey, batch.specVersion(),
                    ordered, outcome, violated, newStatus, Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 向量格式校验：特性集合必须恰好等于规范特性；每值最多 6 位小数。违反抛 400 且不留检验。
     */
    private void validateVector(SpecDefinition def, Map<String, BigDecimal> values) {
        Set<String> expected = new HashSet<>(def.features());
        if (!values.keySet().equals(expected)) {
            throw ApiException.badRequest("测量向量必须恰好覆盖规范特性: " + def.features());
        }
        for (Map.Entry<String, BigDecimal> entry : values.entrySet()) {
            if (entry.getValue() == null) {
                throw ApiException.badRequest("测量值不能为空: " + entry.getKey());
            }
            if (entry.getValue().scale() > 6) {
                throw ApiException.badRequest("测量值最多 6 位小数: " + entry.getKey()
                        + "=" + entry.getValue().toPlainString());
            }
        }
    }

    /**
     * 显式换版：仅未放行（QUARANTINED/PENDING_RELEASE）且尚无任何批准的批次可换版；
     * expectedVersion 必须等于当前绑定版本。换版后旧向量与结论保留但不再作为放行依据，
     * 原 PENDING_RELEASE 回退为 QUARANTINED，需按新版本重新提交测量向量。
     */
    public StoredResponse rebindSpec(String batchKey, RebindSpecRequest req) {
        String fingerprint = fingerprint("respec", batchKey, req.operator().trim(),
                String.valueOf(req.expectedVersion()), String.valueOf(req.newVersion()));
        return executeIdempotent(SpecService.CMD_GLOBAL, req.requestId(), fingerprint, () -> {
            BatchRepository.BatchRow batch = repo.findBatchForUpdate(batchKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
            if (batch.specKey() == null) {
                throw ApiException.unprocessable("批次未绑定规范，不能换版: " + batchKey);
            }
            if (!batch.specVersion().equals(req.expectedVersion())) {
                throw ApiException.conflict("期望版本 " + req.expectedVersion()
                        + " 与当前绑定版本 " + batch.specVersion() + " 不一致");
            }
            BatchStatus status = BatchStatus.valueOf(batch.status());
            if (status != BatchStatus.QUARANTINED && status != BatchStatus.PENDING_RELEASE) {
                throw ApiException.conflict("批次状态 " + status + " 不允许换版，"
                        + "仅未放行且无任何批准的批次可换版");
            }
            if (!repo.findApprovals(batchKey).isEmpty()) {
                throw ApiException.conflict("批次已存在批准记录，不允许换版");
            }
            if (req.newVersion().equals(req.expectedVersion())) {
                throw ApiException.conflict("新版本与当前绑定版本相同: " + req.newVersion());
            }
            specRepo.findSpec(batch.specKey(), req.newVersion())
                    .orElseThrow(() -> ApiException.notFound("规范版本不存在: "
                            + batch.specKey() + "@" + req.newVersion()));
            String now = now();
            repo.updateSpecVersion(batchKey, req.newVersion());
            BatchStatus newStatus = status == BatchStatus.PENDING_RELEASE
                    ? BatchStatus.QUARANTINED
                    : status;
            if (newStatus != status) {
                repo.updateStatus(batchKey, newStatus.name());
            }
            RebindResponse body = new RebindResponse(batchKey, batch.specKey(),
                    req.expectedVersion(), req.newVersion(), newStatus, Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 放行门禁的向量条件：未绑定规范恒真；绑定批次要求当前绑定版本下存在 PASS 测量向量。
     */
    private boolean measurementSatisfied(BatchRepository.BatchRow batch) {
        return batch.specKey() == null
                || specRepo.findLatestPassMeasurement(batch.batchKey(), batch.specVersion())
                        .isPresent();
    }

    /**
     * 批准：两步放行。首个有效批准进入 RELEASE_REVIEW，第二个不同角色、不同批准人批准后 RELEASED。
     * 绑定规范的批次必须携带等于当前绑定版本的 expectedVersion；最终批准（进入 RELEASED）时
     * 冻结完整系数、上界与放行依据向量到 release_freeze，此后规范换版不影响该快照。
     */
    public StoredResponse approve(String batchKey, String actorId, String roleHeader, ApproveRequest req) {
        if (actorId == null || actorId.isBlank()) {
            throw ApiException.badRequest("X-Actor-Id 不能为空");
        }
        ApprovalRole role = parseRole(roleHeader);
        String actor = actorId.trim();
        String fingerprint = fingerprint("approve", batchKey, actor, role.name(),
                String.valueOf(req.expectedVersion()));
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
            assertApprovalVersion(batch, req.expectedVersion());
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
            if (newStatus == BatchStatus.RELEASED && batch.specKey() != null) {
                freezeRelease(batch, now);
            }
            ApprovalResponse body = new ApprovalResponse(batchKey, actor, role, seq, newStatus,
                    Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 批准版本门禁：绑定规范的批次必须提供等于当前绑定版本的 expectedVersion，
     * 且当前版本下存在 PASS 测量向量；未绑定批次不得提供 expectedVersion。
     */
    private void assertApprovalVersion(BatchRepository.BatchRow batch, Integer expectedVersion) {
        if (batch.specKey() == null) {
            if (expectedVersion != null) {
                throw ApiException.badRequest("未绑定规范的批次不应提供 expectedVersion");
            }
            return;
        }
        if (expectedVersion == null) {
            throw ApiException.badRequest("绑定规范的批次批准必须提供 expectedVersion");
        }
        if (!batch.specVersion().equals(expectedVersion)) {
            throw ApiException.conflict("批准期望版本 " + expectedVersion
                    + " 与当前绑定版本 " + batch.specVersion() + " 不一致");
        }
        if (specRepo.findLatestPassMeasurement(batch.batchKey(), batch.specVersion()).isEmpty()) {
            throw ApiException.unprocessable("当前绑定版本缺少 PASS 测量向量，不能批准");
        }
    }

    /**
     * 放行冻结：快照当前绑定版本的完整特性、系数、上界与最近一次 PASS 向量。
     */
    private void freezeRelease(BatchRepository.BatchRow batch, String now) {
        SpecRepository.SpecRow spec = specRepo.findSpec(batch.specKey(), batch.specVersion())
                .orElseThrow(() -> new IllegalStateException("绑定规范版本缺失: "
                        + batch.specKey() + "@" + batch.specVersion()));
        SpecRepository.MeasurementRow pass = specRepo
                .findLatestPassMeasurement(batch.batchKey(), batch.specVersion())
                .orElseThrow(() -> new IllegalStateException("当前版本缺少 PASS 测量向量"));
        specRepo.insertFreeze(new SpecRepository.FreezeRow(0L, batch.batchKey(), batch.specKey(),
                batch.specVersion(), spec.featuresJson(), spec.constraintsJson(),
                pass.vectorJson(), now));
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
        List<MeasurementEntryResponse> measurements = specRepo.findMeasurements(batchKey).stream()
                .map(m -> new MeasurementEntryResponse(m.specVersion(), readVector(m.vectorJson()),
                        TestOutcome.valueOf(m.outcome()), m.operator(),
                        Instant.parse(m.createdAt())))
                .toList();
        return new BatchHistoryResponse(toBatchResponse(batch), tests, measurements, approvals,
                recall);
    }

    /**
     * 解析存储的测量向量 JSON（特性名→十进制值），保持提交时的特性顺序。
     */
    @SuppressWarnings("unchecked")
    private Map<String, BigDecimal> readVector(String vectorJson) {
        try {
            return objectMapper.readValue(vectorJson, objectMapper.getTypeFactory()
                    .constructMapType(LinkedHashMap.class, String.class, BigDecimal.class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("测量向量反序列化失败", e);
        }
    }

    /**
     * 幂等执行：委托给共享执行器（同事务查快照、同键同参重放、同键改参 409、并发冲突重试）。
     */
    private StoredResponse executeIdempotent(String type, String commandKey, String fingerprint,
                                             Supplier<StoredResponse> action) {
        return idem.execute(type, commandKey, fingerprint, action);
    }

    /**
     * 查询命令快照：命中且指纹一致返回首次响应；指纹不一致抛 409；未命中返回空。
     */
    private Optional<StoredResponse> loggedResponse(String type, String commandKey,
                                                    String fingerprint) {
        return idem.logged(type, commandKey, fingerprint);
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
                repo.findRequiredTests(row.batchKey()), row.specKey(), row.specVersion(),
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

    private String now() {
        return Instant.now().toString();
    }

    private String fingerprint(String... parts) {
        return IdempotentExecutor.fingerprint(parts);
    }

    /**
     * 拆分：仅当前可用的 RELEASED 批次可一次拆成 2～5 个全新子批。
     * 子批继承产品编码、生产 UTC 时间、必做检验项与绑定的规范版本，初始 QUARANTINED，
     * 不继承检验、测量向量或批准记录；父批置为 SPLIT 不再可用。
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
                        parent.specKey(), parent.specVersion(), now));
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
}
