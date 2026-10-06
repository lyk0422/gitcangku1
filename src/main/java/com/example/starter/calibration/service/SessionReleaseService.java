package com.example.starter.calibration.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.api.ItemFailure;
import com.example.starter.calibration.api.SessionBatchRejectedException;
import com.example.starter.calibration.api.dto.ReleaseSessionsRequest;
import com.example.starter.calibration.api.dto.SessionReleaseResponse;
import com.example.starter.calibration.model.CalibrationSession;
import com.example.starter.calibration.model.Certificate;
import com.example.starter.calibration.model.Measurement;
import com.example.starter.calibration.model.SessionStatus;
import com.example.starter.calibration.repo.CertificateRepository;
import com.example.starter.calibration.repo.MeasurementRepository;
import com.example.starter.calibration.repo.ReleaseRepository;
import com.example.starter.calibration.repo.SessionRepository;

/**
 * 会话批量放行服务：只能引用已关闭且非空的会话；任一测量不合格或证书撤销整批 422；
 * 放行人不得是任何成员提交人。会话行锁+证书行锁按 ID 升序获取，与关闭、追加、撤销原子判定。
 */
@Service
public class SessionReleaseService {

    /** 单批最大会话数。 */
    static final int MAX_BATCH_SIZE = 50;

    private final SessionRepository sessions;
    private final CertificateRepository certificates;
    private final MeasurementRepository measurements;
    private final ReleaseRepository releases;
    private final IdempotencyService idempotency;

    public SessionReleaseService(SessionRepository sessions, CertificateRepository certificates,
                                 MeasurementRepository measurements, ReleaseRepository releases,
                                 IdempotencyService idempotency) {
        this.sessions = sessions;
        this.certificates = certificates;
        this.measurements = measurements;
        this.releases = releases;
        this.idempotency = idempotency;
    }

    /**
     * 原子批量放行会话。每个会话必须：已关闭、至少一条成员、全部成员合格、证书未撤销、
     * 放行人不同于任何成员提交人；任一不满足则整批 422，不产生部分成功。
     */
    @Transactional
    public SessionReleaseResponse release(ReleaseSessionsRequest request, String actor) {
        String requestId = Inputs.requireRequestId(request.requestId());
        String releaser = Inputs.requireText(actor, "X-Actor-Id");
        List<Long> ids = request.sessionIds();
        if (ids == null || ids.isEmpty() || ids.size() > MAX_BATCH_SIZE) {
            throw ApiException.badRequest("批量放行会话数必须为 1～" + MAX_BATCH_SIZE);
        }
        for (Long id : ids) {
            if (id == null) {
                throw ApiException.badRequest("sessionIds 不能包含空值");
            }
        }
        Set<Long> distinct = new HashSet<>(ids);
        if (distinct.size() != ids.size()) {
            throw ApiException.badRequest("批量放行包含重复会话 ID");
        }

        List<Object> identity = new ArrayList<>(List.of("SESSION_RELEASE", releaser));
        identity.addAll(ids);
        return idempotency.execute(requestId, "SESSION_RELEASE", identity, SessionReleaseResponse.class,
                () -> doRelease(ids, releaser));
    }

    private SessionReleaseResponse doRelease(List<Long> ids, String releaser) {
        // 第一阶段：按会话 ID 升序加行锁，避免并发批次间死锁
        List<CalibrationSession> locked = new ArrayList<>();
        List<ItemFailure> failures = new ArrayList<>();
        for (Long id : ids.stream().sorted().toList()) {
            var session = sessions.findByIdForUpdate(id);
            if (session.isEmpty()) {
                failures.add(new ItemFailure(String.valueOf(id), List.of("SESSION_NOT_FOUND")));
            } else {
                locked.add(session.get());
            }
        }
        // 第二阶段：按证书 ID 升序加行锁，与证书撤销按事务提交顺序互斥
        List<Long> certIds = locked.stream().map(CalibrationSession::certificateId).distinct().sorted().toList();
        java.util.Map<Long, Certificate> certs = new java.util.HashMap<>();
        for (Long certId : certIds) {
            certs.put(certId, certificates.findByIdForUpdate(certId)
                    .orElseThrow(() -> ApiException.conflict("CERTIFICATE_MISSING", "会话关联的证书不存在: " + certId)));
        }

        List<Measurement> approvedMeasurements = new ArrayList<>();
        for (CalibrationSession session : locked) {
            List<String> reasons = new ArrayList<>();
            List<Measurement> members = measurements.findBySessionId(session.id());
            if (session.status() != SessionStatus.CLOSED) {
                reasons.add("SESSION_NOT_CLOSED");
            }
            if (members.isEmpty()) {
                reasons.add("SESSION_EMPTY");
            }
            if (certs.get(session.certificateId()).revoked()) {
                reasons.add("CERTIFICATE_REVOKED");
            }
            for (Measurement member : members) {
                if (!member.passed()) {
                    reasons.add("MEASUREMENT_NOT_PASSED");
                    break;
                }
            }
            for (Measurement member : members) {
                if (member.submittedBy().equals(releaser)) {
                    reasons.add("SAME_ACTOR");
                    break;
                }
            }
            if (reasons.isEmpty()) {
                approvedMeasurements.addAll(members);
            } else {
                failures.add(new ItemFailure(String.valueOf(session.id()), reasons));
            }
        }

        if (!failures.isEmpty()) {
            throw new SessionBatchRejectedException(failures);
        }

        String batchId = UUID.randomUUID().toString();
        Instant releasedAt = Instant.now();
        List<String> releasedKeys = new ArrayList<>();
        for (CalibrationSession session : locked) {
            sessions.markReleased(session.id());
        }
        for (Measurement member : approvedMeasurements) {
            measurements.markReleased(member.id());
            releases.insert(batchId, member.id(), releaser, releasedAt);
            releasedKeys.add(member.measurementKey());
        }
        return new SessionReleaseResponse(batchId, releaser, releasedAt, ids, releasedKeys);
    }
}
