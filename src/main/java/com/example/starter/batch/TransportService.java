package com.example.starter.batch;

import com.example.starter.batch.dto.DispositionRequest;
import com.example.starter.batch.dto.DispositionResponse;
import com.example.starter.batch.dto.ReadingRequest;
import com.example.starter.batch.dto.ReadingResponse;
import com.example.starter.batch.dto.ReadingUploadResponse;
import com.example.starter.batch.dto.RegisterSegmentRequest;
import com.example.starter.batch.dto.ReleaseHoldRequest;
import com.example.starter.batch.dto.SegmentResponse;
import com.example.starter.batch.dto.TemperatureStatusResponse;
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
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 运输温控核心服务：运输段登记、温度读数上传、异常闭包（逐段处置）与温控冻结解除/查询。
 *
 * <p>并发策略：段登记/读数/处置/解除均在事务内先对 batch 行 SELECT ... FOR UPDATE，
 * 同批次温控操作与到货放行/拆分按事务提交顺序裁决；读数再对 segment 行加锁。
 * commandKey 幂等与既有命令一致：同键同参重放首次响应，同键异参 409，失败不占键。
 */
@Service
public class TransportService {

    static final String CMD_REGISTER_SEGMENT = "REGISTER_SEGMENT";
    static final String CMD_UPLOAD_READING = "UPLOAD_READING";
    static final String CMD_DISPOSE = "DISPOSE_EXCURSION";
    static final String CMD_RELEASE_HOLD = "RELEASE_TEMPERATURE_HOLD";

    /**
     * 相邻读数允许的最大间隔：超过 30 分钟即判定运输异常。
     */
    private static final Duration MAX_READING_GAP = Duration.ofMinutes(30);

    /**
     * 指纹拼接分隔符（NUL）：业务参数不可能包含该字符，避免拼接碰撞。
     */
    private static final String SEP = new String(new char[]{'\u0000'});

    private static final int IDEMPOTENCY_MAX_ATTEMPTS = 3;

    private final BatchRepository repo;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper;

    public TransportService(BatchRepository repo,
                            PlatformTransactionManager transactionManager,
                            ObjectMapper objectMapper) {
        this.repo = repo;
        this.tx = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    /**
     * 登记运输段：区间左闭右开 [startAt,endAt)，同批次段间不得重叠；
     * 批次处于 TEMPERATURE_HOLD 时不得继续移交，返回 409。
     */
    public StoredResponse registerSegment(String batchKey, String actorId, RegisterSegmentRequest req) {
        String actor = requireActor(actorId);
        validateRange(req.minTemp(), req.maxTemp(), req.startAt(), req.endAt());
        String fingerprint = fingerprint("registerSegment", batchKey, req.segmentKey(),
                req.startAt().toString(), req.endAt().toString(),
                req.minTemp().toPlainString(), req.maxTemp().toPlainString(), actor);
        return executeIdempotent(CMD_REGISTER_SEGMENT, req.commandKey(), fingerprint, () -> {
            BatchRepository.BatchRow batch = lockBatch(batchKey);
            // 行锁等待期间同键命令可能已由并发请求提交，锁后重查快照避免误判冲突
            StoredResponse replay = loggedResponse(CMD_REGISTER_SEGMENT, req.commandKey(), fingerprint)
                    .orElse(null);
            if (replay != null) {
                return replay;
            }
            if (batch.temperatureHold()) {
                throw ApiException.conflict("批次处于 TEMPERATURE_HOLD 温控冻结，未解除前不得继续移交登记运输段");
            }
            if (repo.findSegment(req.segmentKey()).isPresent()) {
                throw ApiException.conflict("segmentKey 已存在: " + req.segmentKey());
            }
            for (BatchRepository.SegmentRow existing : repo.findSegmentsByBatchForUpdate(batchKey)) {
                Instant s0 = Instant.parse(existing.startAt());
                Instant e0 = Instant.parse(existing.endAt());
                // 左闭右开重叠判定：start < e0 && s0 < end；首尾相接（start == e0）不算重叠
                if (req.startAt().isBefore(e0) && s0.isBefore(req.endAt())) {
                    throw ApiException.unprocessable("运输段与既有段 " + existing.segmentKey()
                            + " 时间区间重叠，运输段左闭右开且同一批次不得重叠");
                }
            }
            String now = now();
            repo.insertSegment(new BatchRepository.SegmentRow(0L, req.segmentKey(), batchKey,
                    req.startAt().toString(), req.endAt().toString(),
                    req.minTemp().toPlainString(), req.maxTemp().toPlainString(),
                    actor, SegmentStatus.NORMAL.name(), now));
            SegmentResponse body = toSegmentResponse(
                    repo.findSegment(req.segmentKey()).orElseThrow());
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 上传温度读数：必须落在所属段 [startAt,endAt) 内且同段时刻严格递增，违反 422；
     * 越界或与上一条读数间隔超过 30 分钟时段转 EXCURSION、批次置 TEMPERATURE_HOLD。
     * 读数与异常状态一旦落库不可改写。
     */
    public StoredResponse uploadReading(String batchKey, String segmentKey, ReadingRequest req) {
        validateScale(req.temperature(), "temperature");
        String fingerprint = fingerprint("uploadReading", batchKey, segmentKey,
                req.readAt().toString(), req.temperature().toPlainString());
        return executeIdempotent(CMD_UPLOAD_READING, req.commandKey(), fingerprint, () -> {
            BatchRepository.BatchRow batch = lockBatch(batchKey);
            StoredResponse replay = loggedResponse(CMD_UPLOAD_READING, req.commandKey(), fingerprint)
                    .orElse(null);
            if (replay != null) {
                return replay;
            }
            BatchRepository.SegmentRow segment = lockSegmentOfBatch(batchKey, segmentKey);
            Instant start = Instant.parse(segment.startAt());
            Instant end = Instant.parse(segment.endAt());
            if (req.readAt().isBefore(start) || !req.readAt().isBefore(end)) {
                throw ApiException.unprocessable("读数时刻 " + req.readAt()
                        + " 不在运输段 [startAt,endAt) 区间内: " + segmentKey);
            }
            List<BatchRepository.ReadingRow> readings = repo.findReadings(segmentKey);
            BatchRepository.ReadingRow last = readings.isEmpty() ? null : readings.get(readings.size() - 1);
            if (last != null && !req.readAt().isAfter(Instant.parse(last.readAt()))) {
                throw ApiException.unprocessable("读数时刻必须严格递增，新读数 " + req.readAt()
                        + " 不晚于上一条读数时刻 " + last.readAt());
            }

            BigDecimal min = new BigDecimal(segment.minTemp());
            BigDecimal max = new BigDecimal(segment.maxTemp());
            boolean outOfRange = req.temperature().compareTo(min) < 0
                    || req.temperature().compareTo(max) > 0;
            boolean gapExceeded = last != null
                    && Duration.between(Instant.parse(last.readAt()), req.readAt()).compareTo(MAX_READING_GAP) > 0;
            boolean violation = outOfRange || gapExceeded;
            // 仅段由 NORMAL 首次转为 EXCURSION 时产生一次门禁事件：异常终态段上的后续读数
            // （即使再次越界）不重新置位已解除的冻结
            boolean becameExcursion = violation
                    && SegmentStatus.NORMAL.name().equals(segment.status());

            String now = now();
            int seq = readings.size() + 1;
            repo.insertReading(new BatchRepository.ReadingRow(0L, segmentKey,
                    req.readAt().toString(), req.temperature().toPlainString(), seq, now));
            if (becameExcursion) {
                repo.updateSegmentStatus(segmentKey, SegmentStatus.EXCURSION.name());
            }
            if (becameExcursion && !batch.temperatureHold()) {
                repo.updateTemperatureHold(batchKey, true);
            }
            ReadingUploadResponse body = new ReadingUploadResponse(segmentKey, batchKey,
                    new ReadingResponse(req.readAt(), req.temperature(), seq),
                    becameExcursion ? SegmentStatus.EXCURSION : SegmentStatus.valueOf(segment.status()),
                    batch.temperatureHold() || becameExcursion);
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 异常段逐段处置（异常闭包）：仅 EXCURSION 段可处置且每段至多一次，记录不可改写；
     * 提交人必须为 QUALITY 角色且不得为该段运输录入人。
     */
    public StoredResponse disposeExcursion(String batchKey, String segmentKey, String actorId,
                                           String roleHeader, DispositionRequest req) {
        String actor = requireActor(actorId);
        requireQualityRole(roleHeader);
        String fingerprint = fingerprint("dispose", batchKey, segmentKey, actor, req.actionNote());
        return executeIdempotent(CMD_DISPOSE, req.commandKey(), fingerprint, () -> {
            lockBatch(batchKey);
            StoredResponse replay = loggedResponse(CMD_DISPOSE, req.commandKey(), fingerprint)
                    .orElse(null);
            if (replay != null) {
                return replay;
            }
            BatchRepository.SegmentRow segment = lockSegmentOfBatch(batchKey, segmentKey);
            if (!SegmentStatus.EXCURSION.name().equals(segment.status())) {
                throw ApiException.unprocessable("仅 EXCURSION 异常运输段可处置: " + segmentKey);
            }
            if (segment.recorderId().equals(actor)) {
                throw ApiException.unprocessable("异常处置人不得为该运输段录入人: " + actor);
            }
            if (repo.findDisposition(segmentKey).isPresent()) {
                throw ApiException.conflict("异常段已处置，处置闭包不可改写: " + segmentKey);
            }
            String now = now();
            repo.insertDisposition(new BatchRepository.DispositionRow(0L, segmentKey,
                    req.actionNote(), actor, now));
            DispositionResponse body = new DispositionResponse(segmentKey, req.actionNote(),
                    actor, Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 解除温控冻结：QUALITY 角色且不同于该批任一运输段录入人；同一事务内确认所有
     * EXCURSION 段均已逐段处置，任一段未处置整次 422 且不解除。解除只移除门禁，
     * 异常段 EXCURSION 状态与读数/处置历史均保留。
     */
    public StoredResponse releaseHold(String batchKey, String actorId, String roleHeader,
                                      ReleaseHoldRequest req) {
        String actor = requireActor(actorId);
        requireQualityRole(roleHeader);
        String fingerprint = fingerprint("releaseHold", batchKey, actor, req.investigationNote());
        return executeIdempotent(CMD_RELEASE_HOLD, req.commandKey(), fingerprint, () -> {
            BatchRepository.BatchRow batch = lockBatch(batchKey);
            StoredResponse replay = loggedResponse(CMD_RELEASE_HOLD, req.commandKey(), fingerprint)
                    .orElse(null);
            if (replay != null) {
                return replay;
            }
            List<BatchRepository.SegmentRow> segments = repo.findSegmentsByBatchForUpdate(batchKey);
            if (segments.isEmpty()) {
                throw ApiException.unprocessable("批次无运输段，不存在温控冻结可解除: " + batchKey);
            }
            Set<String> recorders = new HashSet<>();
            for (BatchRepository.SegmentRow s : segments) {
                recorders.add(s.recorderId());
            }
            if (recorders.contains(actor)) {
                throw ApiException.unprocessable("解除调查人不得为该批次任一运输段的录入人: " + actor);
            }
            List<String> undisposed = new ArrayList<>();
            for (BatchRepository.SegmentRow s : segments) {
                if (SegmentStatus.EXCURSION.name().equals(s.status())
                        && repo.findDisposition(s.segmentKey()).isEmpty()) {
                    undisposed.add(s.segmentKey());
                }
            }
            if (!undisposed.isEmpty()) {
                throw ApiException.unprocessable("仍有 EXCURSION 运输段未逐段处置，整次不解除: "
                        + undisposed);
            }
            // 门禁未置位（无异常或已解除）时无解除语义；后续新段异常可再次置位并产生新的解除 episode
            if (!batch.temperatureHold()) {
                throw ApiException.conflict("批次当前不在 TEMPERATURE_HOLD 温控冻结态: " + batchKey);
            }
            List<String> disposedKeys = segments.stream()
                    .filter(s -> SegmentStatus.EXCURSION.name().equals(s.status()))
                    .map(BatchRepository.SegmentRow::segmentKey)
                    .toList();
            String now = now();
            repo.updateTemperatureHold(batchKey, false);
            repo.insertRelease(new BatchRepository.ReleaseRow(0L, batchKey, req.commandKey(),
                    actor, req.investigationNote(), now), now);
            TemperatureStatusResponse.ReleaseHoldResponse body =
                    new TemperatureStatusResponse.ReleaseHoldResponse(actor, req.investigationNote(),
                            disposedKeys, Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 温控状态查询：冻结门禁、段与读数、异常闭包及解除记录。
     */
    public TemperatureStatusResponse temperatureStatus(String batchKey) {
        BatchRepository.BatchRow batch = repo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        List<BatchRepository.SegmentRow> segments = repo.findSegmentsByBatch(batchKey);
        List<SegmentResponse> segmentBodies = segments.stream().map(this::toSegmentResponse).toList();
        int excursionCount = 0;
        List<String> undisposed = new ArrayList<>();
        for (BatchRepository.SegmentRow s : segments) {
            if (SegmentStatus.EXCURSION.name().equals(s.status())) {
                excursionCount++;
                if (repo.findDisposition(s.segmentKey()).isEmpty()) {
                    undisposed.add(s.segmentKey());
                }
            }
        }
        List<String> disposedKeys = segments.stream()
                .filter(s -> SegmentStatus.EXCURSION.name().equals(s.status()))
                .map(BatchRepository.SegmentRow::segmentKey)
                .toList();
        TemperatureStatusResponse.ReleaseHoldResponse release = repo.findRelease(batchKey)
                .map(r -> new TemperatureStatusResponse.ReleaseHoldResponse(
                        r.investigatorId(), r.investigationNote(), disposedKeys,
                        Instant.parse(r.createdAt())))
                .orElse(null);
        return new TemperatureStatusResponse(batchKey, batch.temperatureHold(), segments.size(),
                excursionCount, undisposed, release, segmentBodies);
    }

    // ---------- 内部辅助 ----------

    private BatchRepository.BatchRow lockBatch(String batchKey) {
        return repo.findBatchForUpdate(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
    }

    private BatchRepository.SegmentRow lockSegmentOfBatch(String batchKey, String segmentKey) {
        BatchRepository.SegmentRow segment = repo.findSegmentForUpdate(segmentKey)
                .orElseThrow(() -> ApiException.notFound("运输段不存在: " + segmentKey));
        if (!segment.batchKey().equals(batchKey)) {
            throw ApiException.notFound("运输段 " + segmentKey + " 不属于批次 " + batchKey);
        }
        return segment;
    }

    private SegmentResponse toSegmentResponse(BatchRepository.SegmentRow s) {
        List<ReadingResponse> readings = repo.findReadings(s.segmentKey()).stream()
                .map(r -> new ReadingResponse(Instant.parse(r.readAt()),
                        new BigDecimal(r.temperature()), r.seq()))
                .toList();
        DispositionResponse disposition = repo.findDisposition(s.segmentKey())
                .map(d -> new DispositionResponse(d.segmentKey(), d.actionNote(),
                        d.actorId(), Instant.parse(d.createdAt())))
                .orElse(null);
        return new SegmentResponse(s.segmentKey(), s.batchKey(),
                Instant.parse(s.startAt()), Instant.parse(s.endAt()),
                new BigDecimal(s.minTemp()), new BigDecimal(s.maxTemp()),
                s.recorderId(), SegmentStatus.valueOf(s.status()),
                readings, disposition, Instant.parse(s.createdAt()));
    }

    private void validateRange(BigDecimal minTemp, BigDecimal maxTemp, Instant startAt, Instant endAt) {
        validateScale(minTemp, "minTemp");
        validateScale(maxTemp, "maxTemp");
        if (minTemp.compareTo(maxTemp) > 0) {
            throw ApiException.badRequest("minTemp 不得大于 maxTemp");
        }
        if (!startAt.isBefore(endAt)) {
            throw ApiException.badRequest("startAt 必须早于 endAt（区间左闭右开）");
        }
    }

    /**
     * 温度最多两位小数：scale 超过 2 位（如 12.345）拒绝。
     */
    private void validateScale(BigDecimal value, String field) {
        if (value.stripTrailingZeros().scale() > 2) {
            throw ApiException.badRequest(field + " 最多保留两位小数");
        }
    }

    private String requireActor(String actorId) {
        if (actorId == null || actorId.isBlank()) {
            throw ApiException.badRequest("X-Actor-Id 不能为空");
        }
        return actorId.trim();
    }

    private void requireQualityRole(String roleHeader) {
        if (roleHeader == null || roleHeader.isBlank()) {
            throw ApiException.badRequest("X-Approval-Role 不能为空");
        }
        if (!ApprovalRole.QUALITY.name().equals(roleHeader.trim())) {
            throw ApiException.badRequest("温控异常处置与解除仅可由 QUALITY 质量角色提交");
        }
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
