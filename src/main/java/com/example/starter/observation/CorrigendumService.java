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
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 观测更正附录业务服务。
 *
 * <p>已解决观测簇在冲突裁决之后若需修正字段，只能追加不可变附录版本（V1..Vn）。
 * 更正链 V0 为冲突裁决生效后指向的只读历史快照（resolution.newVersion 对应版本），
 * observation_version 中的全部历史版本与历史附录均不被修改或覆盖。
 *
 * <p>创建附录在单事务内完成：先在 request_log 占位做 requestId 幂等，再对 observation_current
 * 行加锁（SELECT ... FOR UPDATE）串行化同一观测的并发更正，随后校验：引用的 resolutionId 是
 * 该观测最新裁决、裁决后观测版本未漂移、expectedAppendixVersion 等于当前附录版本、字段合法、
 * 新值非空且确实与旧值不同。成功后原子写入附录并回填幂等响应；任何业务失败整体回滚，
 * 不占用附录版本号与 requestId。
 */
@Service
public class CorrigendumService {

    /**
     * 三个可更正字段的固定集合；读数按数值比较，其余字段按原文比较。
     */
    private static final Set<String> FIELDS = Set.of("location", "reading", "note");
    private static final String READING_PATTERN = "-?\\d+(\\.\\d{1,3})?";
    private static final String SEPARATOR = "";

    private final CorrigendumRepository corrigendumRepository;
    private final ObservationRepository observationRepository;
    private final ResolutionRepository resolutionRepository;
    private final RequestLogRepository requestLogRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public CorrigendumService(CorrigendumRepository corrigendumRepository,
                              ObservationRepository observationRepository,
                              ResolutionRepository resolutionRepository,
                              RequestLogRepository requestLogRepository,
                              ObjectMapper objectMapper,
                              Clock clock) {
        this.corrigendumRepository = corrigendumRepository;
        this.observationRepository = observationRepository;
        this.resolutionRepository = resolutionRepository;
        this.requestLogRepository = requestLogRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 创建更正附录。
     */
    @Transactional
    public CorrigendumOutcome create(String observationId, CreateAppendixRequest request) {
        String fingerprint = fingerprint("APPENDIX", observationId, request.appendixId(),
                request.resolutionId(), String.valueOf(request.expectedAppendixVersion()),
                request.fieldName(), request.newValue(), request.operator());
        CorrigendumOutcome replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        CorrigendumOutcome concurrent = insertPlaceholder(request.requestId(), fingerprint);
        if (concurrent != null) {
            return concurrent;
        }

        ObservationSnapshot current = observationRepository.findCurrentForUpdate(observationId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));
        if (current.deleted()) {
            throw ApiException.gone("observation already deleted: " + observationId);
        }

        // appendixId 已存在（新 requestId 复用）：同参重放原结果，改参一律 409；先于裁决与版本校验。
        CorrigendumAppendix existingById = corrigendumRepository.findByAppendixId(request.appendixId())
                .orElse(null);
        if (existingById != null) {
            List<CorrigendumAppendix> existingChain =
                    corrigendumRepository.findByObservationId(existingById.observationId());
            if (Objects.equals(existingById.observationId(), observationId)
                    && sameAppendixParams(existingById, request)) {
                return complete(request.requestId(), HttpStatus.OK,
                        responseOf(existingById, existingChain));
            }
            throw ApiException.conflict(
                    "appendixId reused with different parameters: " + request.appendixId(),
                    corrigendumRepository.countByObservationId(observationId));
        }

        // 仅最新冲突裁决可作为附录依据：无裁决（未解决簇 422）、引用旧裁决或裁决后版本漂移均拒绝。
        ResolutionRecord latestResolution = resolutionRepository.findLatestByObservationId(observationId)
                .orElseThrow(() -> ApiException.unprocessable(
                        "observation cluster has not been resolved: " + observationId));
        if (!Objects.equals(latestResolution.resolutionId(), request.resolutionId())) {
            throw ApiException.unprocessable(
                    "resolution is not the latest adjudication of this observation: " + request.resolutionId());
        }
        if (latestResolution.newVersion() != current.version()) {
            throw ApiException.unprocessable(
                    "observation changed after resolution; corrigendum can only express differences "
                            + "on the resolved cluster: " + observationId);
        }

        List<CorrigendumAppendix> chain = corrigendumRepository.findByObservationId(observationId);
        int latestAppendixVersion = chain.size();
        if (request.expectedAppendixVersion() != latestAppendixVersion) {
            // 两个更正争用同一前置版本：先提交者推进版本，后到者在此收到版本冲突。
            throw ApiException.conflict("expectedAppendixVersion mismatch", latestAppendixVersion);
        }

        String fieldName = request.fieldName().trim();
        if (!FIELDS.contains(fieldName)) {
            throw ApiException.badRequest(
                    "fieldName must be one of location/reading/note: " + request.fieldName());
        }
        String newValue = request.newValue();
        if (newValue.isBlank()) {
            // 不允许以空值删除既有字段。
            throw ApiException.badRequest("newValue must not be blank; deleting a field is not allowed");
        }
        if ("reading".equals(fieldName) && !newValue.matches(READING_PATTERN)) {
            throw ApiException.badRequest(
                    "newValue for reading must be a decimal string with at most 3 fraction digits");
        }

        // 更正链 V0 基线为最新裁决生效后指向的快照（复核员裁决结果即更正起点）；
        // 该历史快照只读，附录不修改也不覆盖它。
        ObservationSnapshot snapshot = observationRepository.findVersion(
                        observationId, latestResolution.newVersion())
                .orElseThrow(() -> new IllegalStateException(
                        "resolved snapshot missing for observation: " + observationId));
        String oldValue = fieldValue(effectiveAt(snapshot, chain, latestAppendixVersion), fieldName);
        if (valueEquals(fieldName, oldValue, newValue)) {
            // 新旧相同不构成字段差异：拒绝，且不占附录版本号。
            throw ApiException.badRequest(
                    "newValue must differ from the current effective value of field: " + fieldName);
        }

        int newAppendixVersion = latestAppendixVersion + 1;
        CorrigendumAppendix appendix = new CorrigendumAppendix(
                request.appendixId(), observationId, request.requestId(), newAppendixVersion,
                latestAppendixVersion == 0 ? null : latestAppendixVersion,
                request.resolutionId(), snapshot.version(), fieldName, oldValue, newValue,
                request.operator(), Instant.now(clock));
        try {
            corrigendumRepository.insert(appendix);
        } catch (DuplicateKeyException e) {
            // 并发争用：不同请求争同一前置版本时 (observation_id, appendix_version) 冲突，
            // 或 appendixId/(observation_id,request_id) 被占用；失败方不占版本号。
            CorrigendumAppendix winner = corrigendumRepository.findByAppendixId(request.appendixId())
                    .orElse(null);
            if (winner != null && Objects.equals(winner.observationId(), observationId)
                    && sameAppendixParams(winner, request)) {
                List<CorrigendumAppendix> winnerChain =
                        corrigendumRepository.findByObservationId(observationId);
                return complete(request.requestId(), HttpStatus.OK, responseOf(winner, winnerChain));
            }
            throw ApiException.conflict("appendix version conflict; another corrigendum was committed first",
                    corrigendumRepository.countByObservationId(observationId));
        }

        List<CorrigendumAppendix> committedChain = appendChain(chain, appendix);
        return complete(request.requestId(), HttpStatus.CREATED,
                CorrigendumResponse.of(appendix, effectiveAt(snapshot, committedChain, newAppendixVersion)));
    }

    /**
     * 按版本串联原始快照与截至该版本（含）的每次更正。
     */
    @Transactional(readOnly = true)
    public VersionedObservationResponse getVersioned(String observationId, int version) {
        if (version < 0) {
            throw ApiException.badRequest("version must be >= 0");
        }
        observationRepository.findCurrent(observationId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));
        List<CorrigendumAppendix> chain = corrigendumRepository.findByObservationId(observationId);
        if (version > chain.size()) {
            throw ApiException.notFound(
                    "appendix version not found: " + observationId + "@V" + version);
        }
        return buildVersionedResponse(observationId, version, chain);
    }

    /**
     * 查询当前（最新附录版本）视角的原始值与有效值。
     */
    @Transactional(readOnly = true)
    public VersionedObservationResponse getCurrentVersioned(String observationId) {
        observationRepository.findCurrent(observationId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));
        List<CorrigendumAppendix> chain = corrigendumRepository.findByObservationId(observationId);
        return buildVersionedResponse(observationId, chain.size(), chain);
    }

    /**
     * 列出观测记录全部附录差异，按版本先后排序。
     */
    @Transactional(readOnly = true)
    public List<AppendixEntry> listAppendices(String observationId) {
        observationRepository.findCurrent(observationId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));
        return corrigendumRepository.findByObservationId(observationId).stream()
                .map(AppendixEntry::of)
                .toList();
    }

    /**
     * 依据原始快照与附录链计算指定附录版本视角下的字段有效值；version=0 恒为原始值，旧版本不随新附录漂移。
     */
    static EffectiveValues effectiveAt(ObservationSnapshot snapshot, List<CorrigendumAppendix> chain, int version) {
        if (version < 0 || version > chain.size()) {
            throw ApiException.notFound("appendix version out of range: V" + version);
        }
        String location = snapshot.location();
        String reading = snapshot.reading();
        String note = snapshot.note();
        for (int i = 0; i < version; i++) {
            CorrigendumAppendix appendix = chain.get(i);
            switch (appendix.fieldName()) {
                case "location" -> location = appendix.newValue();
                case "reading" -> reading = appendix.newValue();
                case "note" -> note = appendix.newValue();
                default -> throw new IllegalStateException(
                        "unknown appendix field: " + appendix.fieldName());
            }
        }
        return new EffectiveValues(location, reading, note);
    }

    // ---------- 内部辅助 ----------

    private VersionedObservationResponse buildVersionedResponse(String observationId, int version,
                                                                List<CorrigendumAppendix> chain) {
        // 更正链 V0 基线为建链时裁决指向的快照；尚无附录时取最新裁决指向版本（无裁决则回退到原始 version=1）。
        int snapshotVersion = chain.isEmpty()
                ? resolutionRepository.findLatestByObservationId(observationId)
                        .map(ResolutionRecord::newVersion).orElse(1)
                : chain.get(0).snapshotVersion();
        ObservationSnapshot snapshot = observationRepository.findVersion(observationId, snapshotVersion)
                .orElseThrow(() -> new IllegalStateException(
                        "resolved snapshot missing for observation: " + observationId));
        String resolutionId = chain.isEmpty()
                ? resolutionRepository.findLatestByObservationId(observationId)
                        .map(ResolutionRecord::resolutionId).orElse(null)
                : chain.get(0).resolutionId();
        EffectiveValues effective = effectiveAt(snapshot, chain, version);
        List<AppendixEntry> entries = chain.subList(0, version).stream()
                .map(AppendixEntry::of)
                .toList();
        String operator = null;
        Instant correctedAt = null;
        if (version > 0) {
            CorrigendumAppendix pointed = chain.get(version - 1);
            operator = pointed.operator();
            correctedAt = pointed.correctedAtUtc();
        }
        return new VersionedObservationResponse(
                observationId, version, resolutionId, snapshot.version(), operator, correctedAt,
                snapshot.location(), snapshot.reading(), snapshot.note(),
                effective.location(), effective.reading(), effective.note(), entries);
    }

    private CorrigendumResponse responseOf(CorrigendumAppendix appendix,
                                           List<CorrigendumAppendix> chain) {
        ObservationSnapshot snapshot = observationRepository.findVersion(
                        appendix.observationId(), appendix.snapshotVersion())
                .orElseThrow(() -> new IllegalStateException(
                        "resolved snapshot missing for observation: " + appendix.observationId()));
        return CorrigendumResponse.of(appendix, effectiveAt(snapshot, chain, appendix.appendixVersion()));
    }

    private static String fieldValue(EffectiveValues values, String fieldName) {
        return switch (fieldName) {
            case "location" -> values.location();
            case "reading" -> values.reading();
            case "note" -> values.note();
            default -> throw ApiException.badRequest("unknown field: " + fieldName);
        };
    }

    private static boolean valueEquals(String fieldName, String left, String right) {
        if ("reading".equals(fieldName) && left != null && right != null) {
            return new BigDecimal(left).compareTo(new BigDecimal(right)) == 0;
        }
        return Objects.equals(left, right);
    }

    /**
     * 判断已落库附录与本次请求是否同参（用于 appendixId 同参重放）；字段名按 trim 后比较。
     */
    private boolean sameAppendixParams(CorrigendumAppendix record, CreateAppendixRequest request) {
        return Objects.equals(record.resolutionId(), request.resolutionId())
                && Objects.equals(record.fieldName(), request.fieldName().trim())
                && Objects.equals(record.newValue(), request.newValue())
                && Objects.equals(record.operator(), request.operator());
    }

    private static List<CorrigendumAppendix> appendChain(List<CorrigendumAppendix> chain,
                                                         CorrigendumAppendix appendix) {
        return java.util.stream.Stream.concat(chain.stream(), java.util.stream.Stream.of(appendix)).toList();
    }

    /**
     * 幂等检查：同键同参返回原成功结果；同键异参返回 409；无记录返回 null 继续执行。
     */
    private CorrigendumOutcome checkReplay(String requestId, String fingerprint) {
        Optional<RequestLogEntry> existing = requestLogRepository.find(requestId);
        if (existing.isEmpty()) {
            return null;
        }
        RequestLogEntry entry = existing.get();
        if (!entry.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
        }
        return new CorrigendumOutcome(entry.responseStatus(), readBody(entry.responseBody()));
    }

    /**
     * 占位写入去重记录；并发同键时主键冲突，等待对方事务结束后读取已提交结果：
     * 同参返回重放结果，异参抛 409；正常占位返回 null。业务失败时占位随事务回滚，不占键。
     */
    private CorrigendumOutcome insertPlaceholder(String requestId, String fingerprint) {
        try {
            requestLogRepository.insertPlaceholder(requestId, fingerprint, "APPENDIX");
            return null;
        } catch (DuplicateKeyException e) {
            RequestLogEntry entry = requestLogRepository.find(requestId)
                    .orElseThrow(() -> ApiException.conflict("requestId conflict: " + requestId, null));
            if (!entry.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
            }
            return new CorrigendumOutcome(entry.responseStatus(), readBody(entry.responseBody()));
        }
    }

    /**
     * 业务成功后回填去重记录响应，并构造本次附录创建结果；与附录同事务提交。
     */
    private CorrigendumOutcome complete(String requestId, HttpStatus status, CorrigendumResponse body) {
        try {
            requestLogRepository.complete(requestId, status.value(), objectMapper.writeValueAsString(body));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize corrigendum response", e);
        }
        return new CorrigendumOutcome(status.value(), body);
    }

    private CorrigendumResponse readBody(String json) {
        try {
            return objectMapper.readValue(json, CorrigendumResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored corrigendum response", e);
        }
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

    /**
     * 附录创建结果：HTTP 状态码与响应体。
     */
    public record CorrigendumOutcome(int status, CorrigendumResponse body) {
    }
}
