package com.example.starter.observation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 观测设备时钟偏移矫正与顺序重建业务服务。
 *
 * <p>偏移模型：每台设备（deviceId 唯一）可登记多条偏移记录，每条含生效起始 UTC 时刻与偏移秒数，
 * 按生效起始时刻划分半开区间；同一设备生效起始时刻重复即区间重叠，返回 409。
 * 矫正规则：矫正后时刻 = 设备本地时刻 + 命中偏移秒数；命中判定将设备本地时刻按 UTC 时标
 * 与生效起始时刻比较，取不大于该时刻的最后一条记录，无命中时偏移为 0。
 *
 * <p>合并顺序：同一观测记录的全部提交按（矫正后时刻，设备标识，提交标识）升序排列，
 * 胜出提交为该顺序下的最后一条，其内容反映到观测当前版本；顺序稳定可复现。
 *
 * <p>偏移登记/修改在同一事务内重建受影响区间（本地时刻不早于新记录生效起始时刻）的全部提交：
 * 先记录受影响观测的旧胜出提交，再重算矫正后时刻，最后重判胜出；胜出内容变化时追加观测新版本
 * 并写入不可变重排记录，任一失败整次回滚。已有人工冲突解决结论的观测不被重建覆盖。
 *
 * <p>并发裁决：所有偏移变更与设备观测提交先对 device_registry 行加锁，按事务提交顺序生效——
 * 偏移变更先提交则后续提交按新偏移换算，提交先提交则纳入重建范围。
 * requestId 幂等：同键同参重放首次结果，异参 409，失败不占键。
 */
@Service
public class ClockSkewService {

    private static final String SEPARATOR = String.valueOf((char) 1);

    private final DeviceClockRepository deviceClockRepository;
    private final DeviceObservationRepository deviceObservationRepository;
    private final ReorderRepository reorderRepository;
    private final ObservationRepository observationRepository;
    private final ResolutionRepository resolutionRepository;
    private final RequestLogRepository requestLogRepository;
    private final ObjectMapper objectMapper;

    public ClockSkewService(DeviceClockRepository deviceClockRepository,
                            DeviceObservationRepository deviceObservationRepository,
                            ReorderRepository reorderRepository,
                            ObservationRepository observationRepository,
                            ResolutionRepository resolutionRepository,
                            RequestLogRepository requestLogRepository,
                            ObjectMapper objectMapper) {
        this.deviceClockRepository = deviceClockRepository;
        this.deviceObservationRepository = deviceObservationRepository;
        this.reorderRepository = reorderRepository;
        this.observationRepository = observationRepository;
        this.resolutionRepository = resolutionRepository;
        this.requestLogRepository = requestLogRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 登记设备偏移记录：生效起始时刻与既有记录重复（区间重叠）返回 409。
     * 登记成功后在同一事务内重建受影响提交的矫正后时刻与合并顺序。
     */
    @Transactional
    public OffsetOutcome registerOffset(String deviceId, OffsetChangeRequest request) {
        String fingerprint = fingerprint("OFFSET_REGISTER", deviceId,
                request.effectiveFromUtc().toString(), String.valueOf(request.offsetSeconds()));
        OffsetOutcome replayed = checkOffsetReplay(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        OffsetOutcome concurrent = insertOffsetPlaceholder(request.requestId(), fingerprint, "OFFSET_REGISTER");
        if (concurrent != null) {
            return concurrent;
        }

        deviceClockRepository.ensureDevice(deviceId);
        deviceClockRepository.lockDevice(deviceId);
        if (deviceClockRepository.findOffset(deviceId, request.effectiveFromUtc()).isPresent()) {
            throw ApiException.conflict("offset interval overlaps existing record: " + deviceId
                    + " @ " + request.effectiveFromUtc(), null);
        }
        try {
            deviceClockRepository.insertOffset(deviceId, request.effectiveFromUtc(),
                    request.offsetSeconds(), request.requestId());
        } catch (DuplicateKeyException e) {
            // 并发登记同一生效起始时刻：由主键串行化，后到者按区间重叠处理
            throw ApiException.conflict("offset interval overlaps existing record: " + deviceId
                    + " @ " + request.effectiveFromUtc(), null);
        }
        RebuildResult rebuild = rebuild(deviceId, request.effectiveFromUtc(), null,
                request.offsetSeconds(), request.requestId());
        OffsetChangeResponse body = new OffsetChangeResponse(deviceId, request.effectiveFromUtc(),
                request.offsetSeconds(), rebuild.rebuiltSubmissions(), rebuild.reorders());
        return completeOffset(request.requestId(), HttpStatus.CREATED, body);
    }

    /**
     * 修改既有偏移记录的偏移秒数；记录不存在返回 404。修改后在同一事务内重建受影响区间。
     */
    @Transactional
    public OffsetOutcome modifyOffset(String deviceId, OffsetChangeRequest request) {
        String fingerprint = fingerprint("OFFSET_MODIFY", deviceId,
                request.effectiveFromUtc().toString(), String.valueOf(request.offsetSeconds()));
        OffsetOutcome replayed = checkOffsetReplay(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        OffsetOutcome concurrent = insertOffsetPlaceholder(request.requestId(), fingerprint, "OFFSET_MODIFY");
        if (concurrent != null) {
            return concurrent;
        }

        deviceClockRepository.ensureDevice(deviceId);
        deviceClockRepository.lockDevice(deviceId);
        DeviceOffsetEntry existing = deviceClockRepository.findOffset(deviceId, request.effectiveFromUtc())
                .orElseThrow(() -> ApiException.notFound(
                        "offset record not found: " + deviceId + " @ " + request.effectiveFromUtc()));
        deviceClockRepository.updateOffset(deviceId, request.effectiveFromUtc(),
                request.offsetSeconds(), request.requestId());
        RebuildResult rebuild = rebuild(deviceId, request.effectiveFromUtc(), existing.offsetSeconds(),
                request.offsetSeconds(), request.requestId());
        OffsetChangeResponse body = new OffsetChangeResponse(deviceId, request.effectiveFromUtc(),
                request.offsetSeconds(), rebuild.rebuiltSubmissions(), rebuild.reorders());
        return completeOffset(request.requestId(), HttpStatus.OK, body);
    }

    /**
     * 查询设备偏移明细，按生效起始时刻升序。
     */
    @Transactional(readOnly = true)
    public List<DeviceOffsetEntry> listOffsets(String deviceId) {
        return deviceClockRepository.findOffsets(deviceId);
    }

    /**
     * 设备观测提交：保存原始本地时刻与按命中偏移换算的矫正后时刻（二者客户端不可改写），
     * 并按合并顺序判定胜出提交；胜出时将其内容反映到观测当前版本（新版本或初始版本）。
     * 已有人工冲突解决结论的观测不被提交覆盖，仅记录提交。
     */
    @Transactional
    public SubmitOutcome submit(String observationId, DeviceSubmissionRequest request) {
        String fingerprint = fingerprint("DEVICE_SUBMIT", observationId, request.submissionId(),
                request.deviceId(), request.deviceLocalAt().toString(),
                request.location(), request.reading(), request.note());
        SubmitOutcome replayed = checkSubmitReplay(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        SubmitOutcome concurrent = insertSubmitPlaceholder(request.requestId(), fingerprint);
        if (concurrent != null) {
            return concurrent;
        }

        deviceClockRepository.ensureDevice(request.deviceId());
        deviceClockRepository.lockDevice(request.deviceId());
        if (deviceObservationRepository.findBySubmissionId(request.submissionId()).isPresent()) {
            throw ApiException.conflict("submission already exists: " + request.submissionId(), null);
        }

        int offsetSeconds = matchOffsetSeconds(request.deviceId(), request.deviceLocalAt());
        Instant correctedAtUtc = toUtcInstant(request.deviceLocalAt()).plusSeconds(offsetSeconds);
        DeviceSubmission submission = new DeviceSubmission(request.submissionId(), observationId,
                request.deviceId(), request.deviceLocalAt(), correctedAtUtc, offsetSeconds,
                request.location(), request.reading(), request.note(), request.requestId());
        try {
            deviceObservationRepository.insert(submission);
        } catch (DuplicateKeyException e) {
            // 并发复用同一 submissionId：由主键串行化，后到者按冲突处理
            throw ApiException.conflict("submission already exists: " + request.submissionId(), null);
        }

        Optional<ObservationSnapshot> currentOptional = observationRepository.findCurrentForUpdate(observationId);
        if (currentOptional.isEmpty()) {
            // 观测记录尚不存在：首个提交创建记录；并发首建由主键串行化，后到者重读后继续胜出判定
            ObservationSnapshot initial = new ObservationSnapshot(observationId, 1,
                    request.location(), request.reading(), request.note(), false);
            try {
                observationRepository.insertCurrent(initial);
                observationRepository.insertVersion(initial);
                DeviceSubmissionResponse body = submissionResponse(submission, true, 1);
                return completeSubmit(request.requestId(), HttpStatus.CREATED, body);
            } catch (DuplicateKeyException e) {
                currentOptional = observationRepository.findCurrentForUpdate(observationId);
            }
        }
        ObservationSnapshot current = currentOptional.orElseThrow(() -> ApiException.notFound(
                "observation not found: " + observationId));
        if (current.deleted()) {
            throw ApiException.gone("observation already deleted: " + observationId);
        }

        boolean manualResolved = !resolutionRepository.findByObservationId(observationId).isEmpty();
        boolean applied = false;
        int version = current.version();
        if (!manualResolved) {
            DeviceSubmission winner = deviceObservationRepository.findWinner(observationId)
                    .orElseThrow(() -> new IllegalStateException(
                            "submission missing after insert: " + request.submissionId()));
            if (winner.submissionId().equals(request.submissionId())) {
                applied = true;
                if (!sameContent(current, winner)) {
                    ObservationSnapshot next = new ObservationSnapshot(observationId, current.version() + 1,
                            winner.location(), winner.reading(), winner.note(), false);
                    observationRepository.updateCurrent(next);
                    observationRepository.insertVersion(next);
                    version = next.version();
                }
            }
        }
        DeviceSubmissionResponse body = submissionResponse(submission, applied, version);
        return completeSubmit(request.requestId(), HttpStatus.CREATED, body);
    }

    /**
     * 按观测记录查询全部设备提交，按合并顺序（矫正后时刻，设备标识，提交标识）升序；
     * 观测记录不存在返回 404。
     */
    @Transactional(readOnly = true)
    public List<DeviceSubmission> listSubmissions(String observationId) {
        observationRepository.findCurrent(observationId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));
        return deviceObservationRepository.findByObservationId(observationId);
    }

    /**
     * 按观测记录查询重排记录，按落库时间先后排序；观测记录不存在返回 404。
     */
    @Transactional(readOnly = true)
    public List<ObservationReorder> listReorders(String observationId) {
        observationRepository.findCurrent(observationId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));
        return reorderRepository.findByObservationId(observationId);
    }

    /**
     * 偏移重建（在偏移登记/修改事务内执行）：重算受影响提交的矫正后时刻并重判胜出。
     * 胜出提交变化且内容不同（且观测无人工解决结论）时追加新版本并写入不可变重排记录；
     * 原始本地时刻与既有冲突解决记录不被改写。任一步失败抛出异常，整次事务回滚。
     */
    private RebuildResult rebuild(String deviceId, Instant effectiveFromUtc, Integer oldOffsetSeconds,
                                  int newOffsetSeconds, String requestId) {
        LocalDateTime fromLocal = LocalDateTime.ofInstant(effectiveFromUtc, ZoneOffset.UTC);
        List<String> observationIds = deviceObservationRepository.findAffectedObservationIds(deviceId, fromLocal);
        // 先按旧矫正时刻记录各受影响观测的胜出提交，再应用新偏移
        Map<String, DeviceSubmission> oldWinners = new LinkedHashMap<>();
        for (String observationId : observationIds) {
            deviceObservationRepository.findWinner(observationId)
                    .ifPresent(winner -> oldWinners.put(observationId, winner));
        }

        int rebuilt = 0;
        for (DeviceSubmission submission : deviceObservationRepository.findAffected(deviceId, fromLocal)) {
            int offsetSeconds = matchOffsetSeconds(submission.deviceId(), submission.deviceLocalAt());
            Instant correctedAtUtc = toUtcInstant(submission.deviceLocalAt()).plusSeconds(offsetSeconds);
            if (offsetSeconds != submission.offsetSeconds()
                    || !correctedAtUtc.equals(submission.correctedAtUtc())) {
                deviceObservationRepository.updateCorrection(
                        submission.submissionId(), correctedAtUtc, offsetSeconds);
                rebuilt++;
            }
        }

        List<ObservationReorder> reorders = new ArrayList<>();
        for (String observationId : observationIds) {
            DeviceSubmission oldWinner = oldWinners.get(observationId);
            DeviceSubmission newWinner = deviceObservationRepository.findWinner(observationId).orElse(null);
            if (oldWinner == null || newWinner == null
                    || oldWinner.submissionId().equals(newWinner.submissionId())) {
                continue;
            }
            // 已人工选择的冲突解决结论优先保留，不被重建覆盖
            if (!resolutionRepository.findByObservationId(observationId).isEmpty()) {
                continue;
            }
            ObservationSnapshot current = observationRepository.findCurrentForUpdate(observationId)
                    .orElse(null);
            if (current == null || current.deleted() || sameContent(current, newWinner)) {
                continue;
            }
            ObservationSnapshot next = new ObservationSnapshot(observationId, current.version() + 1,
                    newWinner.location(), newWinner.reading(), newWinner.note(), false);
            observationRepository.updateCurrent(next);
            observationRepository.insertVersion(next);
            ObservationReorder reorder = new ObservationReorder(
                    UUID.randomUUID().toString(), observationId, deviceId, effectiveFromUtc,
                    oldOffsetSeconds, newOffsetSeconds,
                    oldWinner.submissionId(), newWinner.submissionId(),
                    orderKey(oldWinner), orderKey(newWinner),
                    current.version(), next.version(), requestId);
            reorderRepository.insert(reorder);
            reorders.add(reorder);
        }
        return new RebuildResult(rebuilt, List.copyOf(reorders));
    }

    /**
     * 命中设备本地时刻对应的偏移秒数；无命中偏移记录时为 0。
     */
    private int matchOffsetSeconds(String deviceId, LocalDateTime deviceLocalAt) {
        return deviceClockRepository.matchOffset(deviceId, toUtcInstant(deviceLocalAt))
                .map(DeviceOffsetEntry::offsetSeconds)
                .orElse(0);
    }

    /**
     * 将设备本地时刻按 UTC 时标解释为 Instant（用于偏移命中与矫正换算）。
     */
    private Instant toUtcInstant(LocalDateTime deviceLocalAt) {
        return deviceLocalAt.toInstant(ZoneOffset.UTC);
    }

    /**
     * 胜出判定用的稳定顺序键：矫正后时刻 | 设备标识 | 提交标识。
     */
    private String orderKey(DeviceSubmission submission) {
        return submission.correctedAtUtc() + "|" + submission.deviceId() + "|" + submission.submissionId();
    }

    /**
     * 判断观测当前内容是否与提交内容一致（读数按数值，其余按原文）。
     */
    private boolean sameContent(ObservationSnapshot current, DeviceSubmission submission) {
        return Objects.equals(current.location(), submission.location())
                && Objects.equals(current.note(), submission.note())
                && readingEquals(current.reading(), submission.reading());
    }

    private boolean readingEquals(String left, String right) {
        if (left == null || right == null) {
            return Objects.equals(left, right);
        }
        return new BigDecimal(left).compareTo(new BigDecimal(right)) == 0;
    }

    private DeviceSubmissionResponse submissionResponse(DeviceSubmission submission,
                                                        boolean applied, int version) {
        return new DeviceSubmissionResponse(submission.submissionId(), submission.observationId(),
                submission.deviceId(), submission.deviceLocalAt(), submission.correctedAtUtc(),
                submission.offsetSeconds(), applied, version);
    }

    // ---------- 幂等去重（与 ObservationService 同一套占位/回填机制） ----------

    /**
     * 偏移变更请求的幂等检查：同键同参返回原成功结果；同键异参返回 409；无记录返回 null 继续执行。
     */
    private OffsetOutcome checkOffsetReplay(String requestId, String fingerprint) {
        Optional<RequestLogEntry> existing = requestLogRepository.find(requestId);
        if (existing.isEmpty()) {
            return null;
        }
        RequestLogEntry entry = existing.get();
        if (!entry.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
        }
        return new OffsetOutcome(entry.responseStatus(), readBody(entry.responseBody(), OffsetChangeResponse.class));
    }

    private OffsetOutcome insertOffsetPlaceholder(String requestId, String fingerprint, String operation) {
        try {
            requestLogRepository.insertPlaceholder(requestId, fingerprint, operation);
            return null;
        } catch (DuplicateKeyException e) {
            RequestLogEntry entry = requestLogRepository.find(requestId)
                    .orElseThrow(() -> ApiException.conflict("requestId conflict: " + requestId, null));
            if (!entry.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
            }
            return new OffsetOutcome(entry.responseStatus(), readBody(entry.responseBody(), OffsetChangeResponse.class));
        }
    }

    private OffsetOutcome completeOffset(String requestId, HttpStatus status, OffsetChangeResponse body) {
        requestLogRepository.complete(requestId, status.value(), writeBody(body));
        return new OffsetOutcome(status.value(), body);
    }

    private SubmitOutcome checkSubmitReplay(String requestId, String fingerprint) {
        Optional<RequestLogEntry> existing = requestLogRepository.find(requestId);
        if (existing.isEmpty()) {
            return null;
        }
        RequestLogEntry entry = existing.get();
        if (!entry.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
        }
        return new SubmitOutcome(entry.responseStatus(), readBody(entry.responseBody(), DeviceSubmissionResponse.class));
    }

    private SubmitOutcome insertSubmitPlaceholder(String requestId, String fingerprint) {
        try {
            requestLogRepository.insertPlaceholder(requestId, fingerprint, "DEVICE_SUBMIT");
            return null;
        } catch (DuplicateKeyException e) {
            RequestLogEntry entry = requestLogRepository.find(requestId)
                    .orElseThrow(() -> ApiException.conflict("requestId conflict: " + requestId, null));
            if (!entry.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
            }
            return new SubmitOutcome(entry.responseStatus(), readBody(entry.responseBody(), DeviceSubmissionResponse.class));
        }
    }

    private SubmitOutcome completeSubmit(String requestId, HttpStatus status, DeviceSubmissionResponse body) {
        requestLogRepository.complete(requestId, status.value(), writeBody(body));
        return new SubmitOutcome(status.value(), body);
    }

    private String fingerprint(String operation, String... parts) {
        StringBuilder raw = new StringBuilder(operation);
        for (String part : parts) {
            raw.append(SEPARATOR).append(part == null ? "<null>" : part);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private String writeBody(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize response", e);
        }
    }

    private <T> T readBody(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored response", e);
        }
    }

    /**
     * 偏移登记/修改结果：HTTP 状态码与响应体。
     */
    public record OffsetOutcome(int status, OffsetChangeResponse body) {
    }

    /**
     * 设备观测提交结果：HTTP 状态码与响应体。
     */
    public record SubmitOutcome(int status, DeviceSubmissionResponse body) {
    }

    /**
     * 重建结果：矫正后时刻发生变化的提交数与产生的不可变重排记录。
     */
    private record RebuildResult(int rebuiltSubmissions, List<ObservationReorder> reorders) {
    }
}
