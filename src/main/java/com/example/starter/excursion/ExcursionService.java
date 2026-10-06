package com.example.starter.excursion;

import com.example.starter.observation.ApiException;
import com.example.starter.observation.RequestLogEntry;
import com.example.starter.observation.RequestLogRepository;
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
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 越限观测写服务：创建、离线提交（三方合并）、删除与当前/历史版本查询。
 *
 * <p>写操作均在单事务内完成：先占位写入幂等去重记录，再对 excursion_current 行加锁
 * （SELECT ... FOR UPDATE）完成业务判定与变更，最后回填去重记录的响应并整体提交；
 * 任何业务失败都会回滚，去重记录不占键。
 *
 * <p>可编辑字段为 stationId / minuteUtc / reading，均参与完整版本快照与三方合并：
 * 候选未改（等于基线）保留当前；当前未改接受候选；两边改为相同值也接受；否则整次 409。
 * 读数按数值比较，站号与时刻按原文比较；时刻一律规范化为 UTC 整分钟 ISO-8601 原文。
 */
@Service
public class ExcursionService {

    private static final String SEPARATOR = "";

    private final ExcursionRepository excursionRepository;
    private final RequestLogRepository requestLogRepository;
    private final ObjectMapper objectMapper;

    public ExcursionService(ExcursionRepository excursionRepository,
                            RequestLogRepository requestLogRepository,
                            ObjectMapper objectMapper) {
        this.excursionRepository = excursionRepository;
        this.requestLogRepository = requestLogRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 创建越限观测，初始版本为 1。记录已存在或同站同分钟已被占用返回 409。
     */
    @Transactional
    public WriteOutcome create(CreateExcursionRequest request) {
        String minuteUtc = normalizeMinute(request.minuteUtc());
        String fingerprint = fingerprint("EXC_CREATE", request.observationId(), request.stationId(),
                minuteUtc, normalizeDecimal(request.reading()), request.operator());
        WriteOutcome replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        WriteOutcome concurrent = insertPlaceholder(request.requestId(), fingerprint, "EXC_CREATE");
        if (concurrent != null) {
            return concurrent;
        }

        if (excursionRepository.findCurrentForUpdate(request.observationId()).isPresent()) {
            throw ApiException.conflict("observation already exists: " + request.observationId(), null);
        }
        ExcursionSnapshot snapshot = new ExcursionSnapshot(request.observationId(), 1,
                request.stationId(), minuteUtc, request.reading(), false);
        try {
            excursionRepository.insertCurrent(snapshot);
        } catch (DuplicateKeyException e) {
            // 并发创建同一 observationId，或同站同分钟已被其他记录占用
            throw ApiException.conflict("observation id or (stationId, minuteUtc) already exists: "
                    + request.observationId(), null);
        }
        excursionRepository.insertVersion(snapshot);
        return complete(request.requestId(), HttpStatus.CREATED, ExcursionResponse.of(snapshot));
    }

    /**
     * 离线提交：基于 baseVersion 的三方合并。合并结果与当前完全相同则不加版本，否则版本加一。
     */
    @Transactional
    public WriteOutcome merge(String observationId, MergeExcursionRequest request) {
        String minuteUtc = normalizeMinute(request.minuteUtc());
        String fingerprint = fingerprint("EXC_MERGE", observationId, String.valueOf(request.baseVersion()),
                request.stationId(), minuteUtc, normalizeDecimal(request.reading()), request.operator());
        WriteOutcome replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        WriteOutcome concurrent = insertPlaceholder(request.requestId(), fingerprint, "EXC_MERGE");
        if (concurrent != null) {
            return concurrent;
        }

        ExcursionSnapshot current = excursionRepository.findCurrentForUpdate(observationId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));
        if (current.deleted()) {
            throw ApiException.gone("observation already deleted: " + observationId);
        }
        ExcursionSnapshot base = excursionRepository.findVersion(observationId, request.baseVersion())
                .orElseThrow(() -> ApiException.notFound(
                        "base version not found: " + observationId + "@" + request.baseVersion()));

        List<String> conflictFields = new ArrayList<>();
        String mergedStation = mergeField("stationId", base.stationId(), current.stationId(),
                request.stationId(), conflictFields);
        String mergedMinute = mergeField("minuteUtc", base.minuteUtc(), current.minuteUtc(),
                minuteUtc, conflictFields);
        String mergedReading = mergeReadingField(base.reading(), current.reading(),
                request.reading(), conflictFields);
        if (!conflictFields.isEmpty()) {
            throw ApiException.mergeConflict(conflictFields, current.version());
        }

        ExcursionSnapshot merged = new ExcursionSnapshot(observationId, current.version(),
                mergedStation, mergedMinute, mergedReading, false);
        if (sameContent(merged, current)) {
            // 合并结果与当前完全相同：返回当前版本，不加版本
            return complete(request.requestId(), HttpStatus.OK, ExcursionResponse.of(current));
        }
        ExcursionSnapshot next = new ExcursionSnapshot(observationId, current.version() + 1,
                mergedStation, mergedMinute, mergedReading, false);
        try {
            excursionRepository.updateCurrent(next);
        } catch (DuplicateKeyException e) {
            // 合并后的 (stationId, minuteUtc) 与其他记录冲突
            throw ApiException.conflict("stationId/minuteUtc already occupied by another observation",
                    current.version());
        }
        excursionRepository.insertVersion(next);
        return complete(request.requestId(), HttpStatus.OK, ExcursionResponse.of(next));
    }

    /**
     * 删除越限观测：expectedVersion 匹配当前版本时生成新版本墓碑；已删除记录返回 410。
     */
    @Transactional
    public WriteOutcome delete(String observationId, DeleteExcursionRequest request) {
        String fingerprint = fingerprint("EXC_DELETE", observationId,
                String.valueOf(request.expectedVersion()), request.operator());
        WriteOutcome replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        WriteOutcome concurrent = insertPlaceholder(request.requestId(), fingerprint, "EXC_DELETE");
        if (concurrent != null) {
            return concurrent;
        }

        ExcursionSnapshot current = excursionRepository.findCurrentForUpdate(observationId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));
        if (current.deleted()) {
            throw ApiException.gone("observation already deleted: " + observationId);
        }
        if (request.expectedVersion() != current.version()) {
            throw ApiException.conflict("expectedVersion mismatch", current.version());
        }
        ExcursionSnapshot tombstone = new ExcursionSnapshot(observationId, current.version() + 1,
                null, null, null, true);
        excursionRepository.markDeleted(observationId, tombstone.version());
        excursionRepository.insertVersion(tombstone);
        return complete(request.requestId(), HttpStatus.OK, ExcursionResponse.of(tombstone));
    }

    /**
     * 查询当前内容；墓碑只返回删除状态和版本。
     */
    @Transactional(readOnly = true)
    public ExcursionResponse getCurrent(String observationId) {
        ExcursionSnapshot current = excursionRepository.findCurrent(observationId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));
        return ExcursionResponse.of(current);
    }

    /**
     * 查询指定历史版本；墓碑版本只返回删除状态和版本。
     */
    @Transactional(readOnly = true)
    public ExcursionResponse getVersion(String observationId, int version) {
        ExcursionSnapshot snapshot = excursionRepository.findVersion(observationId, version)
                .orElseThrow(() -> ApiException.notFound(
                        "version not found: " + observationId + "@" + version));
        return ExcursionResponse.of(snapshot);
    }

    /**
     * 校验并规范化 UTC 整分钟时刻：必须是合法 ISO-8601 UTC 时刻且秒/毫秒为零，否则 400。
     * 返回规范化原文（Instant.toString，形如 2026-10-07T10:00:00Z）。
     */
    static String normalizeMinute(String minuteUtc) {
        Instant instant;
        try {
            instant = Instant.parse(minuteUtc);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("minuteUtc must be a valid ISO-8601 UTC timestamp: " + minuteUtc);
        }
        if (instant.getEpochSecond() % 60 != 0 || instant.getNano() != 0) {
            throw ApiException.badRequest("minuteUtc must be a whole UTC minute: " + minuteUtc);
        }
        return instant.toString();
    }

    /**
     * 十进制按值规范化（去尾部零），用于指纹与内容比较：1.0 与 1.00 视为同值。
     */
    static String normalizeDecimal(String decimal) {
        return new BigDecimal(decimal).stripTrailingZeros().toPlainString();
    }

    private String mergeField(String field, String base, String current, String candidate,
                              List<String> conflictFields) {
        if (Objects.equals(candidate, base)) {
            return current;
        }
        if (Objects.equals(current, base) || Objects.equals(candidate, current)) {
            return candidate;
        }
        conflictFields.add(field);
        return current;
    }

    private String mergeReadingField(String base, String current, String candidate,
                                     List<String> conflictFields) {
        if (readingEquals(candidate, base)) {
            return current;
        }
        if (readingEquals(current, base) || readingEquals(candidate, current)) {
            return candidate;
        }
        conflictFields.add("reading");
        return current;
    }

    private boolean sameContent(ExcursionSnapshot merged, ExcursionSnapshot current) {
        return Objects.equals(merged.stationId(), current.stationId())
                && Objects.equals(merged.minuteUtc(), current.minuteUtc())
                && readingEquals(merged.reading(), current.reading());
    }

    private boolean readingEquals(String left, String right) {
        if (left == null || right == null) {
            return Objects.equals(left, right);
        }
        return new BigDecimal(left).compareTo(new BigDecimal(right)) == 0;
    }

    /**
     * 幂等检查：同键同参返回原成功结果；同键异参返回 409；无记录返回 null 继续执行。
     */
    private WriteOutcome checkReplay(String requestId, String fingerprint) {
        Optional<RequestLogEntry> existing = requestLogRepository.find(requestId);
        if (existing.isEmpty()) {
            return null;
        }
        RequestLogEntry entry = existing.get();
        if (!entry.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
        }
        return new WriteOutcome(entry.responseStatus(), readBody(entry.responseBody()));
    }

    /**
     * 占位写入去重记录；并发同键时主键冲突，等待对方事务结束后读取已提交结果：
     * 同参返回重放结果，异参抛 409；正常占位返回 null。业务失败时占位随事务回滚，不占键。
     */
    private WriteOutcome insertPlaceholder(String requestId, String fingerprint, String operation) {
        try {
            requestLogRepository.insertPlaceholder(requestId, fingerprint, operation);
            return null;
        } catch (DuplicateKeyException e) {
            RequestLogEntry entry = requestLogRepository.find(requestId)
                    .orElseThrow(() -> ApiException.conflict("requestId conflict: " + requestId, null));
            if (!entry.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
            }
            return new WriteOutcome(entry.responseStatus(), readBody(entry.responseBody()));
        }
    }

    /**
     * 业务成功后回填去重记录响应，并构造本次写操作结果；与业务变更同事务提交。
     */
    private WriteOutcome complete(String requestId, HttpStatus status, ExcursionResponse body) {
        requestLogRepository.complete(requestId, status.value(), writeBody(body));
        return new WriteOutcome(status.value(), body);
    }

    static String fingerprint(String operation, String... parts) {
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

    private String writeBody(ExcursionResponse body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize response", e);
        }
    }

    private ExcursionResponse readBody(String json) {
        try {
            return objectMapper.readValue(json, ExcursionResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored response", e);
        }
    }

    /**
     * 写操作结果：HTTP 状态码与响应体。
     */
    public record WriteOutcome(int status, ExcursionResponse body) {
    }
}
