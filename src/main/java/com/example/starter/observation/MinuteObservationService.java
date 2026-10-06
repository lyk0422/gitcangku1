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
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 分钟观测业务服务：新类型观测（站号 + UTC 整分钟时刻 + 十进制读数）的创建、三方合并与删除。
 *
 * <p>写操作均在单事务内完成：先占位写入幂等去重记录，再对当前行加锁（SELECT ... FOR UPDATE）
 * 完成业务判定与变更，最后回填去重记录响应并整体提交；业务失败回滚，去重记录不占键。
 *
 * <p>可编辑字段为站号、时刻、读数，均参与完整版本与三方合并：候选未改保留当前；当前未改接受候选；
 * 两边改为相同值也接受；否则整次 409，不写入任何部分结果。读数按数值比较，站号按原文比较，
 * 时刻按 UTC 瞬间比较。同站同分钟由唯一约束保证，冲突（含并发竞争）统一转为 409，不外泄唯一键异常。
 */
@Service
public class MinuteObservationService {

    private static final String SEPARATOR = "";

    private final MinuteObservationRepository minuteObservationRepository;
    private final RequestLogRepository requestLogRepository;
    private final ObjectMapper objectMapper;

    public MinuteObservationService(MinuteObservationRepository minuteObservationRepository,
                                    RequestLogRepository requestLogRepository,
                                    ObjectMapper objectMapper) {
        this.minuteObservationRepository = minuteObservationRepository;
        this.requestLogRepository = requestLogRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 创建分钟观测记录，初始版本为 1。记录标识已存在或同站同分钟已被占用返回 409。
     */
    @Transactional
    public WriteOutcome create(CreateMinuteObservationRequest request) {
        Instant minuteUtc = ValueParsers.parseMinuteUtc(request.minuteUtc());
        String fingerprint = fingerprint("MO_CREATE", request.observationId(), request.operator(),
                request.stationId(), minuteUtc.toString(),
                ValueParsers.normalizeDecimal(request.reading(), "reading"));
        WriteOutcome replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        WriteOutcome concurrent = insertPlaceholder(request.requestId(), fingerprint, "MO_CREATE");
        if (concurrent != null) {
            return concurrent;
        }

        if (minuteObservationRepository.findCurrentForUpdate(request.observationId()).isPresent()) {
            throw ApiException.conflict("minute observation already exists: " + request.observationId(), null);
        }
        MinuteObservationSnapshot snapshot = new MinuteObservationSnapshot(request.observationId(), 1,
                request.stationId(), minuteUtc, request.reading(), false);
        try {
            minuteObservationRepository.insertCurrent(snapshot);
        } catch (DuplicateKeyException e) {
            // 并发创建同一标识或同站同分钟：由主键/唯一键串行化，后到者按冲突处理
            throw ApiException.conflict("minute observation id or station+minute already exists: "
                    + request.observationId() + " " + request.stationId() + "@" + minuteUtc, null);
        }
        minuteObservationRepository.insertVersion(snapshot);
        return complete(request.requestId(), HttpStatus.CREATED, MinuteObservationResponse.of(snapshot));
    }

    /**
     * 离线提交：基于 baseVersion 的三方合并。合并结果与当前完全相同则不加版本，否则版本加一；
     * 合并后同站同分钟与他人冲突返回 409。
     */
    @Transactional
    public WriteOutcome merge(String observationId, MergeMinuteObservationRequest request) {
        Instant candidateMinuteUtc = ValueParsers.parseMinuteUtc(request.minuteUtc());
        String fingerprint = fingerprint("MO_MERGE", observationId, request.operator(),
                String.valueOf(request.baseVersion()), request.stationId(), candidateMinuteUtc.toString(),
                ValueParsers.normalizeDecimal(request.reading(), "reading"));
        WriteOutcome replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        WriteOutcome concurrent = insertPlaceholder(request.requestId(), fingerprint, "MO_MERGE");
        if (concurrent != null) {
            return concurrent;
        }

        MinuteObservationSnapshot current = minuteObservationRepository.findCurrentForUpdate(observationId)
                .orElseThrow(() -> ApiException.notFound("minute observation not found: " + observationId));
        if (current.deleted()) {
            throw ApiException.gone("minute observation already deleted: " + observationId);
        }
        MinuteObservationSnapshot base = minuteObservationRepository
                .findVersion(observationId, request.baseVersion())
                .orElseThrow(() -> ApiException.notFound(
                        "base version not found: " + observationId + "@" + request.baseVersion()));

        List<String> conflictFields = new ArrayList<>();
        String mergedStation = mergeField("stationId", base.stationId(), current.stationId(),
                request.stationId(), conflictFields);
        Instant mergedMinuteUtc = mergeMinuteField(base.minuteUtc(), current.minuteUtc(),
                candidateMinuteUtc, conflictFields);
        String mergedReading = mergeReadingField(base.reading(), current.reading(),
                request.reading(), conflictFields);
        if (!conflictFields.isEmpty()) {
            throw ApiException.mergeConflict(conflictFields, current.version());
        }

        if (sameContent(mergedStation, mergedMinuteUtc, mergedReading, current)) {
            // 合并结果与当前完全相同：返回当前版本，不加版本
            return complete(request.requestId(), HttpStatus.OK, MinuteObservationResponse.of(current));
        }
        MinuteObservationSnapshot next = new MinuteObservationSnapshot(observationId, current.version() + 1,
                mergedStation, mergedMinuteUtc, mergedReading, false);
        try {
            minuteObservationRepository.updateCurrent(next);
        } catch (DuplicateKeyException e) {
            // 合并后的站号+时刻与他人记录冲突（含并发竞争）：整次 409，不写部分结果
            throw ApiException.conflict("station+minute already occupied by another observation: "
                    + mergedStation + "@" + mergedMinuteUtc, current.version());
        }
        minuteObservationRepository.insertVersion(next);
        return complete(request.requestId(), HttpStatus.OK, MinuteObservationResponse.of(next));
    }

    /**
     * 删除分钟观测记录：expectedVersion 匹配当前版本时生成新版本墓碑并释放同站同分钟槽位；
     * 已删除记录返回 410。
     */
    @Transactional
    public WriteOutcome delete(String observationId, DeleteMinuteObservationRequest request) {
        String fingerprint = fingerprint("MO_DELETE", observationId, request.operator(),
                String.valueOf(request.expectedVersion()));
        WriteOutcome replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        WriteOutcome concurrent = insertPlaceholder(request.requestId(), fingerprint, "MO_DELETE");
        if (concurrent != null) {
            return concurrent;
        }

        MinuteObservationSnapshot current = minuteObservationRepository.findCurrentForUpdate(observationId)
                .orElseThrow(() -> ApiException.notFound("minute observation not found: " + observationId));
        if (current.deleted()) {
            throw ApiException.gone("minute observation already deleted: " + observationId);
        }
        if (request.expectedVersion() != current.version()) {
            throw ApiException.conflict("expectedVersion mismatch", current.version());
        }
        MinuteObservationSnapshot tombstone = new MinuteObservationSnapshot(observationId,
                current.version() + 1, null, null, null, true);
        minuteObservationRepository.markDeleted(observationId, tombstone.version());
        minuteObservationRepository.insertVersion(tombstone);
        return complete(request.requestId(), HttpStatus.OK, MinuteObservationResponse.of(tombstone));
    }

    /**
     * 查询当前内容；墓碑只返回删除状态和版本。
     */
    @Transactional(readOnly = true)
    public MinuteObservationResponse getCurrent(String observationId) {
        MinuteObservationSnapshot current = minuteObservationRepository.findCurrent(observationId)
                .orElseThrow(() -> ApiException.notFound("minute observation not found: " + observationId));
        return MinuteObservationResponse.of(current);
    }

    /**
     * 查询指定历史版本；墓碑版本只返回删除状态和版本。
     */
    @Transactional(readOnly = true)
    public MinuteObservationResponse getVersion(String observationId, int version) {
        MinuteObservationSnapshot snapshot = minuteObservationRepository.findVersion(observationId, version)
                .orElseThrow(() -> ApiException.notFound(
                        "version not found: " + observationId + "@" + version));
        return MinuteObservationResponse.of(snapshot);
    }

    /**
     * 普通字段三方合并：候选未改保留当前；当前未改接受候选；两边改为相同值接受；否则记录冲突。
     */
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

    /**
     * 时刻字段三方合并：按 UTC 瞬间比较，规则与普通字段一致。
     */
    private Instant mergeMinuteField(Instant base, Instant current, Instant candidate,
                                     List<String> conflictFields) {
        if (Objects.equals(candidate, base)) {
            return current;
        }
        if (Objects.equals(current, base) || Objects.equals(candidate, current)) {
            return candidate;
        }
        conflictFields.add("minuteUtc");
        return current;
    }

    /**
     * 读数字段三方合并：按数值比较（BigDecimal），其余规则与普通字段一致。
     */
    private String mergeReadingField(String base, String current, String candidate,
                                     List<String> conflictFields) {
        if (ValueParsers.decimalEquals(candidate, base)) {
            return current;
        }
        if (ValueParsers.decimalEquals(current, base) || ValueParsers.decimalEquals(candidate, current)) {
            return candidate;
        }
        conflictFields.add("reading");
        return current;
    }

    /**
     * 判断合并结果与当前版本内容是否完全相同（读数按数值，时刻按瞬间，站号按原文）。
     */
    private boolean sameContent(String stationId, Instant minuteUtc, String reading,
                                MinuteObservationSnapshot current) {
        return Objects.equals(stationId, current.stationId())
                && Objects.equals(minuteUtc, current.minuteUtc())
                && ValueParsers.decimalEquals(reading, current.reading());
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
    private WriteOutcome complete(String requestId, HttpStatus status, MinuteObservationResponse body) {
        requestLogRepository.complete(requestId, status.value(), writeBody(body));
        return new WriteOutcome(status.value(), body);
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

    private String writeBody(MinuteObservationResponse body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize response", e);
        }
    }

    private MinuteObservationResponse readBody(String json) {
        try {
            return objectMapper.readValue(json, MinuteObservationResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored response", e);
        }
    }

    /**
     * 写操作结果：HTTP 状态码与响应体。
     */
    public record WriteOutcome(int status, MinuteObservationResponse body) {
    }
}
