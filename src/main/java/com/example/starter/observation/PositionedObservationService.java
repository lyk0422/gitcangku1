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
import java.util.Optional;
import java.util.UUID;

/**
 * 观测点（新类型观测）业务服务：二维坐标与方向相关误差协方差矩阵（a、b、c）作为一个完整字段组。
 *
 * <p>写操作（创建/离线提交/删除/一致性确认）均在单事务内完成：先占位写入幂等去重记录，
 * 再对相关观测点行加锁（SELECT ... FOR UPDATE）完成业务判定与变更，最后回填去重记录的响应并整体提交；
 * 任何业务失败都会回滚，去重记录不占键。并发同键由主键串行化，重读已提交结果，不报唯一键 500。
 *
 * <p>三方合并中坐标与矩阵作为一个完整字段组：候选未改（等于基线）保留当前；当前未改接受候选；
 * 两边改为相同值也接受；双方不同改动时整组冲突（409，字段名 position），不逐字段拼装。
 * 组内十进制均按数值比较。
 *
 * <p>一致性确认引用两条不同观测点的当前版本：用坐标差 u、v 与两矩阵之和 A、B、C，
 * 检查 C*u^2 - 2*B*u*v + A*v^2 不大于 9*(A*C - B^2)；精确乘积比较，不开根不舍入。
 * 超限 422，矩阵非法 400，版本旧 409，墓碑 422。成功后冻结两侧坐标、矩阵与比较两侧值，
 * 保存确认关系而不改动源观测点；与合并、删除的行锁互斥，原子核对两份完整版本。
 */
@Service
public class PositionedObservationService {

    private static final String SEPARATOR = "\u0001";

    private final PositionedObservationRepository observationRepository;
    private final ConfirmationRepository confirmationRepository;
    private final RequestLogRepository requestLogRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public PositionedObservationService(PositionedObservationRepository observationRepository,
                                        ConfirmationRepository confirmationRepository,
                                        RequestLogRepository requestLogRepository,
                                        ObjectMapper objectMapper,
                                        Clock clock) {
        this.observationRepository = observationRepository;
        this.confirmationRepository = confirmationRepository;
        this.requestLogRepository = requestLogRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 创建观测点，初始版本为 1。观测点已存在返回 409；矩阵非法（a、c 非正或 a*c 不大于 b^2）返回 400。
     */
    @Transactional
    public PositionedWriteOutcome create(CreatePositionedObservationRequest request) {
        String fingerprint = fingerprint("POSITION_CREATE", request.observationId(),
                normalizeDecimal(request.x()), normalizeDecimal(request.y()),
                normalizeDecimal(request.a()), normalizeDecimal(request.b()), normalizeDecimal(request.c()),
                request.operator());
        PositionedWriteOutcome replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        PositionedWriteOutcome concurrent = insertPlaceholder(request.requestId(), fingerprint, "POSITION_CREATE");
        if (concurrent != null) {
            return concurrent;
        }

        validateMatrix(request.a(), request.b(), request.c());
        if (observationRepository.findCurrentForUpdate(request.observationId()).isPresent()) {
            throw ApiException.conflict("positioned observation already exists: " + request.observationId(), null);
        }
        PositionedObservationSnapshot snapshot = new PositionedObservationSnapshot(request.observationId(), 1,
                decimal(request.x()), decimal(request.y()),
                decimal(request.a()), decimal(request.b()), decimal(request.c()), false);
        try {
            observationRepository.insertCurrent(snapshot);
        } catch (DuplicateKeyException e) {
            // 并发创建同一 observationId：由主键串行化，后到者按冲突处理
            throw ApiException.conflict("positioned observation already exists: " + request.observationId(), null);
        }
        observationRepository.insertVersion(snapshot);
        return complete(request.requestId(), HttpStatus.CREATED, PositionedObservationResponse.of(snapshot));
    }

    /**
     * 离线提交：基于 baseVersion 的三方合并，坐标与矩阵作为完整字段组整体判定。
     * 合并结果与当前完全相同则不加版本，否则版本加一；双方不同改动整组冲突（409）。
     */
    @Transactional
    public PositionedWriteOutcome merge(String observationId, MergePositionedObservationRequest request) {
        String fingerprint = fingerprint("POSITION_MERGE", observationId, String.valueOf(request.baseVersion()),
                normalizeDecimal(request.x()), normalizeDecimal(request.y()),
                normalizeDecimal(request.a()), normalizeDecimal(request.b()), normalizeDecimal(request.c()),
                request.operator());
        PositionedWriteOutcome replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        PositionedWriteOutcome concurrent = insertPlaceholder(request.requestId(), fingerprint, "POSITION_MERGE");
        if (concurrent != null) {
            return concurrent;
        }

        validateMatrix(request.a(), request.b(), request.c());
        PositionedObservationSnapshot current = observationRepository.findCurrentForUpdate(observationId)
                .orElseThrow(() -> ApiException.notFound("positioned observation not found: " + observationId));
        if (current.deleted()) {
            throw ApiException.gone("positioned observation already deleted: " + observationId);
        }
        PositionedObservationSnapshot base = observationRepository.findVersion(observationId, request.baseVersion())
                .orElseThrow(() -> ApiException.notFound(
                        "base version not found: " + observationId + "@" + request.baseVersion()));
        if (base.deleted()) {
            throw ApiException.unprocessable(
                    "base version is a tombstone: " + observationId + "@" + request.baseVersion());
        }

        PositionedObservationSnapshot candidate = new PositionedObservationSnapshot(observationId, current.version(),
                decimal(request.x()), decimal(request.y()),
                decimal(request.a()), decimal(request.b()), decimal(request.c()), false);
        PositionedObservationSnapshot merged = mergeGroup(base, current, candidate);
        if (merged == null) {
            throw ApiException.mergeConflict(List.of("position"), current.version());
        }
        if (sameGroup(merged, current)) {
            // 合并结果与当前完全相同：返回当前版本，不加版本
            return complete(request.requestId(), HttpStatus.OK, PositionedObservationResponse.of(current));
        }
        PositionedObservationSnapshot next = new PositionedObservationSnapshot(observationId, current.version() + 1,
                merged.x(), merged.y(), merged.a(), merged.b(), merged.c(), false);
        observationRepository.updateCurrent(next);
        observationRepository.insertVersion(next);
        return complete(request.requestId(), HttpStatus.OK, PositionedObservationResponse.of(next));
    }

    /**
     * 删除观测点：expectedVersion 匹配当前版本时生成新版本墓碑；已删除返回 410。
     */
    @Transactional
    public PositionedWriteOutcome delete(String observationId, DeletePositionedObservationRequest request) {
        String fingerprint = fingerprint("POSITION_DELETE", observationId,
                String.valueOf(request.expectedVersion()), request.operator());
        PositionedWriteOutcome replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        PositionedWriteOutcome concurrent = insertPlaceholder(request.requestId(), fingerprint, "POSITION_DELETE");
        if (concurrent != null) {
            return concurrent;
        }

        PositionedObservationSnapshot current = observationRepository.findCurrentForUpdate(observationId)
                .orElseThrow(() -> ApiException.notFound("positioned observation not found: " + observationId));
        if (current.deleted()) {
            throw ApiException.gone("positioned observation already deleted: " + observationId);
        }
        if (request.expectedVersion() != current.version()) {
            throw ApiException.conflict("expectedVersion mismatch", current.version());
        }
        PositionedObservationSnapshot tombstone = new PositionedObservationSnapshot(observationId,
                current.version() + 1, null, null, null, null, null, true);
        observationRepository.markDeleted(observationId, tombstone.version());
        observationRepository.insertVersion(tombstone);
        return complete(request.requestId(), HttpStatus.OK, PositionedObservationResponse.of(tombstone));
    }

    /**
     * 查询当前内容；墓碑只返回删除状态和版本。
     */
    @Transactional(readOnly = true)
    public PositionedObservationResponse getCurrent(String observationId) {
        PositionedObservationSnapshot current = observationRepository.findCurrent(observationId)
                .orElseThrow(() -> ApiException.notFound("positioned observation not found: " + observationId));
        return PositionedObservationResponse.of(current);
    }

    /**
     * 查询指定历史版本；墓碑版本只返回删除状态和版本。
     */
    @Transactional(readOnly = true)
    public PositionedObservationResponse getVersion(String observationId, int version) {
        PositionedObservationSnapshot snapshot = observationRepository.findVersion(observationId, version)
                .orElseThrow(() -> ApiException.notFound(
                        "version not found: " + observationId + "@" + version));
        return PositionedObservationResponse.of(snapshot);
    }

    /**
     * 一致性确认：引用两条不同观测点的当前版本，按方向相关误差判定位置差异是否可接受。
     *
     * <p>事务内按标识排序依次对两行加锁（避免死锁），与并发合并、删除互斥地原子核对两份完整版本；
     * 成功时插入不可变确认记录，冻结两侧坐标、矩阵与比较两侧值，不改动源观测点。
     * 超限 422，矩阵非法 400，版本旧 409，墓碑或缺矩阵 422，两条标识相同 400。
     */
    @Transactional
    public ConfirmOutcome confirm(ConfirmConsistencyRequest request) {
        String fingerprint = fingerprint("CONFIRM", request.firstObservationId(),
                String.valueOf(request.firstExpectedVersion()), request.secondObservationId(),
                String.valueOf(request.secondExpectedVersion()), request.operator());
        ConfirmOutcome replayed = checkConfirmReplay(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        ConfirmOutcome concurrent = insertConfirmPlaceholder(request.requestId(), fingerprint);
        if (concurrent != null) {
            return concurrent;
        }

        if (request.firstObservationId().equals(request.secondObservationId())) {
            throw ApiException.badRequest("confirmation requires two different observations: "
                    + request.firstObservationId());
        }
        // 按标识排序加锁，保证并发确认/合并/删除的锁顺序一致，避免死锁
        String lockFirst = request.firstObservationId().compareTo(request.secondObservationId()) <= 0
                ? request.firstObservationId() : request.secondObservationId();
        String lockSecond = lockFirst.equals(request.firstObservationId())
                ? request.secondObservationId() : request.firstObservationId();
        PositionedObservationSnapshot lockedFirst = observationRepository.findCurrentForUpdate(lockFirst)
                .orElseThrow(() -> ApiException.notFound("positioned observation not found: " + lockFirst));
        PositionedObservationSnapshot lockedSecond = observationRepository.findCurrentForUpdate(lockSecond)
                .orElseThrow(() -> ApiException.notFound("positioned observation not found: " + lockSecond));
        PositionedObservationSnapshot first = lockFirst.equals(request.firstObservationId())
                ? lockedFirst : lockedSecond;
        PositionedObservationSnapshot second = lockFirst.equals(request.firstObservationId())
                ? lockedSecond : lockedFirst;

        checkConfirmable(first, request.firstExpectedVersion());
        checkConfirmable(second, request.secondExpectedVersion());
        validateStoredMatrix(first);
        validateStoredMatrix(second);

        BigDecimal u = first.x().subtract(second.x());
        BigDecimal v = first.y().subtract(second.y());
        BigDecimal sumA = first.a().add(second.a());
        BigDecimal sumB = first.b().add(second.b());
        BigDecimal sumC = first.c().add(second.c());
        // 左侧 C*u^2 - 2*B*u*v + A*v^2，右侧 9*(A*C - B^2)：精确乘积比较，不开根、不舍入
        BigDecimal lhs = sumC.multiply(u.multiply(u))
                .subtract(sumB.multiply(u).multiply(v).multiply(BigDecimal.valueOf(2)))
                .add(sumA.multiply(v.multiply(v)));
        BigDecimal rhs = sumA.multiply(sumC).subtract(sumB.multiply(sumB)).multiply(BigDecimal.valueOf(9));
        if (lhs.compareTo(rhs) > 0) {
            throw ApiException.unprocessable(
                    "consistency check exceeded: " + lhs.toPlainString() + " > " + rhs.toPlainString());
        }

        ConfirmationRecord record = new ConfirmationRecord(
                UUID.randomUUID().toString(), request.requestId(),
                first.observationId(), first.version(), second.observationId(), second.version(),
                first, second, u, v, sumA, sumB, sumC, lhs, rhs,
                request.operator(), Instant.now(clock));
        confirmationRepository.insert(record);
        return completeConfirm(request.requestId(), HttpStatus.CREATED, ConfirmationResponse.of(record));
    }

    /**
     * 按全局唯一 confirmationId 查询不可变确认记录；不存在返回 404。
     */
    @Transactional(readOnly = true)
    public ConfirmationRecord getConfirmation(String confirmationId) {
        return confirmationRepository.findByConfirmationId(confirmationId)
                .orElseThrow(() -> ApiException.notFound("confirmation not found: " + confirmationId));
    }

    /**
     * 按观测点标识查询其参与的确认历史，按确认时刻先后排序；观测点不存在返回 404。
     */
    @Transactional(readOnly = true)
    public List<ConfirmationRecord> listConfirmations(String observationId) {
        observationRepository.findCurrent(observationId)
                .orElseThrow(() -> ApiException.notFound("positioned observation not found: " + observationId));
        return confirmationRepository.findByObservationId(observationId);
    }

    /**
     * 核对单条观测点可参与确认：墓碑 422（不能从当前字段补齐旧版本），期望版本过旧 409。
     */
    private void checkConfirmable(PositionedObservationSnapshot snapshot, int expectedVersion) {
        if (snapshot.deleted()) {
            throw ApiException.unprocessable(
                    "observation is a tombstone: " + snapshot.observationId());
        }
        if (expectedVersion != snapshot.version()) {
            throw ApiException.conflict("expectedVersion mismatch for " + snapshot.observationId(),
                    snapshot.version());
        }
    }

    /**
     * 防御性校验已落库矩阵仍合法（a、c 正且 a*c 大于 b^2）；非法返回 400。
     */
    private void validateStoredMatrix(PositionedObservationSnapshot snapshot) {
        BigDecimal determinant = snapshot.a().multiply(snapshot.c())
                .subtract(snapshot.b().multiply(snapshot.b()));
        if (snapshot.a().compareTo(BigDecimal.ZERO) <= 0
                || snapshot.c().compareTo(BigDecimal.ZERO) <= 0
                || determinant.compareTo(BigDecimal.ZERO) <= 0) {
            throw ApiException.badRequest(
                    "stored covariance matrix is invalid for observation: " + snapshot.observationId());
        }
    }

    /**
     * 校验请求中的矩阵元素：a、c 必须为正且 a*c 大于 b^2，否则 400。
     */
    private void validateMatrix(String a, String b, String c) {
        BigDecimal aValue = new BigDecimal(a);
        BigDecimal bValue = new BigDecimal(b);
        BigDecimal cValue = new BigDecimal(c);
        BigDecimal determinant = aValue.multiply(cValue).subtract(bValue.multiply(bValue));
        if (aValue.compareTo(BigDecimal.ZERO) <= 0
                || cValue.compareTo(BigDecimal.ZERO) <= 0
                || determinant.compareTo(BigDecimal.ZERO) <= 0) {
            throw ApiException.badRequest(
                    "invalid covariance matrix: require a > 0, c > 0 and a*c > b^2");
        }
    }

    /**
     * 字段组三方合并：候选未改保留当前；当前未改或两边改为相同值接受候选；双方不同改动返回 null（整组冲突）。
     */
    private PositionedObservationSnapshot mergeGroup(PositionedObservationSnapshot base,
                                                     PositionedObservationSnapshot current,
                                                     PositionedObservationSnapshot candidate) {
        if (sameGroup(candidate, base)) {
            return current;
        }
        if (sameGroup(current, base) || sameGroup(candidate, current)) {
            return candidate;
        }
        return null;
    }

    /**
     * 判断两个快照的坐标与矩阵字段组是否完全相同（十进制按数值比较）。
     */
    private boolean sameGroup(PositionedObservationSnapshot left, PositionedObservationSnapshot right) {
        return decimalEquals(left.x(), right.x()) && decimalEquals(left.y(), right.y())
                && decimalEquals(left.a(), right.a()) && decimalEquals(left.b(), right.b())
                && decimalEquals(left.c(), right.c());
    }

    private boolean decimalEquals(BigDecimal left, BigDecimal right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.compareTo(right) == 0;
    }

    /**
     * 十进制按值规范化（用于指纹）：去除尾随零，零统一为 "0"，保证 4.0 与 4.00 同键。
     */
    private String normalizeDecimal(String value) {
        BigDecimal decimal = new BigDecimal(value);
        if (decimal.compareTo(BigDecimal.ZERO) == 0) {
            return "0";
        }
        return decimal.stripTrailingZeros().toPlainString();
    }

    /**
     * 解析请求中的十进制并统一为三位小数标度（与存储列 DECIMAL(30,3) 一致）。
     * 输入经校验最多三位小数，此处补零精确无舍入。
     */
    private BigDecimal decimal(String value) {
        return new BigDecimal(value).setScale(3);
    }

    /**
     * 幂等检查：同键同参返回原成功结果；同键异参返回 409；无记录返回 null 继续执行。
     */
    private PositionedWriteOutcome checkReplay(String requestId, String fingerprint) {
        Optional<RequestLogEntry> existing = requestLogRepository.find(requestId);
        if (existing.isEmpty()) {
            return null;
        }
        RequestLogEntry entry = existing.get();
        if (!entry.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
        }
        return new PositionedWriteOutcome(entry.responseStatus(), readBody(entry.responseBody()));
    }

    /**
     * 占位写入去重记录；并发同键时主键冲突，等待对方事务结束后读取已提交结果：
     * 同参返回重放结果，异参抛 409；正常占位返回 null。业务失败时占位随事务回滚，不占键。
     */
    private PositionedWriteOutcome insertPlaceholder(String requestId, String fingerprint, String operation) {
        try {
            requestLogRepository.insertPlaceholder(requestId, fingerprint, operation);
            return null;
        } catch (DuplicateKeyException e) {
            RequestLogEntry entry = requestLogRepository.find(requestId)
                    .orElseThrow(() -> ApiException.conflict("requestId conflict: " + requestId, null));
            if (!entry.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
            }
            return new PositionedWriteOutcome(entry.responseStatus(), readBody(entry.responseBody()));
        }
    }

    /**
     * 业务成功后回填去重记录响应，并构造本次写操作结果；与业务变更同事务提交。
     */
    private PositionedWriteOutcome complete(String requestId, HttpStatus status,
                                            PositionedObservationResponse body) {
        requestLogRepository.complete(requestId, status.value(), writeBody(body));
        return new PositionedWriteOutcome(status.value(), body);
    }

    /**
     * 确认请求的 requestId 幂等检查：同键同参返回原成功结果；同键异参返回 409；无记录返回 null。
     */
    private ConfirmOutcome checkConfirmReplay(String requestId, String fingerprint) {
        Optional<RequestLogEntry> existing = requestLogRepository.find(requestId);
        if (existing.isEmpty()) {
            return null;
        }
        RequestLogEntry entry = existing.get();
        if (!entry.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
        }
        return new ConfirmOutcome(entry.responseStatus(), readConfirmBody(entry.responseBody()));
    }

    /**
     * 确认请求的占位写入，语义与其他写操作一致：冲突后读已提交记录，同参重放、异参 409。
     */
    private ConfirmOutcome insertConfirmPlaceholder(String requestId, String fingerprint) {
        try {
            requestLogRepository.insertPlaceholder(requestId, fingerprint, "CONFIRM");
            return null;
        } catch (DuplicateKeyException e) {
            RequestLogEntry entry = requestLogRepository.find(requestId)
                    .orElseThrow(() -> ApiException.conflict("requestId conflict: " + requestId, null));
            if (!entry.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
            }
            return new ConfirmOutcome(entry.responseStatus(), readConfirmBody(entry.responseBody()));
        }
    }

    /**
     * 确认成功后回填去重记录响应，与确认记录在同一事务提交。
     */
    private ConfirmOutcome completeConfirm(String requestId, HttpStatus status, ConfirmationResponse body) {
        try {
            requestLogRepository.complete(requestId, status.value(), objectMapper.writeValueAsString(body));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize confirmation response", e);
        }
        return new ConfirmOutcome(status.value(), body);
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

    private String writeBody(PositionedObservationResponse body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize response", e);
        }
    }

    private PositionedObservationResponse readBody(String json) {
        try {
            return objectMapper.readValue(json, PositionedObservationResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored response", e);
        }
    }

    private ConfirmationResponse readConfirmBody(String json) {
        try {
            return objectMapper.readValue(json, ConfirmationResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored confirmation response", e);
        }
    }

    /**
     * 观测点写操作结果：HTTP 状态码与响应体。
     */
    public record PositionedWriteOutcome(int status, PositionedObservationResponse body) {
    }

    /**
     * 一致性确认结果：HTTP 状态码与确认响应体。
     */
    public record ConfirmOutcome(int status, ConfirmationResponse body) {
    }
}