package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.api.BatchRejectedException;
import com.example.starter.calibration.api.ItemFailure;
import com.example.starter.calibration.api.ReleaseEnvironmentException;
import com.example.starter.calibration.api.dto.ReleaseRequest;
import com.example.starter.calibration.api.dto.ReleaseResponse;
import com.example.starter.calibration.model.Certificate;
import com.example.starter.calibration.model.CoefficientVersion;
import com.example.starter.calibration.model.Measurement;
import com.example.starter.calibration.model.MeasurementStatus;
import com.example.starter.calibration.model.ReleaseSnapshot;
import com.example.starter.calibration.repo.CertificateRepository;
import com.example.starter.calibration.repo.CoefficientVersionRepository;
import com.example.starter.calibration.repo.MeasurementRepository;
import com.example.starter.calibration.repo.ReleaseRepository;
import com.example.starter.calibration.repo.ReleaseSnapshotRepository;

/**
 * 批量放行服务：每批 1～50 条，整批原子生效。
 * 放行必须携带 requestId、环境温度与湿度：
 * 温湿度须落在每条测量所属证书允许的范围内（含端点），且放行时刻须恰好命中一个
 * 生效中的环境补偿系数版本（UTC 左闭右开），否则整批 422 且不产生任何放行记录；
 * 其他单项规则失败沿用整批 409。requestId 重放返回首次计算快照，环境读数或测量键
 * 被修改返回 409。
 */
@Service
public class ReleaseService {

    /** 单批最大条数。 */
    static final int MAX_BATCH_SIZE = 50;

    /** 最终结果保留 4 位小数。 */
    private static final int FINAL_SCALE = 4;

    private final MeasurementRepository measurements;
    private final CertificateRepository certificates;
    private final ReleaseRepository releases;
    private final CoefficientVersionRepository coefficientVersions;
    private final ReleaseSnapshotRepository snapshots;
    private final DomainClock clock;

    public ReleaseService(MeasurementRepository measurements,
                          CertificateRepository certificates,
                          ReleaseRepository releases,
                          CoefficientVersionRepository coefficientVersions,
                          ReleaseSnapshotRepository snapshots,
                          DomainClock clock) {
        this.measurements = measurements;
        this.certificates = certificates;
        this.releases = releases;
        this.coefficientVersions = coefficientVersions;
        this.snapshots = snapshots;
        this.clock = clock;
    }

    /**
     * 原子批量放行。行锁按测量键字典序获取避免死锁；系数哨兵行锁使版本创建与放行互斥，
     * 保证一条放行结果只引用一个完整的生效版本。
     */
    @Transactional
    public ReleaseResponse release(ReleaseRequest request, String actor) {
        String releaser = Inputs.requireText(actor, "X-Actor-Id");
        String requestId = Inputs.requireText(request.requestId(), "requestId");
        if (request.keys() == null || request.keys().isEmpty()
                || request.keys().size() > MAX_BATCH_SIZE) {
            throw ApiException.badRequest("批量放行条数必须为 1～" + MAX_BATCH_SIZE);
        }
        List<String> orderedKeys = request.keys().stream()
                .map(k -> Inputs.requireText(k, "keys[]")).toList();
        Set<String> distinct = new HashSet<>(orderedKeys);
        if (distinct.size() != orderedKeys.size()) {
            throw ApiException.badRequest("批量放行包含重复测量键");
        }
        BigDecimal temperature = Inputs.requireDecimal(request.temperature(), "temperature");
        BigDecimal humidity = Inputs.requireDecimal(request.humidity(), "humidity");

        // 快速重放路径：已有首次快照，直接按快照语义返回，不重新计算
        ReleaseSnapshot existing = snapshots.findByRequestId(requestId).orElse(null);
        if (existing != null) {
            return replayOrConflict(existing, releaser, temperature, humidity, orderedKeys);
        }

        // 与系数版本创建互斥：拿到哨兵锁后再次确认快照（可能等待期间首个请求已提交）
        coefficientVersions.lockCoefficientTable();
        existing = snapshots.findByRequestId(requestId).orElse(null);
        if (existing != null) {
            return replayOrConflict(existing, releaser, temperature, humidity, orderedKeys);
        }

        Instant now = clock.instant();
        List<CoefficientVersion> effective = coefficientVersions.findEffectiveAt(now);
        if (effective.isEmpty()) {
            throw new ReleaseEnvironmentException("NO_EFFECTIVE_COEFFICIENT",
                    "放行时刻没有生效中的环境补偿系数版本",
                    new ReleaseEnvironmentException.Details(
                            DtoMapper.format(temperature), DtoMapper.format(humidity),
                            List.of(), List.of()));
        }
        if (effective.size() > 1) {
            throw new ReleaseEnvironmentException("MULTIPLE_COEFFICIENT_VERSIONS",
                    "放行时刻同时命中多个环境补偿系数版本",
                    new ReleaseEnvironmentException.Details(
                            DtoMapper.format(temperature), DtoMapper.format(humidity),
                            effective.stream().map(CoefficientVersion::versionTag).toList(),
                            List.of()));
        }
        CoefficientVersion version = effective.get(0);

        List<ItemFailure> classicFailures = new ArrayList<>();
        List<ItemFailure> environmentFailures = new ArrayList<>();
        record Approved(Measurement measurement, Certificate certificate,
                        BigDecimal compensation, BigDecimal finalValue) {
        }
        List<Approved> approved = new ArrayList<>();

        for (String key : orderedKeys.stream().sorted().toList()) {
            var locked = measurements.findByKeyForUpdate(key);
            if (locked.isEmpty()) {
                classicFailures.add(new ItemFailure(key, List.of("MEASUREMENT_NOT_FOUND")));
                continue;
            }
            Measurement measurement = locked.get();
            List<String> classicReasons = new ArrayList<>();
            if (measurement.status() == MeasurementStatus.RELEASED) {
                classicReasons.add("ALREADY_RELEASED");
            } else if (measurement.status() != MeasurementStatus.PENDING) {
                classicReasons.add("NOT_PENDING");
            }
            if (!measurement.passed()) {
                classicReasons.add("NOT_PASSED");
            }
            Certificate cert = certificates.findByIdForUpdate(measurement.certificateId())
                    .orElseThrow(() -> ApiException.conflict("CERTIFICATE_MISSING",
                            "测量关联的证书不存在: " + measurement.certificateId()));
            if (cert.revoked()) {
                classicReasons.add("CERTIFICATE_REVOKED");
            }
            if (measurement.submittedBy().equals(releaser)) {
                classicReasons.add("SAME_ACTOR");
            }

            // 证书允许温湿度范围：端点恰好等于上限视为范围内
            List<String> environmentReasons = new ArrayList<>();
            if (temperature.compareTo(cert.tempMin()) < 0
                    || temperature.compareTo(cert.tempMax()) > 0) {
                environmentReasons.add("TEMPERATURE_OUT_OF_RANGE");
            }
            if (humidity.compareTo(cert.humMin()) < 0
                    || humidity.compareTo(cert.humMax()) > 0) {
                environmentReasons.add("HUMIDITY_OUT_OF_RANGE");
            }

            if (!environmentReasons.isEmpty()) {
                environmentFailures.add(new ItemFailure(key, environmentReasons));
            } else if (classicReasons.isEmpty()) {
                BigDecimal compensation = temperature.subtract(version.referenceTemperature())
                        .multiply(version.temperatureCoefficient())
                        .add(humidity.subtract(version.referenceHumidity())
                                .multiply(version.humidityCoefficient()));
                BigDecimal finalValue = measurement.computedValue().add(compensation)
                        .setScale(FINAL_SCALE, RoundingMode.HALF_UP);
                approved.add(new Approved(measurement, cert, compensation, finalValue));
            } else {
                classicFailures.add(new ItemFailure(key, classicReasons));
            }
        }

        // 环境问题优先按 422 返回，携带实际值与越界项；不生成任何放行记录
        if (!environmentFailures.isEmpty()) {
            throw new ReleaseEnvironmentException("ENVIRONMENT_OUT_OF_RANGE",
                    "放行环境温湿度越出证书允许范围或系数版本不可用",
                    new ReleaseEnvironmentException.Details(
                            DtoMapper.format(temperature), DtoMapper.format(humidity),
                            List.of(version.versionTag()), environmentFailures));
        }
        if (!classicFailures.isEmpty()) {
            throw new BatchRejectedException(classicFailures);
        }

        String batchId = UUID.randomUUID().toString();
        for (Approved item : approved) {
            measurements.applyRelease(item.measurement().id(), temperature, humidity,
                    version.id(), version.versionTag(),
                    item.compensation(), item.finalValue());
            releases.insert(batchId, item.measurement().id(), releaser, now);
        }

        ReleaseSnapshot snapshot = new ReleaseSnapshot(requestId, batchId, releaser,
                temperature, humidity, version.versionTag(), now, orderedKeys, now);
        try {
            snapshots.insert(snapshot);
        } catch (DuplicateKeyException ex) {
            // 并发下同 requestId 首个事务抢先提交：按重放/冲突处理，本事务回滚
            ReleaseSnapshot winner = snapshots.findByRequestId(requestId).orElseThrow();
            return replayOrConflict(winner, releaser, temperature, humidity, orderedKeys);
        }
        return toResponse(snapshot, false);
    }

    /**
     * 重放语义：requestId、放行人、温湿度与测量键完全一致时返回首次快照；
     * 环境读数或测量键被修改返回 409。
     */
    private ReleaseResponse replayOrConflict(ReleaseSnapshot snapshot, String releaser,
                                             BigDecimal temperature, BigDecimal humidity,
                                             List<String> keys) {
        boolean sameEnvironment = temperature.compareTo(snapshot.temperature()) == 0
                && humidity.compareTo(snapshot.humidity()) == 0;
        boolean sameKeys = keys.equals(snapshot.keys());
        boolean sameActor = releaser.equals(snapshot.releasedBy());
        if (!sameEnvironment || !sameKeys || !sameActor) {
            throw ApiException.conflict("REPLAY_CONFLICT",
                    "requestId 已用于不同的放行请求：requestId=" + snapshot.requestId()
                            + "，首次温度=" + DtoMapper.format(snapshot.temperature())
                            + "，首次湿度=" + DtoMapper.format(snapshot.humidity())
                            + "，本次温度=" + DtoMapper.format(temperature)
                            + "，本次湿度=" + DtoMapper.format(humidity));
        }
        return toResponse(snapshot, true);
    }

    private static ReleaseResponse toResponse(ReleaseSnapshot snapshot, boolean replayed) {
        return new ReleaseResponse(
                snapshot.requestId(),
                snapshot.batchId(),
                snapshot.releasedBy(),
                DtoMapper.format(snapshot.temperature()),
                DtoMapper.format(snapshot.humidity()),
                snapshot.coefficientVersionTag(),
                snapshot.releasedAt(),
                snapshot.keys(),
                replayed);
    }
}
