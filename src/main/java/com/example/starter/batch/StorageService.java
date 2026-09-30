package com.example.starter.batch;

import com.example.starter.batch.dto.ConfirmationResponse;
import com.example.starter.batch.dto.ExcursionEntry;
import com.example.starter.batch.dto.ExcursionResponse;
import com.example.starter.batch.dto.ReadingEntry;
import com.example.starter.batch.dto.RegisterSegmentRequest;
import com.example.starter.batch.dto.SegmentResponse;
import com.example.starter.batch.dto.SegmentTimelineResponse;
import com.example.starter.batch.dto.StorageGateResponse;
import com.example.starter.batch.dto.TemperatureReadingRequest;
import com.example.starter.batch.dto.TemperatureReadingResponse;
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
import java.util.List;
import java.util.Optional;

/**
 * 仓储温度偏差核心服务。
 *
 * <p>并发策略：读数入库、限值改版与批次放行/合批在事务内对 batch 行 SELECT ... FOR UPDATE 串行化，
 * 因此读数提交产生的未处置偏差对随后获锁的放行/拆分/合批事务必然可见，不会留下带未处置偏差的已放行批次。
 * 读数 requestId 幂等由 temperature_reading 唯一约束 + 全参数比对保证：同键重放首次结果，换参数 409。
 * 限值与读数均保存当时快照，历史偏差不随后续限值改版回写。
 */
@Service
public class StorageService {

    private static final String CMD_REGISTER_SEGMENT = "REGISTER_SEGMENT";
    private static final String CMD_UPDATE_LIMITS = "UPDATE_LIMITS";
    private static final String CMD_CONFIRM_EXCURSION = "CONFIRM_EXCURSION";

    /**
     * 指纹拼接分隔符（NUL）：业务参数不可能包含该字符，避免拼接碰撞。
     */
    private static final String SEP = "";

    private static final int IDEMPOTENCY_MAX_ATTEMPTS = 3;

    /**
     * 相邻读数最大允许采集间隔：恰好 30 分钟合法，严格大于才生成缺测偏差。
     */
    private static final Duration MAX_READING_GAP = Duration.ofMinutes(30);

    private final BatchRepository batchRepository;
    private final StorageRepository storageRepository;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper;

    public StorageService(BatchRepository batchRepository,
                          StorageRepository storageRepository,
                          PlatformTransactionManager transactionManager,
                          ObjectMapper objectMapper) {
        this.batchRepository = batchRepository;
        this.storageRepository = storageRepository;
        this.tx = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    /**
     * 登记仓储段及初始上下限（版本 1）；同批次内 segmentKey 唯一。
     */
    public StoredResponse registerSegment(String batchKey, RegisterSegmentRequest req) {
        validateLimits(req.lowerLimit(), req.upperLimit());
        String fingerprint = fingerprint("register", batchKey, req.segmentKey(),
                req.lowerLimit().toPlainString(), req.upperLimit().toPlainString());
        return executeIdempotent(CMD_REGISTER_SEGMENT, req.commandKey(), fingerprint, () -> {
            BatchRepository.BatchRow batch = batchRepository.findBatchForUpdate(batchKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
            assertActiveForStorage(batch);
            if (storageRepository.findSegment(batchKey, req.segmentKey()).isPresent()) {
                throw ApiException.conflict("仓储段已存在: " + req.segmentKey());
            }
            String now = now();
            storageRepository.insertSegment(new StorageRepository.SegmentRow(0L, batchKey,
                    req.segmentKey(), req.lowerLimit(), req.upperLimit(), 1, now, now));
            StorageRepository.SegmentRow saved = storageRepository.findSegment(batchKey, req.segmentKey())
                    .orElseThrow(() -> new IllegalStateException("仓储段插入后查询失败"));
            storageRepository.insertLimitVersion(saved.id(), 1, req.lowerLimit(), req.upperLimit(), now);
            SegmentResponse body = new SegmentResponse(batchKey, req.segmentKey(),
                    req.lowerLimit(), req.upperLimit(), 1, Instant.parse(now), Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 限值改版：生成新版本，仅新版本对后续读数生效；历史读数与偏差不回写。
     */
    public StoredResponse updateLimits(String batchKey, String segmentKey, UpdateLimitsRequest req) {
        validateLimits(req.lowerLimit(), req.upperLimit());
        String fingerprint = fingerprint("limits", batchKey, segmentKey,
                req.lowerLimit().toPlainString(), req.upperLimit().toPlainString());
        return executeIdempotent(CMD_UPDATE_LIMITS, req.commandKey(), fingerprint, () -> {
            batchRepository.findBatchForUpdate(batchKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
            StorageRepository.SegmentRow segment = storageRepository.findSegment(batchKey, segmentKey)
                    .orElseThrow(() -> ApiException.notFound("仓储段不存在: " + segmentKey));
            int newVersion = segment.version() + 1;
            String now = now();
            storageRepository.insertLimitVersion(segment.id(), newVersion,
                    req.lowerLimit(), req.upperLimit(), now);
            storageRepository.updateSegmentLimits(segment.id(), req.lowerLimit(), req.upperLimit(),
                    newVersion, now);
            SegmentResponse body = new SegmentResponse(batchKey, segmentKey,
                    req.lowerLimit(), req.upperLimit(), newVersion,
                    Instant.parse(segment.createdAt()), Instant.parse(now));
            return new StoredResponse(200, toJson(body));
        });
    }

    /**
     * 登记温度读数：同 requestId 同参重放首次结果（200），同 requestId 换参数 409。
     * 读数越界或与同段前一读数采集间隔严格大于 30 分钟时生成偏差事件（201，可能一次生成两类）。
     */
    public StoredResponse recordReading(String batchKey, String segmentKey,
                                        TemperatureReadingRequest req) {
        for (int attempt = 0; attempt < IDEMPOTENCY_MAX_ATTEMPTS; attempt++) {
            try {
                return tx.execute(status -> {
                    BatchRepository.BatchRow batch = batchRepository.findBatchForUpdate(batchKey)
                            .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
                    assertActiveForStorage(batch);
                    StorageRepository.SegmentRow segment =
                            storageRepository.findSegment(batchKey, segmentKey)
                                    .orElseThrow(() -> ApiException.notFound("仓储段不存在: " + segmentKey));

                    Optional<StorageRepository.ReadingRow> existing =
                            storageRepository.findReadingByRequestId(req.requestId());
                    if (existing.isPresent()) {
                        return replayOrConflict(existing.get(), batchKey, segment.id(), req);
                    }

                    String now = now();
                    StorageRepository.ReadingRow row = new StorageRepository.ReadingRow(0L, batchKey,
                            segment.id(), req.requestId(), req.readAt().toString(), req.temperature(),
                            segment.lowerLimit(), segment.upperLimit(), now);
                    storageRepository.insertReading(row);
                    StorageRepository.ReadingRow saved =
                            storageRepository.findReadingByRequestId(req.requestId())
                                    .orElseThrow(() -> new IllegalStateException("读数插入后查询失败"));

                    List<String> excursionKeys = new ArrayList<>(2);
                    if (isOutOfRange(saved.temperature(), segment.lowerLimit(), segment.upperLimit())) {
                        String key = excursionKey(req.requestId(), ExcursionType.OUT_OF_RANGE);
                        storageRepository.insertExcursion(new StorageRepository.ExcursionRow(0L, batchKey,
                                segment.id(), key, ExcursionType.OUT_OF_RANGE.name(), segment.version(),
                                saved.id(), null, null, null,
                                "读数 " + saved.temperature().toPlainString() + "℃ 超出限值 ["
                                        + segment.lowerLimit().toPlainString() + ", "
                                        + segment.upperLimit().toPlainString() + "]℃",
                                ExcursionStatus.OPEN.name(), now, null));
                        excursionKeys.add(key);
                    }
                    storageRepository.findPreviousReading(segment.id(), saved.readAt())
                            .ifPresent(prev -> {
                                Duration gap = Duration.between(Instant.parse(prev.readAt()),
                                        Instant.parse(saved.readAt()));
                                if (gap.compareTo(MAX_READING_GAP) > 0) {
                                    String key = excursionKey(req.requestId(),
                                            ExcursionType.MISSING_INTERVAL);
                                    storageRepository.insertExcursion(new StorageRepository.ExcursionRow(
                                            0L, batchKey, segment.id(), key,
                                            ExcursionType.MISSING_INTERVAL.name(), segment.version(),
                                            saved.id(), prev.id(), prev.readAt(), saved.readAt(),
                                            "相邻读数间隔 " + gap.toMinutes() + " 分钟，超过 30 分钟上限",
                                            ExcursionStatus.OPEN.name(), now, null));
                                    excursionKeys.add(key);
                                }
                            });

                    TemperatureReadingResponse body = new TemperatureReadingResponse(
                            req.requestId(), batchKey, segmentKey, req.readAt(), req.temperature(),
                            segment.lowerLimit(), segment.upperLimit(), false,
                            List.copyOf(excursionKeys), Instant.parse(now));
                    return new StoredResponse(201, toJson(body));
                });
            } catch (DuplicateKeyException e) {
                // 并发同 requestId 插入冲突：回滚后重试，读取对方已提交读数后重放或判 409
            }
        }
        throw ApiException.conflict("读数并发冲突，请重试: " + req.requestId());
    }

    /**
     * 偏差处置确认：两名不同 actorId、不同角色（WAREHOUSE/QUALITY）依次确认同一偏差版本，
     * 第二次确认落定后偏差 RESOLVED。
     */
    public StoredResponse confirmExcursion(String excursionKey, String actorId, String roleHeader,
                                           com.example.starter.batch.dto.ConfirmExcursionRequest req) {
        if (actorId == null || actorId.isBlank()) {
            throw ApiException.badRequest("X-Actor-Id 不能为空");
        }
        StorageRole role = parseRole(roleHeader);
        String actor = actorId.trim();
        String fingerprint = fingerprint("confirm", excursionKey, actor, role.name());
        return executeIdempotent(CMD_CONFIRM_EXCURSION, req.commandKey(), fingerprint, () -> {
            StorageRepository.ExcursionRow excursion =
                    storageRepository.findExcursionForUpdate(excursionKey)
                            .orElseThrow(() -> ApiException.notFound("偏差不存在: " + excursionKey));
            if (ExcursionStatus.RESOLVED.name().equals(excursion.status())) {
                throw ApiException.conflict("偏差已处置完成: " + excursionKey);
            }
            List<StorageRepository.ConfirmationRow> confirmations =
                    storageRepository.findConfirmations(excursion.id());
            String now = now();
            int seq;
            ExcursionStatus newStatus;
            if (confirmations.isEmpty()) {
                seq = 1;
                newStatus = ExcursionStatus.OPEN;
            } else {
                StorageRepository.ConfirmationRow first = confirmations.get(0);
                if (first.actorId().equals(actor)) {
                    throw ApiException.conflict("同一确认人不能两次确认: " + actor);
                }
                if (first.role().equals(role.name())) {
                    throw ApiException.conflict("角色 " + role + " 已确认过，需要另一种角色");
                }
                seq = 2;
                newStatus = ExcursionStatus.RESOLVED;
            }
            storageRepository.insertConfirmation(new StorageRepository.ConfirmationRow(0L,
                    excursion.id(), req.commandKey(), actor, role.name(),
                    excursion.limitVersion(), seq, now));
            if (newStatus == ExcursionStatus.RESOLVED) {
                storageRepository.resolveExcursion(excursion.id(), now);
            }
            ConfirmationResponse body = new ConfirmationResponse(excursionKey, actor, role,
                    excursion.limitVersion(), seq, newStatus, Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 时刻查询：截至 at（含）的原始读数、超限或缺测区间、处置人及批次门禁状态。
     */
    public SegmentTimelineResponse timeline(String batchKey, String segmentKey, Instant at) {
        batchRepository.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        StorageRepository.SegmentRow segment = storageRepository.findSegment(batchKey, segmentKey)
                .orElseThrow(() -> ApiException.notFound("仓储段不存在: " + segmentKey));
        Instant asOf = at == null ? Instant.now() : at;
        String asOfText = asOf.toString();

        List<ReadingEntry> readings = storageRepository.findReadings(segment.id(), asOfText).stream()
                .map(r -> new ReadingEntry(r.requestId(), Instant.parse(r.readAt()), r.temperature(),
                        r.lowerLimit(), r.upperLimit()))
                .toList();

        List<ExcursionEntry> excursions = new ArrayList<>();
        boolean blocked = false;
        for (StorageRepository.ExcursionRow e :
                storageRepository.findExcursionsBySegment(segment.id(), asOfText)) {
            List<ExcursionEntry.ConfirmationEntry> confirms =
                    storageRepository.findConfirmations(e.id()).stream()
                            .filter(c -> !Instant.parse(c.createdAt()).isAfter(asOf))
                            .map(c -> new ExcursionEntry.ConfirmationEntry(c.actorId(),
                                    StorageRole.valueOf(c.role()), c.limitVersion(), c.seq(),
                                    Instant.parse(c.createdAt())))
                            .toList();
            boolean stillOpenAt = e.resolvedAt() == null || Instant.parse(e.resolvedAt()).isAfter(asOf);
            if (stillOpenAt) {
                blocked = true;
            }
            excursions.add(new ExcursionEntry(e.excursionKey(),
                    ExcursionType.valueOf(e.eventType()), e.limitVersion(),
                    e.intervalFrom() == null ? null : Instant.parse(e.intervalFrom()),
                    e.intervalTo() == null ? null : Instant.parse(e.intervalTo()),
                    e.detail(), ExcursionStatus.valueOf(e.status()),
                    e.resolvedAt() == null ? null : Instant.parse(e.resolvedAt()), confirms));
        }
        return new SegmentTimelineResponse(batchKey, segmentKey, asOf,
                blocked ? "BLOCKED" : "OPEN", readings, excursions);
    }

    /**
     * 批次仓储门禁：聚合全部仓储段当前未处置偏差。
     */
    public StorageGateResponse storageGate(String batchKey) {
        batchRepository.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        Instant now = Instant.now();
        List<StorageGateResponse.SegmentGate> segments = new ArrayList<>();
        int totalOpen = 0;
        for (StorageRepository.SegmentRow segment : storageRepository.findSegments(batchKey)) {
            int open = (int) storageRepository.findExcursionsBySegment(segment.id(), now.toString())
                    .stream()
                    .filter(e -> ExcursionStatus.OPEN.name().equals(e.status()))
                    .count();
            totalOpen += open;
            segments.add(new StorageGateResponse.SegmentGate(segment.segmentKey(), open,
                    open > 0 ? "BLOCKED" : "OPEN"));
        }
        return new StorageGateResponse(batchKey, totalOpen > 0 ? "BLOCKED" : "OPEN",
                totalOpen, segments, now);
    }

    /**
     * 单条偏差详情。
     */
    public ExcursionResponse getExcursion(String excursionKey) {
        StorageRepository.ExcursionRow e = storageRepository.findExcursion(excursionKey)
                .orElseThrow(() -> ApiException.notFound("偏差不存在: " + excursionKey));
        StorageRepository.SegmentRow segment = storageRepository.findSegmentById(e.segmentId())
                .orElseThrow(() -> new IllegalStateException("偏差关联仓储段缺失: " + e.segmentId()));
        return new ExcursionResponse(e.excursionKey(), e.batchKey(), segment.segmentKey(),
                ExcursionType.valueOf(e.eventType()), e.limitVersion(), e.readingId(),
                e.intervalFrom() == null ? null : Instant.parse(e.intervalFrom()),
                e.intervalTo() == null ? null : Instant.parse(e.intervalTo()),
                e.detail(), ExcursionStatus.valueOf(e.status()), Instant.parse(e.createdAt()),
                e.resolvedAt() == null ? null : Instant.parse(e.resolvedAt()));
    }

    /**
     * 批次当前是否存在未处置仓储温度偏差；放行、拆分、合批门禁调用。
     */
    public boolean hasOpenExcursions(String batchKey) {
        return !storageRepository.findOpenExcursions(batchKey).isEmpty();
    }

    // ---------- 内部方法 ----------

    /**
     * 已入库读数重放：参数（所属批次/仓储段、采集时刻、温度）一致返回首次结果；不一致 409。
     */
    private StoredResponse replayOrConflict(StorageRepository.ReadingRow saved, long batchKeyIdUnused,
                                            long expectedSegmentId, TemperatureReadingRequest req) {
        boolean sameContent = saved.segmentId() == expectedSegmentId
                && saved.readAt().equals(req.readAt().toString())
                && saved.temperature().compareTo(req.temperature()) == 0;
        if (!sameContent) {
            throw ApiException.conflict("requestId 已以不同参数使用: " + req.requestId());
        }
        StorageRepository.SegmentRow segment = storageRepository.findSegmentById(saved.segmentId())
                .orElseThrow(() -> new IllegalStateException("读数关联仓储段缺失: " + saved.segmentId()));
        List<String> keys = storageRepository.findExcursionsByReading(saved.id()).stream()
                .map(StorageRepository.ExcursionRow::excursionKey)
                .toList();
        TemperatureReadingResponse body = new TemperatureReadingResponse(
                saved.requestId(), saved.batchKey(), segment.segmentKey(),
                Instant.parse(saved.readAt()), saved.temperature(),
                saved.lowerLimit(), saved.upperLimit(), true, keys,
                Instant.parse(saved.createdAt()));
        return new StoredResponse(200, toJson(body));
    }

    private boolean isOutOfRange(BigDecimal temperature, BigDecimal lower, BigDecimal upper) {
        return temperature.compareTo(lower) < 0 || temperature.compareTo(upper) > 0;
    }

    private String excursionKey(String requestId, ExcursionType type) {
        return "EX-" + requestId + "-" + (type == ExcursionType.OUT_OF_RANGE ? "RANGE" : "GAP");
    }

    private void assertActiveForStorage(BatchRepository.BatchRow batch) {
        BatchStatus status = BatchStatus.valueOf(batch.status());
        if (status == BatchStatus.REJECTED || status == BatchStatus.RECALLED
                || status == BatchStatus.SPLIT || status == BatchStatus.MERGED) {
            throw ApiException.conflict("批次状态 " + status + " 不允许登记仓储段或温度读数");
        }
    }

    private void validateLimits(BigDecimal lower, BigDecimal upper) {
        if (lower.compareTo(upper) > 0) {
            throw ApiException.badRequest("lowerLimit 不能大于 upperLimit");
        }
    }

    private StorageRole parseRole(String roleHeader) {
        if (roleHeader == null || roleHeader.isBlank()) {
            throw ApiException.badRequest("X-Storage-Role 不能为空");
        }
        try {
            return StorageRole.valueOf(roleHeader.trim());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("X-Storage-Role 必须为 WAREHOUSE 或 QUALITY");
        }
    }

    /**
     * 幂等执行：同事务内先查 command_log，命中则按指纹返回快照或 409；
     * 未命中执行业务动作并写入快照。并发同键插入冲突时重试，读取已提交结果。
     */
    private StoredResponse executeIdempotent(String type, String commandKey, String fingerprint,
                                             java.util.function.Supplier<StoredResponse> action) {
        for (int attempt = 0; attempt < IDEMPOTENCY_MAX_ATTEMPTS; attempt++) {
            try {
                return tx.execute(status -> {
                    var logged = loggedResponse(type, commandKey, fingerprint);
                    if (logged.isPresent()) {
                        return logged.get();
                    }
                    StoredResponse response = action.get();
                    batchRepository.insertCommand(new BatchRepository.CommandRow(type, commandKey,
                            fingerprint, response.status(), response.body()), now());
                    return response;
                });
            } catch (DuplicateKeyException e) {
                // 并发同事务键冲突：回滚后重试，读取对方已提交的命令快照
            }
        }
        throw ApiException.conflict("命令并发冲突，请重试: " + commandKey);
    }

    private Optional<StoredResponse> loggedResponse(String type, String commandKey,
                                                    String fingerprint) {
        var existing = batchRepository.findCommand(type, commandKey);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        BatchRepository.CommandRow row = existing.get();
        if (!row.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("commandKey 已以不同参数使用: " + commandKey);
        }
        return Optional.of(new StoredResponse(row.responseStatus(), row.responseBody()));
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
