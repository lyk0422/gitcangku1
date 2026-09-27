package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.api.dto.SetThresholdRequest;
import com.example.starter.calibration.api.dto.ThresholdView;
import com.example.starter.calibration.repo.CertificateRepository;
import com.example.starter.calibration.repo.ThresholdRepository;

/**
 * 仪器不确定度阈值服务：登记（版本化、幂等）、当前阈值与历史查询。
 * 登记通过仪器级行锁串行化，保证同一仪器版本号连续递增；历史版本只增不改。
 */
@Service
public class ThresholdService {

    /** 非负十进制：整数最多 30 位，小数最多 6 位。 */
    private static final Pattern NON_NEGATIVE_DECIMAL = Pattern.compile("^\\d{1,30}(\\.\\d{1,6})?$");

    private final ThresholdRepository thresholds;
    private final CertificateRepository certificates;
    private final IdempotencyExecutor idempotency;

    public ThresholdService(ThresholdRepository thresholds, CertificateRepository certificates,
                            IdempotencyExecutor idempotency) {
        this.thresholds = thresholds;
        this.certificates = certificates;
        this.idempotency = idempotency;
    }

    /**
     * 登记新版本阈值。阈值缺失或非法返回 422；requestId 同参重放返回首次快照，异参 409，失败不占键。
     */
    public ThresholdView setThreshold(String instrumentId, SetThresholdRequest request, String actor) {
        String registrar = Inputs.requireText(actor, "X-Actor-Id");
        String instrument = Inputs.requireText(instrumentId, "instrumentId");
        String requestId = Inputs.requireText(request == null ? null : request.requestId(), "requestId");
        BigDecimal threshold = requireThreshold(request.threshold());

        String fingerprint = Fingerprint.of(List.of(
                "SET_THRESHOLD", registrar, instrument, threshold.toPlainString()));
        return idempotency.idempotent(requestId, "SET_THRESHOLD", registrar, fingerprint,
                ThresholdView.class, () -> doSet(instrument, threshold, registrar, requestId));
    }

    /**
     * 当前最新版本阈值；未登记返回 404。
     */
    @Transactional(readOnly = true)
    public ThresholdView current(String instrumentId) {
        return DtoMapper.toResponse(thresholds.findCurrent(instrumentId)
                .orElseThrow(() -> ApiException.notFound("仪器未登记不确定度阈值: " + instrumentId)));
    }

    /**
     * 阈值版本历史（按版本升序）。
     */
    @Transactional(readOnly = true)
    public List<ThresholdView> history(String instrumentId) {
        return thresholds.findHistory(instrumentId).stream()
                .map(DtoMapper::toResponse)
                .toList();
    }

    private ThresholdView doSet(String instrumentId, BigDecimal threshold,
                                String registrar, String requestId) {
        certificates.lockInstrument(instrumentId);
        int version = thresholds.currentVersion(instrumentId) + 1;
        thresholds.insert(instrumentId, threshold, version, registrar, requestId, Instant.now());
        return DtoMapper.toResponse(thresholds.findCurrent(instrumentId).orElseThrow());
    }

    /**
     * 阈值校验：缺失返回 422（THRESHOLD_MISSING）；非法或为负返回 422（INVALID_THRESHOLD）。
     */
    static BigDecimal requireThreshold(String value) {
        if (value == null || value.isBlank()) {
            throw ApiException.unprocessable("THRESHOLD_MISSING", "阈值缺失: threshold 不能为空");
        }
        if (!NON_NEGATIVE_DECIMAL.matcher(value.trim()).matches()) {
            throw ApiException.unprocessable("INVALID_THRESHOLD",
                    "阈值非法（须为非负、最多 6 位小数的十进制）: " + value);
        }
        return new BigDecimal(value.trim());
    }
}
