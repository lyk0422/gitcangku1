package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.api.dto.CreateThresholdRequest;
import com.example.starter.calibration.api.dto.ThresholdResponse;
import com.example.starter.calibration.model.UncertaintyThreshold;
import com.example.starter.calibration.repo.ThresholdRepository;

/**
 * 仪器不确定度阈值版本服务：版本只追加不改写，后续修订不影响旧放行快照。
 * 新版本 valid_from 必须严格晚于该仪器上一版本（左闭右开）。
 */
@Service
public class ThresholdService {

    private final ThresholdRepository thresholds;
    private final BusinessClock clock;

    public ThresholdService(ThresholdRepository thresholds, BusinessClock clock) {
        this.thresholds = thresholds;
        this.clock = clock;
    }

    /**
     * 追加阈值版本。对仪器最新阈值行加锁串行化修订；阈值为负或格式非法返回 422；
     * valid_from 不严格晚于上一版本返回 409。
     */
    @Transactional
    public ThresholdResponse create(CreateThresholdRequest request) {
        String instrumentId = Inputs.requireText(request.instrumentId(), "instrumentId");
        Instant validFrom = Inputs.requireInstant(request.validFrom(), "validFrom");
        BigDecimal threshold = Inputs.requireNonNegativeDecimal(request.threshold(), "threshold",
                "INVALID_THRESHOLD");

        var latestLocked = thresholds.findLatestForUpdate(instrumentId);
        if (latestLocked.isPresent()) {
            UncertaintyThreshold latest = latestLocked.get();
            if (!validFrom.isAfter(latest.validFrom())) {
                throw ApiException.conflict("THRESHOLD_VERSION_ORDER",
                        "新阈值版本生效起点必须严格晚于上一版本 " + latest.validFrom());
            }
        }
        int nextVersion = thresholds.maxVersion(instrumentId) + 1;
        long id = thresholds.insert(instrumentId, nextVersion, threshold, validFrom, clock.now());
        return DtoMapper.toResponse(thresholds.findById(id).orElseThrow());
    }

    /**
     * 版本历史：按版本号升序；查询不修改状态。
     */
    @Transactional(readOnly = true)
    public List<ThresholdResponse> list(String instrumentId) {
        String instrument = Inputs.requireText(instrumentId, "instrumentId");
        return thresholds.findByInstrument(instrument).stream()
                .map(DtoMapper::toResponse)
                .toList();
    }
}
