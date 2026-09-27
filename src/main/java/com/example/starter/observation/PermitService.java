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
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * 观测采样许可业务服务：创建（交集重叠 409）、提前结束、撤销与计数查询。
 *
 * <p>写操作均在单事务内完成：先占位写入幂等去重记录，再加锁完成业务判定与变更，
 * 最后回填去重记录响应并整体提交；任何业务失败都回滚，去重记录不占键。
 *
 * <p>创建许可时按设备加互斥锁（permit_device_lock），串行化同一设备的许可创建；
 * 同一设备的有效许可（ACTIVE/ENDED）在网格与时间的交集上不得重叠，重叠返回 409 PERMIT_OVERLAP。
 *
 * <p>提前结束只影响之后采样，不删除已接收观测；撤销许可将未合并（版本仍为 1）且未经人工裁决的
 * 观测标记 PERMIT_REVIEW 并写入 permit_review_snapshot 快照，已人工裁决的冲突簇不重算。
 */
@Service
public class PermitService {

    private static final String SEPARATOR = "";

    private final PermitRepository permitRepository;
    private final ObservationRepository observationRepository;
    private final ResolutionRepository resolutionRepository;
    private final PermitReviewSnapshotRepository reviewSnapshotRepository;
    private final RequestLogRepository requestLogRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public PermitService(PermitRepository permitRepository,
                         ObservationRepository observationRepository,
                         ResolutionRepository resolutionRepository,
                         PermitReviewSnapshotRepository reviewSnapshotRepository,
                         RequestLogRepository requestLogRepository,
                         ObjectMapper objectMapper,
                         Clock clock) {
        this.permitRepository = permitRepository;
        this.observationRepository = observationRepository;
        this.resolutionRepository = resolutionRepository;
        this.reviewSnapshotRepository = reviewSnapshotRepository;
        this.requestLogRepository = requestLogRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 创建采样许可。同一设备的有效许可在网格与时间交集上重叠时返回 409 PERMIT_OVERLAP。
     */
    @Transactional
    public PermitOutcome create(CreatePermitRequest request) {
        validateGridAndWindow(request.gridXMin(), request.gridXMax(), request.gridYMin(), request.gridYMax(),
                request.validFromUtc(), request.validToUtc());
        String fingerprint = fingerprint("PERMIT_CREATE", request.permitId(), request.deviceId(),
                String.valueOf(request.gridXMin()), String.valueOf(request.gridXMax()),
                String.valueOf(request.gridYMin()), String.valueOf(request.gridYMax()),
                String.valueOf(request.validFromUtc()), String.valueOf(request.validToUtc()),
                String.valueOf(request.maxObservations()), String.valueOf(request.permitVersion()));
        PermitOutcome replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        PermitOutcome concurrent = insertPlaceholder(request.requestId(), fingerprint, "PERMIT_CREATE");
        if (concurrent != null) {
            return concurrent;
        }

        // 设备级互斥锁：串行化同一设备的许可创建，防止并发创建出交集重叠的许可
        permitRepository.lockDevice(request.deviceId());
        if (permitRepository.findByPermitId(request.permitId()).isPresent()) {
            throw ApiException.conflict("permit already exists: " + request.permitId(), null);
        }
        SamplingPermit candidate = new SamplingPermit(request.permitId(), request.deviceId(),
                request.gridXMin(), request.gridXMax(), request.gridYMin(), request.gridYMax(),
                request.validFromUtc(), request.validToUtc(),
                request.maxObservations(), 0, request.permitVersion(), SamplingPermit.STATUS_ACTIVE);
        for (SamplingPermit existing : permitRepository.findValidByDeviceForUpdate(request.deviceId())) {
            if (existing.intersects(candidate)) {
                throw ApiException.conflictWithReason("PERMIT_OVERLAP",
                        "permit overlaps existing permit " + existing.permitId()
                                + " for device " + request.deviceId()
                                + " in grid/time intersection");
            }
        }
        permitRepository.insert(candidate);
        return complete(request.requestId(), HttpStatus.CREATED, PermitResponse.of(candidate));
    }

    /**
     * 提前结束许可：有效期结束时刻提前到指定 UTC 时刻（必须严格位于原有效期内部），
     * 只影响之后采样，不删除已接收观测。已撤销许可返回 409。
     */
    @Transactional
    public PermitOutcome end(String permitId, EndPermitRequest request) {
        String fingerprint = fingerprint("PERMIT_END", permitId, String.valueOf(request.endAtUtc()));
        PermitOutcome replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        PermitOutcome concurrent = insertPlaceholder(request.requestId(), fingerprint, "PERMIT_END");
        if (concurrent != null) {
            return concurrent;
        }

        SamplingPermit permit = permitRepository.findByPermitIdForUpdate(permitId)
                .orElseThrow(() -> ApiException.notFound("permit not found: " + permitId));
        if (SamplingPermit.STATUS_REVOKED.equals(permit.status())) {
            throw ApiException.conflictWithReason("PERMIT_ALREADY_REVOKED", "permit already revoked: " + permitId);
        }
        if (!request.endAtUtc().isAfter(permit.validFromUtc()) || !request.endAtUtc().isBefore(permit.validToUtc())) {
            throw ApiException.badRequest("endAtUtc must be strictly inside the permit validity window: "
                    + permit.validFromUtc() + " .. " + permit.validToUtc());
        }
        permitRepository.markEnded(permitId, request.endAtUtc());
        SamplingPermit ended = permitRepository.findByPermitId(permitId)
                .orElseThrow(() -> new IllegalStateException("permit missing after end: " + permitId));
        return complete(request.requestId(), HttpStatus.OK, PermitResponse.of(ended));
    }

    /**
     * 撤销许可：许可不再命中任何提交；未合并（版本仍为 1）且未经人工裁决的观测标记 PERMIT_REVIEW
     * 并写入复核快照；已人工裁决的冲突簇不重算。重复撤销返回 409 PERMIT_ALREADY_REVOKED。
     */
    @Transactional
    public PermitOutcome revoke(String permitId, RevokePermitRequest request) {
        String fingerprint = fingerprint("PERMIT_REVOKE", permitId);
        PermitOutcome replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        PermitOutcome concurrent = insertPlaceholder(request.requestId(), fingerprint, "PERMIT_REVOKE");
        if (concurrent != null) {
            return concurrent;
        }

        SamplingPermit permit = permitRepository.findByPermitIdForUpdate(permitId)
                .orElseThrow(() -> ApiException.notFound("permit not found: " + permitId));
        if (SamplingPermit.STATUS_REVOKED.equals(permit.status())) {
            throw ApiException.conflictWithReason("PERMIT_ALREADY_REVOKED", "permit already revoked: " + permitId);
        }
        permitRepository.markRevoked(permitId);

        Instant revokedAtUtc = Instant.now(clock);
        for (ObservationSnapshot observation
                : observationRepository.findByPermitAndReviewStatus(permitId, SamplingPermit.REVIEW_NONE)) {
            if (observation.version() != 1) {
                // 已经历合并的观测不属于“未合并观测”，不标记复核
                continue;
            }
            if (resolutionRepository.existsByObservationId(observation.observationId())) {
                // 已人工裁决的冲突簇不重算、不标记
                continue;
            }
            observationRepository.updateReviewStatus(observation.observationId(),
                    SamplingPermit.REVIEW_PERMIT_REVIEW);
            reviewSnapshotRepository.insert(new PermitReviewSnapshot(null, observation.observationId(),
                    permitId, permit.permitVersion(), observation.version(), revokedAtUtc));
        }

        SamplingPermit revoked = permitRepository.findByPermitId(permitId)
                .orElseThrow(() -> new IllegalStateException("permit missing after revoke: " + permitId));
        return complete(request.requestId(), HttpStatus.OK, PermitResponse.of(revoked));
    }

    /**
     * 查询许可（含观测计数：已用/上限/剩余）；不存在返回 404。
     */
    @Transactional(readOnly = true)
    public PermitResponse getPermit(String permitId) {
        SamplingPermit permit = permitRepository.findByPermitId(permitId)
                .orElseThrow(() -> ApiException.notFound("permit not found: " + permitId));
        return PermitResponse.of(permit);
    }

    /**
     * 查询许可撤销时写入的复核快照，按落库先后排序；许可不存在返回 404。
     */
    @Transactional(readOnly = true)
    public List<PermitReviewSnapshot> listReviewSnapshots(String permitId) {
        permitRepository.findByPermitId(permitId)
                .orElseThrow(() -> ApiException.notFound("permit not found: " + permitId));
        return reviewSnapshotRepository.findByPermitId(permitId);
    }

    /**
     * 校验网格闭区间与 UTC 左闭右开有效期本身合法。
     */
    private void validateGridAndWindow(int gridXMin, int gridXMax, int gridYMin, int gridYMax,
                                       Instant validFromUtc, Instant validToUtc) {
        if (gridXMin > gridXMax || gridYMin > gridYMax) {
            throw ApiException.badRequest("grid range min must not exceed max");
        }
        if (!validFromUtc.isBefore(validToUtc)) {
            throw ApiException.badRequest("validFromUtc must be before validToUtc");
        }
    }

    /**
     * 幂等检查：同键同参返回原成功结果；同键异参返回 409；无记录返回 null 继续执行。
     */
    private PermitOutcome checkReplay(String requestId, String fingerprint) {
        Optional<RequestLogEntry> existing = requestLogRepository.find(requestId);
        if (existing.isEmpty()) {
            return null;
        }
        RequestLogEntry entry = existing.get();
        if (!entry.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
        }
        return new PermitOutcome(entry.responseStatus(), readBody(entry.responseBody()));
    }

    /**
     * 占位写入去重记录；并发同键时主键冲突，等待对方事务结束后读取已提交结果：
     * 同参返回重放结果，异参抛 409；正常占位返回 null。业务失败时占位随事务回滚，不占键。
     */
    private PermitOutcome insertPlaceholder(String requestId, String fingerprint, String operation) {
        try {
            requestLogRepository.insertPlaceholder(requestId, fingerprint, operation);
            return null;
        } catch (DuplicateKeyException e) {
            RequestLogEntry entry = requestLogRepository.find(requestId)
                    .orElseThrow(() -> ApiException.conflict("requestId conflict: " + requestId, null));
            if (!entry.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
            }
            return new PermitOutcome(entry.responseStatus(), readBody(entry.responseBody()));
        }
    }

    /**
     * 业务成功后回填去重记录响应，与业务变更同事务提交。
     */
    private PermitOutcome complete(String requestId, HttpStatus status, PermitResponse body) {
        requestLogRepository.complete(requestId, status.value(), writeBody(body));
        return new PermitOutcome(status.value(), body);
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

    private String writeBody(PermitResponse body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize response", e);
        }
    }

    private PermitResponse readBody(String json) {
        try {
            return objectMapper.readValue(json, PermitResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored response", e);
        }
    }

    /**
     * 许可写操作结果：HTTP 状态码与许可响应体。
     */
    public record PermitOutcome(int status, PermitResponse body) {
    }
}
