package com.example.starter.observation.geo;

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
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * 二维坐标观测业务服务：坐标 (x, y) 与协方差矩阵 (a, b, c) 作为一个完整字段组参与三方合并。
 *
 * <p>写操作（创建/离线提交/删除/一致性确认）均在单事务内完成：先占位写入幂等去重记录，
 * 再对相关 geo_observation_current 行加锁（SELECT ... FOR UPDATE）完成业务判定与变更，
 * 最后回填去重记录的响应并整体提交；任何业务失败都会回滚，去重记录不占键。
 *
 * <p>三方合并规则：坐标与矩阵整组比较（十进制按数值），候选未改保留当前；当前未改接受候选；
 * 两边改为相同值也接受；双方不同改动整次 409（冲突字段 geo），不逐字段拼装、不写部分结果。
 *
 * <p>一致性确认：在同一事务内按标识字典序原子锁定两条观测的完整当前版本，核对期望版本后
 * 用坐标差 u、v 与两矩阵之和 A、B、C 做精确乘积比较（不开根、不舍入）；
 * 成功时冻结两侧快照与比较值写入不可变确认记录，不改动源观测。
 */
@Service
public class GeoObservationService {

    private static final String SEPARATOR = Character.toString((char) 1);
    private static final BigDecimal TWO = BigDecimal.valueOf(2);
    private static final BigDecimal NINE = BigDecimal.valueOf(9);

    private final GeoObservationRepository geoObservationRepository;
    private final ConsistencyConfirmationRepository confirmationRepository;
    private final RequestLogRepository requestLogRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public GeoObservationService(GeoObservationRepository geoObservationRepository,
                                 ConsistencyConfirmationRepository confirmationRepository,
                                 RequestLogRepository requestLogRepository,
                                 ObjectMapper objectMapper,
                                 Clock clock) {
        this.geoObservationRepository = geoObservationRepository;
        this.confirmationRepository = confirmationRepository;
        this.requestLogRepository = requestLogRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 创建二维坐标观测记录，初始版本为 1。矩阵非法返回 400，记录已存在返回 409。
     */
    @Transactional
    public GeoWriteOutcome create(CreateGeoObservationRequest request) {
        validateMatrix(request.a(), request.b(), request.c());
        String fingerprint = fingerprint("GEO_CREATE", request.observationId(),
                GeoDecimal.normalize(request.x()), GeoDecimal.normalize(request.y()),
                GeoDecimal.normalize(request.a()), GeoDecimal.normalize(request.b()),
                GeoDecimal.normalize(request.c()), request.operator());
        RequestLogEntry replayed = findReplayed(request.requestId(), fingerprint);
        if (replayed != null) {
            return new GeoWriteOutcome(replayed.responseStatus(),
                    readBody(replayed.responseBody(), GeoObservationResponse.class));
        }
        RequestLogEntry concurrent = insertPlaceholder(request.requestId(), fingerprint, "GEO_CREATE");
        if (concurrent != null) {
            return new GeoWriteOutcome(concurrent.responseStatus(),
                    readBody(concurrent.responseBody(), GeoObservationResponse.class));
        }

        if (geoObservationRepository.findCurrentForUpdate(request.observationId()).isPresent()) {
            throw ApiException.conflict("observation already exists: " + request.observationId(), null);
        }
        GeoObservationSnapshot snapshot = new GeoObservationSnapshot(request.observationId(), 1,
                request.x(), request.y(), request.a(), request.b(), request.c(), false);
        try {
            geoObservationRepository.insertCurrent(snapshot);
        } catch (DuplicateKeyException e) {
            // 并发创建同一 observationId：由主键串行化，后到者按冲突处理
            throw ApiException.conflict("observation already exists: " + request.observationId(), null);
        }
        geoObservationRepository.insertVersion(snapshot);
        return complete(request.requestId(), HttpStatus.CREATED, GeoObservationResponse.of(snapshot));
    }

    /**
     * 离线提交：基于 baseVersion 的三方合并，坐标与矩阵作为完整字段组整体判定。
     * 合并结果与当前完全相同则不加版本，否则版本加一；双方不同改动整组冲突 409。
     */
    @Transactional
    public GeoWriteOutcome merge(String observationId, MergeGeoObservationRequest request) {
        validateMatrix(request.a(), request.b(), request.c());
        String fingerprint = fingerprint("GEO_MERGE", observationId, String.valueOf(request.baseVersion()),
                GeoDecimal.normalize(request.x()), GeoDecimal.normalize(request.y()),
                GeoDecimal.normalize(request.a()), GeoDecimal.normalize(request.b()),
                GeoDecimal.normalize(request.c()), request.operator());
        RequestLogEntry replayed = findReplayed(request.requestId(), fingerprint);
        if (replayed != null) {
            return new GeoWriteOutcome(replayed.responseStatus(),
                    readBody(replayed.responseBody(), GeoObservationResponse.class));
        }
        RequestLogEntry concurrent = insertPlaceholder(request.requestId(), fingerprint, "GEO_MERGE");
        if (concurrent != null) {
            return new GeoWriteOutcome(concurrent.responseStatus(),
                    readBody(concurrent.responseBody(), GeoObservationResponse.class));
        }

        GeoObservationSnapshot current = geoObservationRepository.findCurrentForUpdate(observationId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));
        if (current.deleted()) {
            throw ApiException.gone("observation already deleted: " + observationId);
        }
        GeoObservationSnapshot base = geoObservationRepository.findVersion(observationId, request.baseVersion())
                .orElseThrow(() -> ApiException.notFound(
                        "base version not found: " + observationId + "@" + request.baseVersion()));

        GeoObservationSnapshot merged;
        if (groupEquals(request.x(), request.y(), request.a(), request.b(), request.c(), base)) {
            // 候选整组未改：保留当前
            merged = current;
        } else if (groupEquals(current, base)
                || groupEquals(request.x(), request.y(), request.a(), request.b(), request.c(), current)) {
            // 当前未改或两边改为相同值：接受候选整组
            merged = new GeoObservationSnapshot(observationId, current.version(),
                    request.x(), request.y(), request.a(), request.b(), request.c(), false);
        } else {
            throw ApiException.mergeConflict(List.of("geo"), current.version());
        }

        if (groupEquals(merged, current)) {
            // 合并结果与当前完全相同：返回当前版本，不加版本
            return complete(request.requestId(), HttpStatus.OK, GeoObservationResponse.of(current));
        }
        GeoObservationSnapshot next = new GeoObservationSnapshot(observationId, current.version() + 1,
                merged.x(), merged.y(), merged.a(), merged.b(), merged.c(), false);
        geoObservationRepository.updateCurrent(next);
        geoObservationRepository.insertVersion(next);
        return complete(request.requestId(), HttpStatus.OK, GeoObservationResponse.of(next));
    }

    /**
     * 删除二维坐标观测记录：expectedVersion 匹配当前版本时生成新版本墓碑；已删除记录返回 410。
     */
    @Transactional
    public GeoWriteOutcome delete(String observationId, DeleteGeoObservationRequest request) {
        String fingerprint = fingerprint("GEO_DELETE", observationId,
                String.valueOf(request.expectedVersion()), request.operator());
        RequestLogEntry replayed = findReplayed(request.requestId(), fingerprint);
        if (replayed != null) {
            return new GeoWriteOutcome(replayed.responseStatus(),
                    readBody(replayed.responseBody(), GeoObservationResponse.class));
        }
        RequestLogEntry concurrent = insertPlaceholder(request.requestId(), fingerprint, "GEO_DELETE");
        if (concurrent != null) {
            return new GeoWriteOutcome(concurrent.responseStatus(),
                    readBody(concurrent.responseBody(), GeoObservationResponse.class));
        }

        GeoObservationSnapshot current = geoObservationRepository.findCurrentForUpdate(observationId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));
        if (current.deleted()) {
            throw ApiException.gone("observation already deleted: " + observationId);
        }
        if (request.expectedVersion() != current.version()) {
            throw ApiException.conflict("expectedVersion mismatch", current.version());
        }
        GeoObservationSnapshot tombstone = new GeoObservationSnapshot(observationId, current.version() + 1,
                null, null, null, null, null, true);
        geoObservationRepository.markDeleted(observationId, tombstone.version());
        geoObservationRepository.insertVersion(tombstone);
        return complete(request.requestId(), HttpStatus.OK, GeoObservationResponse.of(tombstone));
    }

    /**
     * 查询当前内容；墓碑只返回删除状态和版本。
     */
    @Transactional(readOnly = true)
    public GeoObservationResponse getCurrent(String observationId) {
        GeoObservationSnapshot current = geoObservationRepository.findCurrent(observationId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));
        return GeoObservationResponse.of(current);
    }

    /**
     * 查询指定历史版本；墓碑版本只返回删除状态和版本。历史版本一律读取当时快照，不从当前字段补齐。
     */
    @Transactional(readOnly = true)
    public GeoObservationResponse getVersion(String observationId, int version) {
        GeoObservationSnapshot snapshot = geoObservationRepository.findVersion(observationId, version)
                .orElseThrow(() -> ApiException.notFound(
                        "version not found: " + observationId + "@" + version));
        return GeoObservationResponse.of(snapshot);
    }

    /**
     * 一致性确认：引用两条不同观测的当前版本，原子核对两份完整版本后按方向相关误差判定。
     *
     * <p>判定式（精确乘积比较，不开根、不舍入）：
     * C*u^2 - 2*B*u*v + A*v^2 &lt;= 9*(A*C - B^2)，其中 u、v 为坐标差，A、B、C 为两矩阵之和。
     * 超出门限返回 422；参与观测为墓碑或两条标识相同返回 422；期望版本过旧返回 409；
     * 矩阵非法返回 400。成功时冻结两侧快照与比较值写入不可变确认记录，不改动源观测。
     */
    @Transactional
    public ConfirmOutcome confirm(ConfirmConsistencyRequest request) {
        String fingerprint = fingerprint("GEO_CONFIRM",
                request.observationIdA(), String.valueOf(request.expectedVersionA()),
                request.observationIdB(), String.valueOf(request.expectedVersionB()),
                request.operator());
        RequestLogEntry replayed = findReplayed(request.requestId(), fingerprint);
        if (replayed != null) {
            return new ConfirmOutcome(replayed.responseStatus(),
                    readBody(replayed.responseBody(), ConsistencyConfirmation.class));
        }
        RequestLogEntry concurrent = insertPlaceholder(request.requestId(), fingerprint, "GEO_CONFIRM");
        if (concurrent != null) {
            return new ConfirmOutcome(concurrent.responseStatus(),
                    readBody(concurrent.responseBody(), ConsistencyConfirmation.class));
        }

        if (request.observationIdA().equals(request.observationIdB())) {
            throw ApiException.unprocessable(
                    "consistency confirmation requires two different observations: " + request.observationIdA());
        }
        // 按标识字典序加锁两条当前行，避免与并发确认互相死锁；与合并/删除的行锁互斥，实现原子核对。
        String firstId = request.observationIdA().compareTo(request.observationIdB()) < 0
                ? request.observationIdA() : request.observationIdB();
        String secondId = firstId.equals(request.observationIdA())
                ? request.observationIdB() : request.observationIdA();
        GeoObservationSnapshot first = geoObservationRepository.findCurrentForUpdate(firstId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + firstId));
        GeoObservationSnapshot second = geoObservationRepository.findCurrentForUpdate(secondId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + secondId));
        GeoObservationSnapshot snapshotA = firstId.equals(request.observationIdA()) ? first : second;
        GeoObservationSnapshot snapshotB = firstId.equals(request.observationIdB()) ? first : second;

        if (snapshotA.deleted() || snapshotB.deleted()) {
            throw ApiException.unprocessable("consistency confirmation rejects tombstone observation");
        }
        if (snapshotA.version() != request.expectedVersionA()) {
            throw ApiException.conflict("expectedVersionA mismatch", snapshotA.version());
        }
        if (snapshotB.version() != request.expectedVersionB()) {
            throw ApiException.conflict("expectedVersionB mismatch", snapshotB.version());
        }
        validateMatrix(snapshotA.a(), snapshotA.b(), snapshotA.c());
        validateMatrix(snapshotB.a(), snapshotB.b(), snapshotB.c());

        BigDecimal u = GeoDecimal.parse(snapshotA.x()).subtract(GeoDecimal.parse(snapshotB.x()));
        BigDecimal v = GeoDecimal.parse(snapshotA.y()).subtract(GeoDecimal.parse(snapshotB.y()));
        BigDecimal sumA = GeoDecimal.parse(snapshotA.a()).add(GeoDecimal.parse(snapshotB.a()));
        BigDecimal sumB = GeoDecimal.parse(snapshotA.b()).add(GeoDecimal.parse(snapshotB.b()));
        BigDecimal sumC = GeoDecimal.parse(snapshotA.c()).add(GeoDecimal.parse(snapshotB.c()));
        BigDecimal lhs = sumC.multiply(u.multiply(u))
                .subtract(TWO.multiply(sumB).multiply(u).multiply(v))
                .add(sumA.multiply(v.multiply(v)));
        BigDecimal rhs = NINE.multiply(sumA.multiply(sumC).subtract(sumB.multiply(sumB)));
        if (lhs.compareTo(rhs) > 0) {
            throw ApiException.unprocessable(
                    "consistency check failed: " + lhs.toPlainString() + " > " + rhs.toPlainString());
        }

        ConsistencyConfirmation confirmation = new ConsistencyConfirmation(
                UUID.randomUUID().toString(), request.requestId(),
                snapshotA.observationId(), snapshotA.version(),
                snapshotA.x(), snapshotA.y(), snapshotA.a(), snapshotA.b(), snapshotA.c(),
                snapshotB.observationId(), snapshotB.version(),
                snapshotB.x(), snapshotB.y(), snapshotB.a(), snapshotB.b(), snapshotB.c(),
                u.toPlainString(), v.toPlainString(),
                sumA.toPlainString(), sumB.toPlainString(), sumC.toPlainString(),
                lhs.toPlainString(), rhs.toPlainString(),
                request.operator(), Instant.now(clock));
        confirmationRepository.insert(confirmation);
        requestLogRepository.complete(request.requestId(), HttpStatus.CREATED.value(),
                writeBody(confirmation));
        return new ConfirmOutcome(HttpStatus.CREATED.value(), confirmation);
    }

    /**
     * 按全局唯一 confirmationId 查询不可变确认记录；不存在返回 404。
     * 记录为确认时刻冻结的完整快照，后续观测变更不影响查询结果。
     */
    @Transactional(readOnly = true)
    public ConsistencyConfirmation getConfirmation(String confirmationId) {
        return confirmationRepository.findByConfirmationId(confirmationId)
                .orElseThrow(() -> ApiException.notFound("confirmation not found: " + confirmationId));
    }

    // ---------- 内部辅助 ----------

    /**
     * 校验协方差矩阵合法性：a、c 必须为正且 a*c &gt; b*b（精确乘积比较）；非法返回 400。
     */
    private void validateMatrix(String a, String b, String c) {
        BigDecimal da = GeoDecimal.parse(a);
        BigDecimal db = GeoDecimal.parse(b);
        BigDecimal dc = GeoDecimal.parse(c);
        if (da.signum() <= 0 || dc.signum() <= 0) {
            throw ApiException.badRequest("covariance matrix requires positive a and c");
        }
        if (da.multiply(dc).compareTo(db.multiply(db)) <= 0) {
            throw ApiException.badRequest("covariance matrix requires a*c > b*b");
        }
    }

    /**
     * 判断候选五元组与快照的坐标+矩阵字段组是否整体相等（十进制按数值，墓碑快照全 null 视为不等）。
     */
    private boolean groupEquals(String x, String y, String a, String b, String c, GeoObservationSnapshot snapshot) {
        return !snapshot.deleted()
                && GeoDecimal.valueEquals(x, snapshot.x())
                && GeoDecimal.valueEquals(y, snapshot.y())
                && GeoDecimal.valueEquals(a, snapshot.a())
                && GeoDecimal.valueEquals(b, snapshot.b())
                && GeoDecimal.valueEquals(c, snapshot.c());
    }

    /**
     * 判断两个快照的坐标+矩阵字段组是否整体相等（十进制按数值）。
     */
    private boolean groupEquals(GeoObservationSnapshot left, GeoObservationSnapshot right) {
        if (left.deleted() || right.deleted()) {
            return left.deleted() && right.deleted();
        }
        return groupEquals(left.x(), left.y(), left.a(), left.b(), left.c(), right);
    }

    /**
     * 幂等检查：同键同参返回已落库记录；同键异参抛 409；无记录返回 null 继续执行。
     */
    private RequestLogEntry findReplayed(String requestId, String fingerprint) {
        return requestLogRepository.find(requestId)
                .map(entry -> {
                    if (!entry.fingerprint().equals(fingerprint)) {
                        throw ApiException.conflict(
                                "requestId reused with different parameters: " + requestId, null);
                    }
                    return entry;
                })
                .orElse(null);
    }

    /**
     * 占位写入去重记录；并发同键时主键冲突，读取已提交结果：同参返回记录，异参抛 409；
     * 正常占位返回 null。业务失败时占位随事务回滚，不占键。
     */
    private RequestLogEntry insertPlaceholder(String requestId, String fingerprint, String operation) {
        try {
            requestLogRepository.insertPlaceholder(requestId, fingerprint, operation);
            return null;
        } catch (DuplicateKeyException e) {
            RequestLogEntry entry = requestLogRepository.find(requestId)
                    .orElseThrow(() -> ApiException.conflict("requestId conflict: " + requestId, null));
            if (!entry.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
            }
            return entry;
        }
    }

    /**
     * 业务成功后回填去重记录响应，并构造本次写操作结果；与业务变更同事务提交。
     */
    private GeoWriteOutcome complete(String requestId, HttpStatus status, GeoObservationResponse body) {
        requestLogRepository.complete(requestId, status.value(), writeBody(body));
        return new GeoWriteOutcome(status.value(), body);
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
     * 二维坐标观测写操作结果：HTTP 状态码与响应体。
     */
    public record GeoWriteOutcome(int status, GeoObservationResponse body) {
    }

    /**
     * 一致性确认结果：HTTP 状态码与确认记录。
     */
    public record ConfirmOutcome(int status, ConsistencyConfirmation body) {
    }
}
