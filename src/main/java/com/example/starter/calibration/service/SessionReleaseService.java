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
import com.example.starter.calibration.api.SessionReleaseRejectedException;
import com.example.starter.calibration.api.dto.SessionReleaseResponse;
import com.example.starter.calibration.model.CalibrationSession;
import com.example.starter.calibration.model.Certificate;
import com.example.starter.calibration.model.Measurement;
import com.example.starter.calibration.model.MeasurementStatus;
import com.example.starter.calibration.model.SessionStatus;
import com.example.starter.calibration.repo.CertificateRepository;
import com.example.starter.calibration.repo.MeasurementRepository;
import com.example.starter.calibration.repo.ReleaseRepository;
import com.example.starter.calibration.repo.SessionRepository;

/**
 * 会话批量放行服务：只能引用已关闭的完整会话，整批原子生效；
 * 任一成员不合格或证书撤销则整批 422。放行人不得是任何成员的提交人。
 */
@Service
public class SessionReleaseService {

    /** 单批最大会话数。 */
    static final int MAX_BATCH_SIZE = 50;

    private final SessionRepository sessions;
    private final CertificateRepository certificates;
    private final MeasurementRepository measurements;
    private final ReleaseRepository releases;

    public SessionReleaseService(SessionRepository sessions,
                                 CertificateRepository certificates,
                                 MeasurementRepository measurements,
                                 ReleaseRepository releases) {
        this.sessions = sessions;
        this.certificates = certificates;
        this.measurements = measurements;
        this.releases = releases;
    }

    /**
     * 原子批量放行会话。每个会话必须：已关闭、至少 1 条成员、全部成员合格且待放行、
     * 证书未撤销、放行人不同于任何成员提交人。行锁按会话键字典序获取，避免并发批次间死锁。
     */
    @Transactional
    public SessionReleaseResponse release(List<String> keys, String actor) {
        String releaser = Inputs.requireText(actor, "X-Actor-Id", 64);
        if (keys == null || keys.isEmpty() || keys.size() > MAX_BATCH_SIZE) {
            throw ApiException.badRequest("批量放行会话数必须为 1～" + MAX_BATCH_SIZE);
        }
        List<String> orderedKeys = keys.stream().map(k -> Inputs.requireText(k, "keys[]")).toList();
        Set<String> distinct = new HashSet<>(orderedKeys);
        if (distinct.size() != orderedKeys.size()) {
            throw ApiException.badRequest("批量放行包含重复会话键");
        }

        List<ItemFailure> failures = new ArrayList<>();
        List<Measurement> approved = new ArrayList<>();
        for (String key : orderedKeys.stream().sorted().toList()) {
            var locked = sessions.findByKeyForUpdate(key);
            if (locked.isEmpty()) {
                failures.add(new ItemFailure(key, List.of("SESSION_NOT_FOUND")));
                continue;
            }
            CalibrationSession session = locked.get();
            List<String> reasons = new ArrayList<>();
            if (session.status() != SessionStatus.CLOSED) {
                reasons.add("SESSION_NOT_CLOSED");
            } else if (session.version() < 1) {
                reasons.add("SESSION_EMPTY");
            }
            Certificate cert = certificates.findByIdForUpdate(session.certificateId())
                    .orElseThrow(() -> ApiException.unprocessable("CERTIFICATE_MISSING",
                            "会话关联的证书不存在: " + session.certificateId()));
            if (cert.revoked()) {
                reasons.add("CERTIFICATE_REVOKED");
            }
            List<Measurement> entries = measurements.findBySessionIdForUpdate(session.id());
            for (Measurement entry : entries) {
                if (!entry.passed()) {
                    reasons.add("NOT_PASSED");
                    break;
                }
            }
            for (Measurement entry : entries) {
                if (entry.status() != MeasurementStatus.PENDING) {
                    reasons.add("NOT_PENDING");
                    break;
                }
            }
            for (Measurement entry : entries) {
                if (entry.submittedBy().equals(releaser)) {
                    reasons.add("SAME_ACTOR");
                    break;
                }
            }
            if (reasons.isEmpty()) {
                approved.addAll(entries);
            } else {
                failures.add(new ItemFailure(key, reasons));
            }
        }

        if (!failures.isEmpty()) {
            throw new SessionReleaseRejectedException(failures);
        }

        String batchId = UUID.randomUUID().toString();
        Instant releasedAt = Instant.now();
        for (Measurement entry : approved) {
            measurements.markReleased(entry.id());
            releases.insert(batchId, entry.id(), releaser, releasedAt);
        }
        return new SessionReleaseResponse(batchId, releaser, releasedAt, orderedKeys,
                approved.stream().map(Measurement::measurementKey).toList());
    }
}
