package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.api.dto.PublishThresholdRequest;
import com.example.starter.calibration.api.dto.ThresholdResponse;
import com.example.starter.calibration.model.IdempotentRequest;
import com.example.starter.calibration.model.UncertaintyThreshold;
import com.example.starter.calibration.repo.CertificateRepository;
import com.example.starter.calibration.repo.IdempotentRequestRepository;
import com.example.starter.calibration.repo.ThresholdRepository;

/**
 * 仪器不确定度阈值服务：版本化发布（只增不改）、查询。
 * 发布在仪器级行锁内分配版本号；支持 X-Request-Id 幂等：同参重放首次完整响应，异参 409，失败不占键。
 */
@Service
public class ThresholdService {

    /** 幂等操作类型标识，参与指纹。 */
    public static final String OPERATION = "THRESHOLD_PUBLISH";

    private final ThresholdRepository thresholds;
    private final CertificateRepository certificates;
    private final IdempotentRequestRepository idempotentRequests;
    private final IdempotencySupport idempotency;
    private final Clock clock;

    public ThresholdService(ThresholdRepository thresholds,
                            CertificateRepository certificates,
                            IdempotentRequestRepository idempotentRequests,
                            IdempotencySupport idempotency,
                            Clock clock) {
        this.thresholds = thresholds;
        this.certificates = certificates;
        this.idempotentRequests = idempotentRequests;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    /**
     * 发布阈值新版本。同一仪器串行发布，版本号从 1 递增；负值返回 422。
     * requestId 非空时：同参重放返回首次完整响应快照，异参返回 409，业务失败回滚不占键。
     */
    @Transactional
    public IdempotentResult<ThresholdResponse> publish(PublishThresholdRequest request,
                                                       String actor, String requestId) {
        String operator = Inputs.requireText(actor, "X-Actor-Id");
        String instrumentId = Inputs.requireText(request.instrumentId(), "instrumentId");
        BigDecimal max = Inputs.requireNonNegativeDecimal(request.maxUncertainty(), "maxUncertainty");

        Map<String, Object> fingerprintBody = Fingerprints.ordered();
        fingerprintBody.put("operationType", OPERATION);
        fingerprintBody.put("operator", operator);
        fingerprintBody.put("instrumentId", instrumentId);
        fingerprintBody.put("maxUncertainty", max.stripTrailingZeros().toPlainString());
        String fingerprint = Fingerprints.sha256(fingerprintBody);

        IdempotentRequest existing = idempotency.acquire(requestId, operator, OPERATION, fingerprint);
        if (existing != null) {
            return idempotency.replay(existing, ThresholdResponse.class);
        }

        certificates.lockInstrument(instrumentId);
        int nextVersion = thresholds.currentVersionNumber(instrumentId) + 1;
        Instant now = clock.instant();
        long id = thresholds.insert(instrumentId, nextVersion, max, operator, now);
        ThresholdResponse response = DtoMapper.toResponse(
                new UncertaintyThreshold(id, instrumentId, nextVersion, max, operator, now));
        idempotency.complete(requestId, 201, response);
        return IdempotentResult.first(201, response);
    }

    /**
     * 查询仪器当前（版本号最大）阈值；从未发布返回 422（阈值缺失）。
     */
    @Transactional(readOnly = true)
    public ThresholdResponse current(String instrumentId) {
        String instrument = Inputs.requireText(instrumentId, "instrumentId");
        return DtoMapper.toResponse(thresholds.findCurrent(instrument)
                .orElseThrow(() -> ApiException.unprocessable(
                        "THRESHOLD_MISSING", "仪器尚未发布不确定度阈值: " + instrument)));
    }

    /**
     * 按版本行 ID 查询阈值；不存在 404。
     */
    @Transactional(readOnly = true)
    public ThresholdResponse get(long id) {
        return DtoMapper.toResponse(thresholds.findById(id)
                .orElseThrow(() -> ApiException.notFound("阈值版本不存在: " + id)));
    }
}
