package com.example.starter.batch;

import com.example.starter.batch.dto.ConfirmationResponse;
import com.example.starter.batch.dto.ConfirmDeviationRequest;
import com.example.starter.batch.dto.CreateSegmentRequest;
import com.example.starter.batch.dto.DeviationResponse;
import com.example.starter.batch.dto.IntervalEntry;
import com.example.starter.batch.dto.MergeRequest;
import com.example.starter.batch.dto.MergeResponse;
import com.example.starter.batch.dto.ReadingEntry;
import com.example.starter.batch.dto.ReadingResponse;
import com.example.starter.batch.dto.RegisterReadingRequest;
import com.example.starter.batch.dto.SegmentResponse;
import com.example.starter.batch.dto.StorageTimelineResponse;
import com.example.starter.batch.dto.UpdateLimitsRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 仓储温度偏差域服务。
 *
 * <p>并发策略：读数登记与限值修改在事务内按"先锁 batch 行、再锁 storage_segment 行"固定顺序加锁，
 * 放行/拆分/合批/召回均锁 batch 行，因此读数入库与批次放行在同一 batch 行锁上串行裁决：
 * 放行先提交则读数随后把 RELEASED 置为 RELEASE_HOLD；读数先提交则放行在获锁后重查门禁并被拒，
 * 不会留下带未处置偏差的已放行批次。requestId 幂等通过 reading_request 表快照实现；
 * 其余命令沿用 command_log 指纹机制。
 */
@Service
public class StorageService {

    private static final String CMD_SEGMENT = "CREATE_SEGMENT";
    private static final String CMD_UPDATE_LIMITS = "UPDATE_LIMITS";
    private static final String CMD_DEVIATION_CONFIRM = "DEVIATION_CONFIRM";
    private static final String CMD_MERGE = "MERGE";

    public static final String GATE_OPEN = "GATE_OPEN";
    public static final String GATE_CLOSED = "GATE_CLOSED";

    /**
     * 指纹拼接分隔符（NUL）：业务参数不可能包含该字符，避免拼接碰撞。
     */
    private static final String SEP = Character.toString((char) 0);

    private static final int IDEMPOTENCY_MAX_ATTEMPTS = 3;

    private final StorageRepository storageRepo;
    private final BatchRepository batchRepo;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper;

    public StorageService(StorageRepository storageRepo,
                          BatchRepository batchRepo,
                          PlatformTransactionManager transactionManager,
                          ObjectMapper objectMapper) {
        this.storageRepo = storageRepo;
        this.batchRepo = batchRepo;
        this.tx = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    /**
     * 创建仓储段：同批次内 segmentKey 唯一；lowerLimit 必须严格小于 upperLimit（单位℃）。
     */
    public StoredResponse createSegment(String batchKey, CreateSegmentRequest req) {
        validateLimits(req.upperLimit(), req.lowerLimit());
        String fingerprint = fingerprint("segment", batchKey, req.segmentKey(),
                req.upperLimit().toPlainString(), req.lowerLimit().toPlainString());
        return executeIdempotent(CMD_SEGMENT, req.commandKey(), fingerprint, () -> {
            batchRepo.findBatchForUpdate(batchKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
            if (storageRepo.findSegment(batchKey, req.segmentKey()).isPresent()) {
                throw ApiException.conflict("仓储段已存在: " + req.segmentKey());
            }
            String now = now();
            storageRepo.insertSegment(new StorageRepository.SegmentRow(0L, batchKey,
                    req.segmentKey(), req.upperLimit(), req.lowerLimit(), now));
            SegmentResponse body = new SegmentResponse(batchKey, req.segmentKey(),
                    req.upperLimit(), req.lowerLimit(), Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 修改仓储段温度限值（℃）：仅影响修改之后登记的新读数，历史偏差保留快照不回写。
     */
    public StoredResponse updateLimits(String batchKey, String segmentKey, UpdateLimitsRequest req) {
        validateLimits(req.upperLimit(), req.lowerLimit());
        String fingerprint = fingerprint("limits", batchKey, segmentKey,
                req.upperLimit().toPlainString(), req.lowerLimit().toPlainString());
        return executeIdempotent(CMD_UPDATE_LIMITS, req.commandKey(), fingerprint, () -> {
            batchRepo.findBatchForUpdate(batchKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
            StorageRepository.SegmentRow segment = storageRepo.findSegmentForUpdate(batchKey, segmentKey)
                    .orElseThrow(() -> ApiException.notFound("仓储段不存在: " + segmentKey));
            storageRepo.updateSegmentLimits(batchKey, segmentKey, req.upperLimit(), req.lowerLimit());
            SegmentResponse body = new SegmentResponse(batchKey, segmentKey,
                    req.upperLimit(), req.lowerLimit(), Instant.parse(segment.createdAt()));
            return new StoredResponse(200, toJson(body));
        });
    }

    /**
     * 登记仓储温度读数：越界或与前一读数间隔严格大于 30 分钟即生成偏差并关闭批次门禁。
     * requestId 重放返回首次结果；同键换温度/时刻/仓储段返回 409。
     */
    public StoredResponse registerReading(String batchKey, String segmentKey, RegisterReadingRequest req) {
        String fingerprint = fingerprint("reading", batchKey, segmentKey,
                req.readingTime().toString(), req.temperature().toPlainString());
        for (int attempt = 0; attempt < IDEMPOTENCY_MAX_ATTEMPTS; attempt++) {
            try {
                return tx.execute(status -> {
                    // 先查 requestId 快照：已提交的首次登记直接重放或判异
                    var logged = loggedReading(req.requestId(), fingerprint);
                    if (logged.isPresent()) {
                        return logged.get();
                    }
                    // 固定加锁顺序：batch → segment，与放行事务互斥
                    BatchRepository.BatchRow batch = batchRepo.findBatchForUpdate(batchKey)
                            .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
                    StorageRepository.SegmentRow segment =
                            storageRepo.findSegmentForUpdate(batchKey, segmentKey)
                                    .orElseThrow(() -> ApiException.notFound("仓储段不存在: " + segmentKey));
                    // 锁等待期间并发同 requestId 可能已提交：重查快照
                    logged = loggedReading(req.requestId(), fingerprint);
                    if (logged.isPresent()) {
                        return logged.get();
                    }
                    List<StorageRepository.ReadingRow> ordered =
                            storageRepo.findReadings(segmentKey);
                    StorageRepository.ReadingRow previous = null;
                    for (StorageRepository.ReadingRow existing : ordered) {
                        if (existing.readingTime().compareTo(req.readingTime().toString()) >= 0) {
                            throw ApiException.unprocessable(
                                    "采集时刻必须严格晚于同段已有读数: " + existing.readingTime());
                        }
                        previous = existing;
                    }

                    String now = now();
                    storageRepo.insertReading(new StorageRepository.ReadingRow(0L, batchKey, segmentKey,
                            req.requestId(), req.readingTime().toString(), req.temperature(), now));
                    StorageRepository.ReadingRow reading =
                            storageRepo.findReadingByRequestId(req.requestId())
                                    .orElseThrow(() -> new IllegalStateException("读数入库后无法回读"));

                    Instant previousTime = previous == null ? null : Instant.parse(previous.readingTime());
                    boolean outOfLimit = StorageRules.isOutOfLimit(req.temperature(),
                            segment.upperLimit(), segment.lowerLimit());
                    boolean missing = StorageRules.isMissingGap(previousTime, req.readingTime());

                    List<StorageRepository.DeviationRow> created = new ArrayList<>(2);
                    if (outOfLimit) {
                        created.add(insertDeviation(reading, DeviationType.OUT_OF_LIMIT,
                                segment, req.temperature(), null, null, now));
                    }
                    if (missing) {
                        created.add(insertDeviation(reading, DeviationType.MISSING_READING,
                                segment, req.temperature(),
                                previous.readingTime(), reading.readingTime(), now));
                    }

                    // 已放行批次新发偏差 → 挂起；未放行批次不改状态，由门禁阻止后续放行
                    if (!created.isEmpty()
                            && BatchStatus.RELEASED.name().equals(batch.status())) {
                        batchRepo.updateStatus(batchKey, BatchStatus.RELEASE_HOLD.name());
                    }

                    List<DeviationResponse> deviationBodies = created.stream()
                            .map(this::toDeviationResponse).toList();
                    int openCount = storageRepo.countOpenDeviations(batchKey);
                    ReadingResponse body = new ReadingResponse(batchKey, segmentKey, req.requestId(),
                            req.readingTime(), req.temperature(), deviationBodies,
                            gateStatus(openCount), openCount, Instant.parse(now));
                    StoredResponse response = new StoredResponse(201, toJson(body));
                    storageRepo.insertReadingRequest(new StorageRepository.ReadingRequestRow(
                            req.requestId(), batchKey, segmentKey, fingerprint,
                            response.status(), response.body(), now));
                    return response;
                });
            } catch (DuplicateKeyException e) {
                // 并发同 requestId 插入冲突：回滚重试，读取对方已提交的快照或判异
            }
        }
        throw ApiException.conflict("读数并发冲突，请重试: " + req.requestId());
    }

    /**
     * 处置确认：两名不同角色、不同操作人针对同一偏差版本各确认一次后偏差解除，门禁重开。
     */
    public StoredResponse confirmDeviation(String deviationKey, String actorId, String roleHeader,
                                           ConfirmDeviationRequest req) {
        if (actorId == null || actorId.isBlank()) {
            throw ApiException.badRequest("X-Actor-Id 不能为空");
        }
        ApprovalRole role = parseRole(roleHeader);
        String actor = actorId.trim();
        String note = req.note() == null || req.note().isBlank() ? null : req.note().trim();
        String fingerprint = fingerprint("confirm", deviationKey, actor, role.name(),
                String.valueOf(req.version()), note == null ? "" : note);
        return executeIdempotent(CMD_DEVIATION_CONFIRM, req.commandKey(), fingerprint, () -> {
            StorageRepository.DeviationRow deviation =
                    storageRepo.findDeviationByKeyForUpdate(deviationKey)
                            .orElseThrow(() -> ApiException.notFound("偏差不存在: " + deviationKey));
            if (DeviationStatus.DISPOSED.name().equals(deviation.status())) {
                throw ApiException.conflict("偏差已处置，不能再次确认: " + deviationKey);
            }
            if (req.version() == null || req.version() != deviation.version()) {
                throw ApiException.conflict("偏差版本不匹配，当前版本 v" + deviation.version());
            }
            List<StorageRepository.ConfirmationRow> confirmations =
                    storageRepo.findConfirmations(deviation.id()).stream()
                            .filter(c -> c.version() == deviation.version())
                            .toList();
            for (StorageRepository.ConfirmationRow existing : confirmations) {
                if (existing.role().equals(role.name())) {
                    throw ApiException.conflict("角色 " + role + " 已确认该版本，需要另一种角色");
                }
                if (existing.actorId().equals(actor)) {
                    throw ApiException.conflict("两名处置人必须不同: " + actor);
                }
            }

            String now = now();
            storageRepo.insertConfirmation(new StorageRepository.ConfirmationRow(0L, deviation.id(),
                    deviation.version(), actor, role.name(), note, req.commandKey(), now));

            // 偏差行锁已将同偏差确认串行：连同本次共两笔不同角色确认即解除
            if (confirmations.size() + 1 >= 2) {
                storageRepo.markDeviationDisposed(deviation.id(), now);
                // 再锁批行恢复状态：仅挂起态且无其他未处置偏差时恢复 RELEASED
                BatchRepository.BatchRow batch = batchRepo.findBatchForUpdate(deviation.batchKey())
                        .orElseThrow(() -> new IllegalStateException("偏差关联批次不存在"));
                if (BatchStatus.RELEASE_HOLD.name().equals(batch.status())
                        && storageRepo.countOpenDeviations(deviation.batchKey()) == 0) {
                    batchRepo.updateStatus(deviation.batchKey(), BatchStatus.RELEASED.name());
                }
            }
            ConfirmationResponse body = new ConfirmationResponse(deviationKey, deviation.version(),
                    actor, role, note, Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 仓储段时刻视图：原始读数、超限/缺测区间、偏差处置人及当前门禁状态。
     * 历史偏差携带触发时限值快照，不随后续限值修改回写。
     */
    public StorageTimelineResponse timeline(String batchKey, String segmentKey) {
        batchRepo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        StorageRepository.SegmentRow segment = storageRepo.findSegment(batchKey, segmentKey)
                .orElseThrow(() -> ApiException.notFound("仓储段不存在: " + segmentKey));

        List<StorageRepository.ReadingRow> readings = storageRepo.findReadings(segmentKey);
        List<StorageRepository.DeviationRow> deviations =
                storageRepo.findDeviationsBySegment(batchKey, segmentKey);

        Map<Long, StorageRepository.DeviationRow> outOfLimitByReading = new HashMap<>();
        Map<Long, StorageRepository.DeviationRow> missingByReading = new HashMap<>();
        for (StorageRepository.DeviationRow d : deviations) {
            if (DeviationType.OUT_OF_LIMIT.name().equals(d.type())) {
                outOfLimitByReading.put(d.readingId(), d);
            } else {
                missingByReading.put(d.readingId(), d);
            }
        }

        List<ReadingEntry> readingEntries = new ArrayList<>(readings.size());
        for (StorageRepository.ReadingRow r : readings) {
            StorageRepository.DeviationRow out = outOfLimitByReading.get(r.id());
            readingEntries.add(new ReadingEntry(r.requestId(), Instant.parse(r.readingTime()),
                    r.temperatureCelsius(), out != null,
                    out == null ? null : out.deviationKey(), Instant.parse(r.createdAt())));
        }

        List<IntervalEntry> intervalEntries = new ArrayList<>();
        for (int i = 1; i < readings.size(); i++) {
            StorageRepository.ReadingRow prev = readings.get(i - 1);
            StorageRepository.ReadingRow cur = readings.get(i);
            StorageRepository.DeviationRow missing = missingByReading.get(cur.id());
            long gapSeconds = Duration.between(
                    Instant.parse(prev.readingTime()), Instant.parse(cur.readingTime())).getSeconds();
            intervalEntries.add(new IntervalEntry(prev.requestId(), cur.requestId(),
                    Instant.parse(prev.readingTime()), Instant.parse(cur.readingTime()), gapSeconds,
                    missing != null, missing == null ? null : missing.deviationKey()));
        }

        List<DeviationResponse> deviationEntries = deviations.stream()
                .map(this::toDeviationResponse).toList();
        int openCount = storageRepo.countOpenDeviations(batchKey);
        return new StorageTimelineResponse(batchKey, segmentKey, segment.upperLimit(),
                segment.lowerLimit(), readingEntries, intervalEntries, deviationEntries,
                gateStatus(openCount), openCount);
    }

    /**
     * 合批：2～5 个 RELEASED 且无未处置偏差的同产品父批合并为一个 QUARANTINED 子批；
     * 任一父批仍有未处置偏差（或非 RELEASED、产品不一致、子批键冲突）时整单失败、不落任何数据。
     */
    public StoredResponse merge(MergeRequest req) {
        List<String> parents = req.parents().stream().map(String::trim).toList();
        if (new HashSet<>(parents).size() != parents.size()) {
            throw ApiException.conflict("合批父批在请求内重复");
        }
        String fingerprint = fingerprint("merge", req.childKey(), req.childBatchNo(),
                String.join(SEP, parents));
        return executeIdempotent(CMD_MERGE, req.commandKey(), fingerprint, () -> {
            // 按业务键排序加锁，避免与读数/放行及其他合批单形成死锁
            List<String> lockedKeys = new ArrayList<>(parents);
            Collections.sort(lockedKeys);
            Map<String, BatchRepository.BatchRow> parentRows = new LinkedHashMap<>();
            for (String parentKey : lockedKeys) {
                BatchRepository.BatchRow parent = batchRepo.findBatchForUpdate(parentKey)
                        .orElseThrow(() -> ApiException.notFound("父批不存在: " + parentKey));
                parentRows.put(parentKey, parent);
            }
            if (batchRepo.findBatch(req.childKey()).isPresent()
                    || storageRepo.findMergeByChild(req.childKey()).isPresent()) {
                throw ApiException.conflict("子批 batchKey 已存在: " + req.childKey());
            }
            Set<String> products = new HashSet<>();
            for (String parentKey : parents) {
                BatchRepository.BatchRow parent = parentRows.get(parentKey);
                if (!BatchStatus.RELEASED.name().equals(parent.status())) {
                    throw ApiException.conflict("父批 " + parentKey + " 状态 " + parent.status()
                            + " 不允许合批，仅当前 RELEASED 且无未处置偏差的批次可合批");
                }
                if (!storageRepo.findOpenDeviationsByBatch(parentKey).isEmpty()) {
                    throw ApiException.unprocessable(
                            "父批 " + parentKey + " 存在未处置仓储温度偏差，合批整单失败");
                }
                assertNoRecalledAncestor(parentKey);
                products.add(parent.productCode());
            }
            if (products.size() != 1) {
                throw ApiException.unprocessable("合批父批必须属于同一产品编码");
            }
            String productCode = products.iterator().next();

            // 必做检验项取并集，按父批顺序、批内顺序首次出现；生产时间取最早
            List<String> requiredTests = new ArrayList<>();
            Set<String> seenTests = new HashSet<>();
            String earliestProducedAt = null;
            for (String parentKey : parents) {
                BatchRepository.BatchRow parent = parentRows.get(parentKey);
                if (earliestProducedAt == null
                        || parent.producedAt().compareTo(earliestProducedAt) < 0) {
                    earliestProducedAt = parent.producedAt();
                }
                for (String testItem : batchRepo.findRequiredTests(parentKey)) {
                    if (seenTests.add(testItem)) {
                        requiredTests.add(testItem);
                    }
                }
            }

            String now = now();
            batchRepo.insertBatch(new BatchRepository.BatchRow(0L, req.childKey(), productCode,
                    req.childBatchNo(), earliestProducedAt, BatchStatus.QUARANTINED.name(), now));
            for (int i = 0; i < requiredTests.size(); i++) {
                batchRepo.insertRequiredTest(req.childKey(), requiredTests.get(i), i + 1);
            }
            long mergeId = storageRepo.insertMergeOrder(
                    new StorageRepository.MergeRow(0L, req.childKey(), now));
            for (int i = 0; i < parents.size(); i++) {
                batchRepo.insertLineage(new BatchRepository.LineageRow(0L, parents.get(i),
                        req.childKey(), i + 1, now));
                storageRepo.insertMergeParent(new StorageRepository.MergeParentRow(0L, mergeId,
                        parents.get(i), i + 1, now));
            }
            MergeResponse body = new MergeResponse(req.childKey(), req.childBatchNo(), productCode,
                    BatchStatus.QUARANTINED, parents, requiredTests, Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }

    // ---------- 内部辅助 ----------

    private StorageRepository.DeviationRow insertDeviation(StorageRepository.ReadingRow reading,
                                                           DeviationType type,
                                                           StorageRepository.SegmentRow segment,
                                                           BigDecimal observedTemperature,
                                                           String gapStart, String gapEnd,
                                                           String createdAt) {
        String suffix = type == DeviationType.OUT_OF_LIMIT ? "O" : "M";
        String deviationKey = "DEV-" + reading.id() + "-" + suffix;
        StorageRepository.DeviationRow row = new StorageRepository.DeviationRow(0L, deviationKey,
                reading.batchKey(), reading.segmentKey(), reading.id(), type.name(),
                DeviationStatus.OPEN.name(), 1, observedTemperature,
                segment.upperLimit(), segment.lowerLimit(), gapStart, gapEnd, createdAt, null);
        storageRepo.insertDeviation(row);
        return storageRepo.findDeviationByKey(deviationKey)
                .orElseThrow(() -> new IllegalStateException("偏差入库后无法回读"));
    }

    private DeviationResponse toDeviationResponse(StorageRepository.DeviationRow row) {
        List<ConfirmationResponse> confirmations = storageRepo.findConfirmations(row.id()).stream()
                .map(c -> new ConfirmationResponse(row.deviationKey(), c.version(), c.actorId(),
                        ApprovalRole.valueOf(c.role()), c.note(), Instant.parse(c.createdAt())))
                .toList();
        return new DeviationResponse(row.deviationKey(), row.batchKey(), row.segmentKey(),
                DeviationType.valueOf(row.type()), DeviationStatus.valueOf(row.status()),
                row.version(), row.observedTemperatureCelsius(),
                row.upperLimitSnapshot(), row.lowerLimitSnapshot(),
                row.gapStart() == null ? null : Instant.parse(row.gapStart()),
                row.gapEnd() == null ? null : Instant.parse(row.gapEnd()),
                Instant.parse(row.createdAt()),
                row.disposedAt() == null ? null : Instant.parse(row.disposedAt()),
                confirmations);
    }

    private String gateStatus(int openCount) {
        return openCount > 0 ? GATE_CLOSED : GATE_OPEN;
    }

    private void validateLimits(BigDecimal upperLimit, BigDecimal lowerLimit) {
        if (upperLimit == null || lowerLimit == null) {
            throw ApiException.badRequest("温度上下限不能为空");
        }
        if (lowerLimit.compareTo(upperLimit) >= 0) {
            throw ApiException.badRequest("lowerLimit 必须严格小于 upperLimit，单位℃");
        }
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
     * 读数 requestId 快照查询：命中且指纹一致返回首次响应；指纹不一致抛 409；未命中返回空。
     */
    private Optional<StoredResponse> loggedReading(String requestId, String fingerprint) {
        var existing = storageRepo.findReadingRequest(requestId);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        StorageRepository.ReadingRequestRow row = existing.get();
        if (!row.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId 已以不同温度/时刻/仓储段登记: " + requestId);
        }
        return Optional.of(new StoredResponse(row.responseStatus(), row.responseBody()));
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
                    var logged = loggedCommand(type, commandKey, fingerprint);
                    if (logged.isPresent()) {
                        return logged.get();
                    }
                    StoredResponse response = action.get();
                    batchRepo.insertCommand(new BatchRepository.CommandRow(type, commandKey,
                            fingerprint, response.status(), response.body()), now());
                    return response;
                });
            } catch (DuplicateKeyException e) {
                // 并发同命令键冲突：回滚后重试，读取对方已提交的命令快照
            }
        }
        throw ApiException.conflict("命令并发冲突，请重试: " + commandKey);
    }

    private Optional<StoredResponse> loggedCommand(String type, String commandKey,
                                                   String fingerprint) {
        var existing = batchRepo.findCommand(type, commandKey);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        BatchRepository.CommandRow row = existing.get();
        if (!row.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("commandKey 已以不同参数使用: " + commandKey);
        }
        return Optional.of(new StoredResponse(row.responseStatus(), row.responseBody()));
    }

    /**
     * 若任一级祖先已被召回则抛 422：召回祖先的后代禁止参与合批。
     * 合批子批可有多个父批，逐层 BFS 检查全部祖先并去重。
     */
    private void assertNoRecalledAncestor(String batchKey) {
        Map<String, List<String>> parentsOf = new HashMap<>();
        for (BatchRepository.LineageRow row : batchRepo.findAllLineage()) {
            parentsOf.computeIfAbsent(row.childKey(), k -> new ArrayList<>()).add(row.parentKey());
        }
        Set<String> recalled = new HashSet<>(batchRepo.findRecalledKeys());
        Set<String> visited = new HashSet<>();
        List<String> queue = new ArrayList<>();
        queue.add(batchKey);
        visited.add(batchKey);
        while (!queue.isEmpty()) {
            String current = queue.remove(0);
            for (String parent : parentsOf.getOrDefault(current, List.of())) {
                if (recalled.contains(parent)) {
                    throw ApiException.unprocessable("祖先批次 " + parent + " 已召回，禁止合批");
                }
                if (visited.add(parent)) {
                    queue.add(parent);
                }
            }
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
