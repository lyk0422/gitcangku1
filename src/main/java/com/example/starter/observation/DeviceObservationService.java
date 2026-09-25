package com.example.starter.observation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 观测设备时钟偏移矫正与顺序重建业务服务。
 *
 * <p>设备以 deviceId 唯一，可登记多条偏移记录；同一设备的偏移区间
 * [effectiveFromUtc, 下一条起始时刻) 不得重叠，起始时刻相同即重叠（409）。
 * 矫正规则：矫正后时刻 = 设备本地时刻 + 命中记录的偏移秒数；
 * 命中记录为生效起始时刻不大于设备本地时刻（按 UTC 时间线比较）的最后一条。
 *
 * <p>合并顺序：全部观测版本按（矫正后时刻, 设备标识, 观测标识, 版本号）升序排位，
 * 保证顺序稳定可复现；每个观测的当前胜出版本为其排位最后的版本。
 *
 * <p>观测提交、偏移登记与偏移修改在同一事务内先锁定 merge_order_lock 全局互斥行，
 * 并发按事务提交顺序裁决：偏移变更先提交则后续观测按新偏移换算，观测先提交则纳入重建范围。
 * 新增偏移记录或修改偏移秒数后，同事务重建受影响设备的全部矫正后时刻与全局合并顺序，
 * 任一步失败整次回滚；原始本地时刻与既有冲突解决记录永不被改写。
 * 重建使某观测当前胜出版本变化时写入不可变重排记录（observation_reorder）。
 *
 * <p>requestId 幂等：同键同参重放首次结果，异参 409，失败不占键。
 */
@Service
public class DeviceObservationService {

    private static final String SEPARATOR = "\u0001";

    /**
     * 全局合并顺序比较器：矫正后时刻、设备标识字典序、观测标识、版本号依次升序。
     */
    private static final Comparator<DeviceObservation> MERGE_ORDER = Comparator
            .comparing(DeviceObservation::correctedAtUtc)
            .thenComparing(DeviceObservation::deviceId)
            .thenComparing(DeviceObservation::observationId)
            .thenComparingInt(DeviceObservation::version);

    private final DeviceOffsetRepository offsetRepository;
    private final DeviceObservationRepository observationRepository;
    private final ReorderRepository reorderRepository;
    private final RequestLogRepository requestLogRepository;
    private final ObjectMapper objectMapper;

    public DeviceObservationService(DeviceOffsetRepository offsetRepository,
                                    DeviceObservationRepository observationRepository,
                                    ReorderRepository reorderRepository,
                                    RequestLogRepository requestLogRepository,
                                    ObjectMapper objectMapper) {
        this.offsetRepository = offsetRepository;
        this.observationRepository = observationRepository;
        this.reorderRepository = reorderRepository;
        this.requestLogRepository = requestLogRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 登记设备偏移记录：生效起始时刻相同即区间重叠返回 409。
     * 登记成功后在同一事务内重建该设备全部观测的矫正后时刻与全局合并顺序。
     */
    @Transactional
    public OffsetOutcome registerOffset(String deviceId, RegisterOffsetRequest request) {
        Instant effectiveFrom = parseInstant(request.effectiveFromUtc(), "effectiveFromUtc");
        String fingerprint = fingerprint("OFFSET_ADD", deviceId, effectiveFrom.toString(),
                String.valueOf(request.offsetSeconds()));
        OffsetOutcome replayed = checkOffsetReplay(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        OffsetOutcome concurrent = insertOffsetPlaceholder(request.requestId(), fingerprint, "OFFSET_ADD");
        if (concurrent != null) {
            return concurrent;
        }

        observationRepository.acquireMergeLock();
        if (offsetRepository.find(deviceId, effectiveFrom).isPresent()) {
            throw ApiException.conflict(
                    "offset interval overlaps existing record: " + deviceId + "@" + effectiveFrom, null);
        }
        DeviceOffset offset = new DeviceOffset(deviceId, effectiveFrom, request.offsetSeconds());
        try {
            offsetRepository.insert(offset);
        } catch (DuplicateKeyException e) {
            // 并发登记同一设备同一起始时刻：由主键串行化，后到者按重叠处理
            throw ApiException.conflict(
                    "offset interval overlaps existing record: " + deviceId + "@" + effectiveFrom, null);
        }
        rebuild(deviceId, request.requestId(), effectiveFrom, null, request.offsetSeconds());
        return completeOffset(request.requestId(), HttpStatus.CREATED, OffsetResponse.of(offset));
    }

    /**
     * 修改既有偏移记录的偏移秒数：记录不存在返回 404；秒数变化时同事务重建。
     */
    @Transactional
    public OffsetOutcome modifyOffset(String deviceId, ModifyOffsetRequest request) {
        Instant effectiveFrom = parseInstant(request.effectiveFromUtc(), "effectiveFromUtc");
        String fingerprint = fingerprint("OFFSET_MODIFY", deviceId, effectiveFrom.toString(),
                String.valueOf(request.offsetSeconds()));
        OffsetOutcome replayed = checkOffsetReplay(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        OffsetOutcome concurrent = insertOffsetPlaceholder(request.requestId(), fingerprint, "OFFSET_MODIFY");
        if (concurrent != null) {
            return concurrent;
        }

        observationRepository.acquireMergeLock();
        DeviceOffset existing = offsetRepository.find(deviceId, effectiveFrom)
                .orElseThrow(() -> ApiException.notFound(
                        "offset record not found: " + deviceId + "@" + effectiveFrom));
        if (existing.offsetSeconds() != request.offsetSeconds()) {
            offsetRepository.updateSeconds(deviceId, effectiveFrom, request.offsetSeconds());
            rebuild(deviceId, request.requestId(), effectiveFrom,
                    existing.offsetSeconds(), request.offsetSeconds());
        }
        DeviceOffset updated = new DeviceOffset(deviceId, effectiveFrom, request.offsetSeconds());
        return completeOffset(request.requestId(), HttpStatus.OK, OffsetResponse.of(updated));
    }

    /**
     * 设备观测提交：按命中的偏移记录换算矫正后时刻，与原始本地时刻一并保存（均不可由客户端改写）。
     * 无命中偏移记录返回 400。提交后重排全局合并顺序并返回当前胜出版本。
     */
    @Transactional
    public SubmitOutcome submit(SubmitObservationRequest request) {
        LocalDateTime deviceLocalTime = parseLocalDateTime(request.deviceLocalTime(), "deviceLocalTime");
        String fingerprint = fingerprint("SUBMIT", request.observationId(), request.deviceId(),
                deviceLocalTime.toString(), request.location(), request.reading(), request.note());
        SubmitOutcome replayed = checkSubmitReplay(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        SubmitOutcome concurrent = insertSubmitPlaceholder(request.requestId(), fingerprint);
        if (concurrent != null) {
            return concurrent;
        }

        observationRepository.acquireMergeLock();
        DeviceOffset covering = offsetRepository.findCovering(request.deviceId(), deviceLocalTime)
                .orElseThrow(() -> ApiException.badRequest(
                        "no offset record covers device local time: " + request.deviceId()
                                + "@" + deviceLocalTime));
        Instant correctedAtUtc = correct(deviceLocalTime, covering);

        List<DeviceObservation> all = new ArrayList<>(observationRepository.findAll());
        int version = all.stream()
                .filter(row -> row.observationId().equals(request.observationId()))
                .mapToInt(DeviceObservation::version)
                .max().orElse(0) + 1;
        DeviceObservation submitted = new DeviceObservation(request.observationId(), version,
                request.deviceId(), deviceLocalTime, correctedAtUtc,
                request.location(), request.reading(), request.note(), 0L);
        all.add(submitted);

        List<DeviceObservation> ranked = rerank(all);
        DeviceObservation persisted = ranked.stream()
                .filter(row -> row.observationId().equals(submitted.observationId())
                        && row.version() == submitted.version())
                .findFirst().orElseThrow();
        observationRepository.insert(persisted);
        persistTiming(all, ranked);

        int currentVersion = winnerVersion(ranked, submitted.observationId());
        SubmitObservationResponse body = new SubmitObservationResponse(
                persisted.observationId(), persisted.version(), persisted.deviceId(),
                persisted.deviceLocalTime(), persisted.correctedAtUtc(), persisted.mergeSeq(),
                currentVersion);
        return completeSubmit(request.requestId(), HttpStatus.CREATED, body);
    }

    /**
     * 查询设备全部偏移记录，按生效起始时刻升序。
     */
    @Transactional(readOnly = true)
    public List<OffsetResponse> listOffsets(String deviceId) {
        return offsetRepository.findByDevice(deviceId).stream()
                .map(OffsetResponse::of)
                .toList();
    }

    /**
     * 查询观测当前状态（当前胜出版本）；无提交记录返回 404。
     */
    @Transactional(readOnly = true)
    public ObservationStateResponse getObservationState(String observationId) {
        List<DeviceObservation> versions = observationRepository.findByObservationId(observationId);
        if (versions.isEmpty()) {
            throw ApiException.notFound("device observation not found: " + observationId);
        }
        DeviceObservation winner = versions.stream().max(MERGE_ORDER).orElseThrow();
        return new ObservationStateResponse(observationId, winner.version(), winner.deviceId(),
                winner.deviceLocalTime(), winner.correctedAtUtc(), winner.mergeSeq(),
                winner.location(), winner.reading(), winner.note(), versions.size());
    }

    /**
     * 查询观测全部版本（按合并顺序排位返回）；无提交记录返回 404。
     */
    @Transactional(readOnly = true)
    public List<ObservationVersionResponse> listObservationVersions(String observationId) {
        List<DeviceObservation> versions = observationRepository.findByObservationId(observationId);
        if (versions.isEmpty()) {
            throw ApiException.notFound("device observation not found: " + observationId);
        }
        int winnerVersion = versions.stream().max(MERGE_ORDER).orElseThrow().version();
        return versions.stream()
                .sorted(MERGE_ORDER)
                .map(row -> new ObservationVersionResponse(row.version(), row.deviceId(),
                        row.deviceLocalTime(), row.correctedAtUtc(), row.mergeSeq(),
                        row.location(), row.reading(), row.note(), row.version() == winnerVersion))
                .toList();
    }

    /**
     * 查询重排记录；observationId 为空时返回全部，按落库时间与标识排序。
     */
    @Transactional(readOnly = true)
    public List<ReorderRecord> listReorders(String observationId) {
        if (observationId == null || observationId.isBlank()) {
            return reorderRepository.findAll();
        }
        return reorderRepository.findByObservationId(observationId);
    }

    // ---------- 矫正与重建 ----------

    /**
     * 矫正换算：矫正后时刻 = 设备本地时刻（按 UTC 时间线取值）+ 命中记录偏移秒数。
     */
    private Instant correct(LocalDateTime deviceLocalTime, DeviceOffset covering) {
        return deviceLocalTime.toInstant(ZoneOffset.UTC).plusSeconds(covering.offsetSeconds());
    }

    /**
     * 偏移变更后的同事务重建：重算该设备全部版本的矫正后时刻，重排全局合并顺序；
     * 任一观测当前胜出版本变化时写入不可变重排记录。任一步失败抛出异常，整次事务回滚。
     */
    private void rebuild(String deviceId, String requestId, Instant effectiveFrom,
                         Integer oldOffsetSeconds, int newOffsetSeconds) {
        List<DeviceObservation> before = observationRepository.findAll();
        Map<String, List<DeviceObservation>> oldByObservation = groupByObservation(before);

        // 重算该设备全部版本的矫正后时刻；偏移记录只增不改起始时刻，覆盖关系不回退，
        // 防御性兜底：无命中记录时保留原矫正后时刻。
        List<DeviceObservation> recomputed = new ArrayList<>(before.size());
        for (DeviceObservation row : before) {
            if (!row.deviceId().equals(deviceId)) {
                recomputed.add(row);
                continue;
            }
            Optional<DeviceOffset> covering = offsetRepository.findCovering(deviceId, row.deviceLocalTime());
            Instant corrected = covering.map(offset -> correct(row.deviceLocalTime(), offset))
                    .orElse(row.correctedAtUtc());
            recomputed.add(row.withTiming(corrected, row.mergeSeq()));
        }

        List<DeviceObservation> ranked = rerank(recomputed);
        persistTiming(before, ranked);

        // 仅含该设备版本的观测可能改变胜者：其余观测内部相对顺序不变。
        Map<String, List<DeviceObservation>> newByObservation = groupByObservation(ranked);
        for (Map.Entry<String, List<DeviceObservation>> entry : newByObservation.entrySet()) {
            String observationId = entry.getKey();
            boolean involvesDevice = entry.getValue().stream()
                    .anyMatch(row -> row.deviceId().equals(deviceId));
            if (!involvesDevice) {
                continue;
            }
            List<DeviceObservation> oldRows = oldByObservation.get(observationId);
            int oldWinner = winnerVersion(oldRows, observationId);
            int newWinner = winnerVersion(entry.getValue(), observationId);
            if (oldWinner != newWinner) {
                reorderRepository.insert(new ReorderRecord(
                        requestId + "#" + observationId,
                        requestId,
                        deviceId,
                        effectiveFrom,
                        oldOffsetSeconds,
                        newOffsetSeconds,
                        observationId,
                        oldWinner,
                        newWinner,
                        orderOf(oldRows),
                        orderOf(entry.getValue())));
            }
        }
    }

    /**
     * 全局重排：按合并顺序比较器排序后顺次赋予 1..N 的 mergeSeq，返回排位后的新列表。
     */
    private List<DeviceObservation> rerank(List<DeviceObservation> rows) {
        List<DeviceObservation> sorted = new ArrayList<>(rows);
        sorted.sort(MERGE_ORDER);
        List<DeviceObservation> ranked = new ArrayList<>(sorted.size());
        long seq = 1;
        for (DeviceObservation row : sorted) {
            ranked.add(row.withTiming(row.correctedAtUtc(), seq));
            seq++;
        }
        return ranked;
    }

    /**
     * 将重排后发生变化的矫正后时刻与合并顺序写回；before 为排位前状态（含尚未落库的新行）。
     */
    private void persistTiming(List<DeviceObservation> before, List<DeviceObservation> ranked) {
        Map<String, DeviceObservation> previous = new LinkedHashMap<>();
        for (DeviceObservation row : before) {
            previous.put(row.observationId() + SEPARATOR + row.version(), row);
        }
        for (DeviceObservation row : ranked) {
            DeviceObservation old = previous.get(row.observationId() + SEPARATOR + row.version());
            if (old == null || !old.correctedAtUtc().equals(row.correctedAtUtc())
                    || old.mergeSeq() != row.mergeSeq()) {
                if (old != null) {
                    observationRepository.updateTiming(row.observationId(), row.version(),
                            row.correctedAtUtc(), row.mergeSeq());
                }
            }
        }
    }

    private Map<String, List<DeviceObservation>> groupByObservation(List<DeviceObservation> rows) {
        Map<String, List<DeviceObservation>> grouped = new LinkedHashMap<>();
        for (DeviceObservation row : rows) {
            grouped.computeIfAbsent(row.observationId(), key -> new ArrayList<>()).add(row);
        }
        return grouped;
    }

    /**
     * 当前胜出版本：该观测排位最后的版本。
     */
    private int winnerVersion(List<DeviceObservation> rows, String observationId) {
        return rows.stream()
                .filter(row -> row.observationId().equals(observationId))
                .max(MERGE_ORDER)
                .orElseThrow()
                .version();
    }

    /**
     * 该观测各版本按合并顺序排位的版本号列表。
     */
    private List<Integer> orderOf(List<DeviceObservation> rows) {
        return rows.stream()
                .sorted(MERGE_ORDER)
                .map(DeviceObservation::version)
                .toList();
    }

    // ---------- 解析与幂等 ----------

    private Instant parseInstant(String raw, String field) {
        try {
            return Instant.parse(raw.trim());
        } catch (DateTimeParseException | NullPointerException e) {
            throw ApiException.badRequest(field + " must be an ISO-8601 UTC instant (e.g. 2026-09-25T02:00:00Z)");
        }
    }

    private LocalDateTime parseLocalDateTime(String raw, String field) {
        try {
            return LocalDateTime.parse(raw.trim());
        } catch (DateTimeParseException | NullPointerException e) {
            throw ApiException.badRequest(field + " must be an ISO-8601 local date-time (e.g. 2026-09-25T10:00:00)");
        }
    }

    /**
     * 幂等检查：同键同参返回原成功结果；同键异参返回 409；无记录返回 null 继续执行。
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
        return new OffsetOutcome(entry.responseStatus(), readBody(entry.responseBody(), OffsetResponse.class));
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
        return new SubmitOutcome(entry.responseStatus(),
                readBody(entry.responseBody(), SubmitObservationResponse.class));
    }

    /**
     * 占位写入去重记录；并发同键时主键冲突，等待对方事务结束后读取已提交结果：
     * 同参返回重放结果，异参抛 409；正常占位返回 null。业务失败时占位随事务回滚，不占键。
     */
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
            return new OffsetOutcome(entry.responseStatus(), readBody(entry.responseBody(), OffsetResponse.class));
        }
    }

    private SubmitOutcome insertSubmitPlaceholder(String requestId, String fingerprint) {
        try {
            requestLogRepository.insertPlaceholder(requestId, fingerprint, "SUBMIT");
            return null;
        } catch (DuplicateKeyException e) {
            RequestLogEntry entry = requestLogRepository.find(requestId)
                    .orElseThrow(() -> ApiException.conflict("requestId conflict: " + requestId, null));
            if (!entry.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
            }
            return new SubmitOutcome(entry.responseStatus(),
                    readBody(entry.responseBody(), SubmitObservationResponse.class));
        }
    }

    /**
     * 业务成功后回填去重记录响应，并构造本次写操作结果；与业务变更同事务提交。
     */
    private OffsetOutcome completeOffset(String requestId, HttpStatus status, OffsetResponse body) {
        requestLogRepository.complete(requestId, status.value(), writeBody(body));
        return new OffsetOutcome(status.value(), body);
    }

    private SubmitOutcome completeSubmit(String requestId, HttpStatus status, SubmitObservationResponse body) {
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
     * 偏移写操作结果：HTTP 状态码与响应体。
     */
    public record OffsetOutcome(int status, OffsetResponse body) {
    }

    /**
     * 观测提交结果：HTTP 状态码与响应体。
     */
    public record SubmitOutcome(int status, SubmitObservationResponse body) {
    }
}
