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
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 现场观测更正附录业务服务。
 *
 * <p>已解决（存在冲突裁决记录）的观测簇才能创建附录；附录只追加、不可变，
 * 历史观测快照与当前行始终只读，字段差异只能以新附录版本表达。
 *
 * <p>附录版本独立编号：首份附录版本 1、前一附录版本 0（引用原始快照）；
 * 每份附录的字段旧值必须与前一附录版本的有效值一致（读数按数值比较），
 * 以防止借更正绕过原有冲突裁决。expectedPreviousVersion 承担乐观并发控制，
 * 两个争用同一前置版本的更正只有一个成功，另一个收到 409；任何失败均回滚，不占版本号。
 *
 * <p>requestId 复用既有 request_log 幂等机制（操作名 CORRIGENDUM）：
 * 同键同参重放首次结果，同键换字段（异参）返回 409。
 */
@Service
public class CorrigendumService {

    private static final String SEPARATOR = "";
    private static final String READING_PATTERN = "-?\\d+(\\.\\d{1,3})?";

    /**
     * 三个可编辑字段的固定顺序：差异规范化、校验与有效值叠加均以此顺序处理，保证结果稳定。
     */
    private static final List<String> FIELDS = List.of("location", "reading", "note");

    private final ObservationRepository observationRepository;
    private final ResolutionRepository resolutionRepository;
    private final CorrigendumRepository corrigendumRepository;
    private final RequestLogRepository requestLogRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public CorrigendumService(ObservationRepository observationRepository,
                              ResolutionRepository resolutionRepository,
                              CorrigendumRepository corrigendumRepository,
                              RequestLogRepository requestLogRepository,
                              ObjectMapper objectMapper,
                              Clock clock) {
        this.observationRepository = observationRepository;
        this.resolutionRepository = resolutionRepository;
        this.corrigendumRepository = corrigendumRepository;
        this.requestLogRepository = requestLogRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 创建更正附录。
     *
     * <p>事务内先占位 request_log，再对 observation_current 加行锁串行化同簇并发：
     * 未解决簇 422；前置版本不匹配 409；旧值与前置版本有效值不一致 422（拒绝绕过裁决）；
     * 删除字段、非法读数等 400。任何失败整体回滚，附录版本号与 requestId 均不占用。
     */
    @Transactional
    public CorrigendumOutcome submitCorrigendum(String observationId, CreateCorrigendumRequest request) {
        List<FieldChange> changes = normalizeChanges(request.changes());
        String fingerprint = fingerprint("CORRIGENDUM", observationId,
                String.valueOf(request.expectedPreviousVersion()), writeCanonicalChanges(changes), request.operator());

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
        // 只有经冲突裁决解决的簇才能以附录表达字段差异；未解决簇一律 422。
        List<ResolutionRecord> resolutions = resolutionRepository.findByObservationId(observationId);
        if (resolutions.isEmpty()) {
            throw ApiException.unprocessableEntity(
                    "observation cluster is not resolved: " + observationId
                            + "; corrigenda are only accepted after conflict resolution");
        }

        List<CorrigendumRecord> chain = corrigendumRepository.findByObservationId(observationId);
        int latestVersion = chain.size();
        if (request.expectedPreviousVersion() != latestVersion) {
            throw ApiException.conflict(
                    "expectedPreviousVersion mismatch: expected " + latestVersion
                            + " but request based on " + request.expectedPreviousVersion(),
                    latestVersion);
        }

        // 附录链自首份附录起锚定同一只读快照版本；后续观测版本如何变化都不影响历史附录的重算。
        int snapshotVersion = chain.isEmpty() ? current.version() : chain.get(0).snapshotVersion();
        ObservationSnapshot baseSnapshot = observationRepository.findVersion(observationId, snapshotVersion)
                .orElseThrow(() -> new IllegalStateException(
                        "anchored snapshot missing: " + observationId + "@" + snapshotVersion));
        EffectiveValues previousValues = effectiveAt(EffectiveValues.of(baseSnapshot), chain, latestVersion);

        // 旧值必须等于前置附录版本的当时有效值：读数按数值、其余按原文。不一致即试图覆盖/绕过裁决，422。
        for (FieldChange change : changes) {
            String actual = previousValues.get(change.field());
            if (!valueEquals(change.field(), change.oldValue(), actual)) {
                throw ApiException.unprocessableEntity(
                        "old value of field '" + change.field() + "' does not match the effective value "
                                + "at corrigendum version " + latestVersion + ": expected '" + actual
                                + "' but request gave '" + change.oldValue()
                                + "'; overriding the conflict resolution is not allowed");
            }
        }
        EffectiveValues newValues = applyChanges(previousValues, changes);

        int newVersion = latestVersion + 1;
        CorrigendumRecord record = new CorrigendumRecord(
                UUID.randomUUID().toString(), observationId, request.requestId(),
                newVersion, latestVersion, snapshotVersion, List.copyOf(changes),
                request.operator(), Instant.now(clock));
        try {
            corrigendumRepository.insert(record);
        } catch (DuplicateKeyException e) {
            // 理论上行锁已串行化；唯一约束作为并发与 requestId 复用的最后防线，一律按版本冲突拒绝。
            throw ApiException.conflict("corrigendum version or requestId conflict: " + request.requestId(),
                    latestVersion);
        }

        CorrigendumResponse body = CorrigendumResponse.of(record, newValues);
        return complete(request.requestId(), HttpStatus.CREATED, body);
    }

    /**
     * 读取指定附录版本视图：版本 0 为只读原始快照；版本 n 为叠加前 n 次附录后的当时有效值。
     * 观测记录不存在返回 404；附录版本不存在返回 404。旧版本不随后续附录漂移。
     */
    @Transactional(readOnly = true)
    public CorrigendumVersionView getVersionView(String observationId, int corrigendumVersion) {
        ObservationSnapshot current = observationRepository.findCurrent(observationId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));
        List<CorrigendumRecord> chain = corrigendumRepository.findByObservationId(observationId);
        int snapshotVersion = chain.isEmpty() ? current.version() : chain.get(0).snapshotVersion();

        if (corrigendumVersion == 0) {
            ObservationSnapshot baseSnapshot = observationRepository.findVersion(observationId, snapshotVersion)
                    .orElseThrow(() -> ApiException.notFound(
                            "anchored snapshot not found: " + observationId + "@" + snapshotVersion));
            Instant snapshotTime = observationRepository.findVersionCreatedAt(observationId, snapshotVersion)
                    .orElseThrow(() -> new IllegalStateException(
                            "anchored snapshot timestamp missing: " + observationId + "@" + snapshotVersion));
            EffectiveValues values = EffectiveValues.of(baseSnapshot);
            return new CorrigendumVersionView(0, snapshotVersion, null, snapshotTime,
                    List.of(), values.location(), values.reading(), values.note());
        }

        CorrigendumRecord record = chain.stream()
                .filter(candidate -> candidate.corrigendumVersion() == corrigendumVersion)
                .findFirst()
                .orElseThrow(() -> ApiException.notFound(
                        "corrigendum version not found: " + observationId + "#" + corrigendumVersion));
        ObservationSnapshot baseSnapshot = observationRepository.findVersion(observationId, snapshotVersion)
                .orElseThrow(() -> new IllegalStateException(
                        "anchored snapshot missing: " + observationId + "@" + snapshotVersion));
        EffectiveValues values = effectiveAt(EffectiveValues.of(baseSnapshot), chain, corrigendumVersion);
        return new CorrigendumVersionView(record.corrigendumVersion(), record.snapshotVersion(),
                record.operator(), record.submittedAtUtc(), List.copyOf(record.changes()),
                values.location(), values.reading(), values.note());
    }

    /**
     * 查询附录版本链：以只读原始快照（版本 0）为起点，串联每次更正的操作者、时刻与字段差异。
     * 观测记录不存在返回 404；resolvedCluster 表示该簇是否已经冲突裁决解决。
     */
    @Transactional(readOnly = true)
    public CorrigendumHistoryResponse getHistory(String observationId) {
        ObservationSnapshot current = observationRepository.findCurrent(observationId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));
        boolean resolved = !resolutionRepository.findByObservationId(observationId).isEmpty();
        List<CorrigendumRecord> chain = corrigendumRepository.findByObservationId(observationId);
        int snapshotVersion = chain.isEmpty() ? current.version() : chain.get(0).snapshotVersion();

        List<CorrigendumVersionView> versions = new ArrayList<>();
        ObservationSnapshot baseSnapshot;
        Instant snapshotTime;
        if (chain.isEmpty()) {
            baseSnapshot = current;
            snapshotTime = observationRepository.findVersionCreatedAt(observationId, snapshotVersion)
                    .orElseThrow(() -> new IllegalStateException(
                            "snapshot timestamp missing: " + observationId + "@" + snapshotVersion));
        } else {
            baseSnapshot = observationRepository.findVersion(observationId, snapshotVersion)
                    .orElseThrow(() -> new IllegalStateException(
                            "anchored snapshot missing: " + observationId + "@" + snapshotVersion));
            snapshotTime = observationRepository.findVersionCreatedAt(observationId, snapshotVersion)
                    .orElseThrow(() -> new IllegalStateException(
                            "anchored snapshot timestamp missing: " + observationId + "@" + snapshotVersion));
        }
        EffectiveValues values = EffectiveValues.of(baseSnapshot);
        versions.add(new CorrigendumVersionView(0, snapshotVersion, null, snapshotTime,
                List.of(), values.location(), values.reading(), values.note()));
        for (CorrigendumRecord record : chain) {
            values = applyChanges(values, record.changes());
            versions.add(new CorrigendumVersionView(record.corrigendumVersion(), record.snapshotVersion(),
                    record.operator(), record.submittedAtUtc(), List.copyOf(record.changes()),
                    values.location(), values.reading(), values.note()));
        }
        return new CorrigendumHistoryResponse(observationId, resolved, List.copyOf(versions));
    }

    // ---------- 差异规范化与有效值叠加（包内可见，便于单元测试直接覆盖） ----------

    /**
     * 规范化字段差异：按固定字段顺序输出；拒绝重复字段、空白新旧值与非法读数（读数最多三位小数）。
     * 新值为空等价于删除既有字段，一律 400 拒绝。
     */
    static List<FieldChange> normalizeChanges(List<FieldChangeRequest> rawChanges) {
        Map<String, FieldChange> byField = new LinkedHashMap<>();
        for (FieldChangeRequest raw : rawChanges) {
            String field = raw.field() == null ? null : raw.field().trim();
            if (field == null || !FIELDS.contains(field)) {
                throw ApiException.badRequest("change field must be one of location/reading/note: " + field);
            }
            String oldValue = raw.oldValue();
            String newValue = raw.newValue();
            if (oldValue == null || oldValue.isBlank()) {
                throw ApiException.badRequest("old value of field '" + field + "' must not be blank");
            }
            if (newValue == null || newValue.isBlank()) {
                throw ApiException.badRequest(
                        "new value of field '" + field + "' must not be blank; deleting an existing field is not allowed");
            }
            if ("reading".equals(field)) {
                if (!oldValue.matches(READING_PATTERN)) {
                    throw ApiException.badRequest(
                            "old reading must be a decimal string with at most 3 fraction digits: " + oldValue);
                }
                if (!newValue.matches(READING_PATTERN)) {
                    throw ApiException.badRequest(
                            "new reading must be a decimal string with at most 3 fraction digits: " + newValue);
                }
            }
            if (byField.put(field, new FieldChange(field, oldValue, newValue)) != null) {
                throw ApiException.badRequest("duplicate change for field: " + field);
            }
        }
        List<FieldChange> ordered = new ArrayList<>();
        for (String field : FIELDS) {
            FieldChange change = byField.get(field);
            if (change != null) {
                ordered.add(change);
            }
        }
        return List.copyOf(ordered);
    }

    /**
     * 将一批字段差异叠加到当前有效值上，返回新的有效值；未出现的字段保持不变。
     */
    static EffectiveValues applyChanges(EffectiveValues values, List<FieldChange> changes) {
        String location = values.location();
        String reading = values.reading();
        String note = values.note();
        for (FieldChange change : changes) {
            switch (change.field()) {
                case "location" -> location = change.newValue();
                case "reading" -> reading = change.newValue();
                case "note" -> note = change.newValue();
                default -> throw ApiException.badRequest("unknown change field: " + change.field());
            }
        }
        return new EffectiveValues(location, reading, note);
    }

    /**
     * 由原始快照有效值出发，依次叠加附录链中前 {@code upToVersion} 份附录，得到该版本的当时有效值。
     */
    static EffectiveValues effectiveAt(EffectiveValues base, List<CorrigendumRecord> chain, int upToVersion) {
        EffectiveValues values = base;
        for (CorrigendumRecord record : chain) {
            if (record.corrigendumVersion() > upToVersion) {
                break;
            }
            values = applyChanges(values, record.changes());
        }
        return values;
    }

    /**
     * 字段值比较：读数按 BigDecimal 数值比较（10 与 10.0 视为相等），其余字段按原文比较。
     */
    static boolean valueEquals(String field, String left, String right) {
        if ("reading".equals(field)) {
            return new BigDecimal(left).compareTo(new BigDecimal(right)) == 0;
        }
        return java.util.Objects.equals(left, right);
    }

    // ---------- 幂等 ----------

    /**
     * 附录请求的 requestId 幂等检查：同键同参返回原成功结果；同键异参（含换字段）返回 409。
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
     * 占位写入 request_log；并发同键时主键冲突后读已提交结果：同参重放、异参 409。
     * 业务失败时占位随事务回滚，不占 requestId 与附录版本号。
     */
    private CorrigendumOutcome insertPlaceholder(String requestId, String fingerprint) {
        try {
            requestLogRepository.insertPlaceholder(requestId, fingerprint, "CORRIGENDUM");
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
     * 附录成功后回填 request_log 响应，与不可变附录在同一事务提交。
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

    private String writeCanonicalChanges(List<FieldChange> changes) {
        try {
            return objectMapper.writeValueAsString(changes);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize corrigendum changes", e);
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
     * 写附录结果：HTTP 状态码与响应体。
     */
    public record CorrigendumOutcome(int status, CorrigendumResponse body) {
    }
}
