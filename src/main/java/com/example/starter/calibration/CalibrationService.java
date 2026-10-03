package com.example.starter.calibration;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 校准记录与复核作废业务。
 */
@Service
public class CalibrationService {

    /** 通过阈值：两个绝对偏差均不超过 0.2 度。 */
    static final BigDecimal PASS_THRESHOLD_DEG = new BigDecimal("0.2");

    /** 通过后有效期时长：24 小时。 */
    static final long VALIDITY_HOURS = 24L;

    private final CalibrationRepository calibrationRepository;
    private final BindingRepository bindingRepository;
    private final AntennaRepository antennaRepository;
    private final BindingViews bindingViews;
    private final Clock clock;

    public CalibrationService(CalibrationRepository calibrationRepository,
            BindingRepository bindingRepository,
            AntennaRepository antennaRepository,
            BindingViews bindingViews,
            Clock clock) {
        this.calibrationRepository = calibrationRepository;
        this.bindingRepository = bindingRepository;
        this.antennaRepository = antennaRepository;
        this.bindingViews = bindingViews;
        this.clock = clock;
    }

    /** 创建校准记录；两个绝对偏差均不超过0.2度判通过，通过后有效期24小时。 */
    @Transactional
    public CalibrationView createCalibration(CreateCalibrationRequest request) {
        long antennaId = antennaRepository.findIdByCode(request.antennaCode())
                .orElseThrow(() -> ApiException.unprocessable("ANTENNA_UNKNOWN",
                        "天线编号不存在: " + request.antennaCode()));
        boolean passed = request.azimuthDeviationDeg().abs().compareTo(PASS_THRESHOLD_DEG) <= 0
                && request.elevationDeviationDeg().abs().compareTo(PASS_THRESHOLD_DEG) <= 0;
        Instant validFrom = passed ? request.calibratedAt() : null;
        Instant validUntil = passed ? request.calibratedAt().plusSeconds(VALIDITY_HOURS * 3600L) : null;
        AntennaCalibration calibration = new AntennaCalibration(null, antennaId, request.calibratedAt(),
                request.azimuthDeviationDeg(), request.elevationDeviationDeg(), passed,
                validFrom, validUntil, CalibrationStatus.ACTIVE, null, null);
        long id = calibrationRepository.insert(calibration, Instant.now(clock));
        return getCalibration(id);
    }

    /** 查询单条校准及其绑定影响。 */
    @Transactional(readOnly = true)
    public CalibrationView getCalibration(long id) {
        AntennaCalibration calibration = calibrationRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("CALIBRATION_NOT_FOUND", "校准记录不存在: " + id));
        return toView(calibration);
    }

    /** 查询天线的校准链（按校准时刻升序）。 */
    @Transactional(readOnly = true)
    public List<CalibrationView> listCalibrations(String antennaCode) {
        long antennaId = antennaRepository.findIdByCode(antennaCode)
                .orElseThrow(() -> ApiException.notFound("ANTENNA_NOT_FOUND", "天线编号不存在: " + antennaCode));
        List<CalibrationView> views = new ArrayList<>();
        for (AntennaCalibration calibration
                : calibrationRepository.findByAntennaIdOrderByCalibratedAt(antennaId)) {
            views.add(toView(calibration));
        }
        return views;
    }

    /**
     * 复核作废：未开始的绑定转为待重配，已完成任务的绑定保留旧依据并标记作废。
     */
    @Transactional
    public InvalidateResult invalidate(long id, InvalidateRequest request) {
        AntennaCalibration calibration = calibrationRepository.findByIdForUpdate(id)
                .orElseThrow(() -> ApiException.notFound("CALIBRATION_NOT_FOUND", "校准记录不存在: " + id));
        if (calibration.status() == CalibrationStatus.INVALIDATED) {
            throw ApiException.conflict("CALIBRATION_ALREADY_INVALIDATED", "校准记录已作废: " + id);
        }
        Instant now = Instant.now(clock);
        calibrationRepository.markInvalidated(id, now, request.reason());
        bindingRepository.demoteNotStartedToPendingReassign(id, now);
        bindingRepository.flagRemainingBoundAsInvalidated(id);
        CalibrationView view = getCalibration(id);
        return new InvalidateResult(view, view.bindings());
    }

    private CalibrationView toView(AntennaCalibration calibration) {
        String antennaCode = antennaRepository.findCodeById(calibration.antennaId()).orElseThrow(
                () -> ApiException.conflict("DATA_INCONSISTENT",
                        "校准引用的天线不存在: " + calibration.antennaId()));
        List<BindingView> bindings =
                bindingViews.ofAll(bindingRepository.findByCalibrationId(calibration.id()));
        return ViewAssembler.toCalibrationView(calibration, antennaCode, bindings);
    }
}