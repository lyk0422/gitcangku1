package com.example.starter.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import com.example.starter.domain.ApiException;
import com.example.starter.domain.CalibrationRecord;
import com.example.starter.repository.AntennaRepository;
import com.example.starter.repository.BindingRepository;
import com.example.starter.repository.BindingRepository.BindingWithTask;
import com.example.starter.repository.CalibrationRepository;
import com.example.starter.web.dto.CreateCalibrationRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 校准服务：登记校准、计算通过与有效期、复核作废并计算对绑定的影响。
 */
@Service
public class CalibrationService {

    /** 通过阈值：两个绝对偏差均不超过 0.2 度。 */
    public static final BigDecimal PASS_THRESHOLD = new BigDecimal("0.2");

    /** 通过后有效期：24 小时。 */
    public static final long VALIDITY_HOURS = 24;

    private final CalibrationRepository calibrationRepository;
    private final AntennaRepository antennaRepository;
    private final BindingRepository bindingRepository;
    private final Clock clock;

    public CalibrationService(CalibrationRepository calibrationRepository,
            AntennaRepository antennaRepository, BindingRepository bindingRepository, Clock clock) {
        this.calibrationRepository = calibrationRepository;
        this.antennaRepository = antennaRepository;
        this.bindingRepository = bindingRepository;
        this.clock = clock;
    }

    /** 登记校准：计算是否通过与有效期截止时刻，失败不延长有效期。 */
    @Transactional
    public CalibrationRecord create(CreateCalibrationRequest request) {
        long antennaId = antennaRepository.ensureExists(request.antennaCode());
        boolean passed = request.azimuthDeviation().abs().compareTo(PASS_THRESHOLD) <= 0
                && request.elevationDeviation().abs().compareTo(PASS_THRESHOLD) <= 0;
        Instant validUntil = passed
                ? request.calibratedAt().plus(VALIDITY_HOURS, ChronoUnit.HOURS)
                : null;
        long id = calibrationRepository.insert(antennaId, request.calibratedAt(),
                request.azimuthDeviation(), request.elevationDeviation(), passed, validUntil,
                Instant.now(clock));
        return calibrationRepository.findById(id).orElseThrow();
    }

    /**
     * 复核作废：与绑定共用天线行锁，按已提交状态裁决竞争。
     * 未开始的绑定转为待重配；已完成或进行中的任务保留旧依据并置作废标记。
     */
    @Transactional
    public VoidImpact voidCalibration(long calibrationId, String reason) {
        CalibrationRecord record = calibrationRepository.findById(calibrationId)
                .orElseThrow(() -> ApiException.notFound("CALIBRATION_NOT_FOUND",
                        "校准记录不存在: " + calibrationId));
        antennaRepository.lockById(record.antennaId());
        Instant now = Instant.now(clock);
        int updated = calibrationRepository.markVoided(calibrationId, now, reason);
        if (updated == 0) {
            throw ApiException.conflict("CALIBRATION_ALREADY_VOIDED",
                    "校准记录已作废: " + calibrationId);
        }
        List<VoidImpact.BindingEffect> effects = new ArrayList<>();
        for (BindingWithTask item : bindingRepository.findByCalibrationId(calibrationId)) {
            if (item.binding().status() == com.example.starter.domain.BindingStatus.PENDING_REASSIGN) {
                effects.add(new VoidImpact.BindingEffect(item.binding().id(), item.binding().taskId(),
                        item.taskCode(), "ALREADY_PENDING_REASSIGN"));
                continue;
            }
            if (!item.taskStart().isAfter(now)) {
                bindingRepository.markVoidedBasis(item.binding().id(), now);
                effects.add(new VoidImpact.BindingEffect(item.binding().id(), item.binding().taskId(),
                        item.taskCode(), "RETAINED_VOIDED_BASIS"));
            } else {
                bindingRepository.markPendingReassign(item.binding().id(), now);
                effects.add(new VoidImpact.BindingEffect(item.binding().id(), item.binding().taskId(),
                        item.taskCode(), "PENDING_REASSIGN"));
            }
        }
        return new VoidImpact(calibrationId, now, effects);
    }

    /** 查询单条校准记录。 */
    @Transactional(readOnly = true)
    public CalibrationRecord get(long calibrationId) {
        return calibrationRepository.findById(calibrationId)
                .orElseThrow(() -> ApiException.notFound("CALIBRATION_NOT_FOUND",
                        "校准记录不存在: " + calibrationId));
    }

    /** 查询天线校准链：按校准时刻升序，含每条校准的绑定版本与作废影响。 */
    @Transactional(readOnly = true)
    public CalibrationChain chain(String antennaCode) {
        long antennaId = antennaRepository.findIdByCode(antennaCode)
                .orElseThrow(() -> ApiException.notFound("ANTENNA_NOT_FOUND",
                        "天线不存在: " + antennaCode));
        List<CalibrationChain.ChainEntry> entries = new ArrayList<>();
        for (CalibrationRecord record : calibrationRepository.findByAntennaId(antennaId)) {
            List<BindingWithTask> bindings = bindingRepository.findByCalibrationId(record.id());
            entries.add(new CalibrationChain.ChainEntry(record, bindings));
        }
        return new CalibrationChain(antennaCode, entries);
    }

    /** 作废结果：作废时刻与每条绑定受到的影响。 */
    public record VoidImpact(long calibrationId, Instant voidedAt,
            List<BindingEffect> effects) {
        /** 单条绑定的作废影响：PENDING_REASSIGN 待重配 / RETAINED_VOIDED_BASIS 保留旧依据。 */
        public record BindingEffect(long bindingId, long taskId, String taskCode, String effect) {
        }
    }

    /** 校准链视图：一台天线的全部校准及其绑定。 */
    public record CalibrationChain(String antennaCode, List<ChainEntry> entries) {
        /** 校准链节点：校准记录与其固化绑定列表。 */
        public record ChainEntry(CalibrationRecord calibration, List<BindingWithTask> bindings) {
        }
    }
}