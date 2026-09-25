package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.api.EnvironmentOutOfRangeException;
import com.example.starter.calibration.api.dto.MeasurementResponse;
import com.example.starter.calibration.api.dto.RecalculateRequest;
import com.example.starter.calibration.api.dto.RejectRequest;
import com.example.starter.calibration.api.dto.SubmitMeasurementRequest;
import com.example.starter.calibration.model.Certificate;
import com.example.starter.calibration.model.CompensationProfile;
import com.example.starter.calibration.model.Measurement;
import com.example.starter.calibration.model.MeasurementStatus;
import com.example.starter.calibration.model.MeasurementVersion;
import com.example.starter.calibration.repo.CertificateRepository;
import com.example.starter.calibration.repo.CompensationProfileRepository;
import com.example.starter.calibration.repo.MeasurementRepository;
import com.example.starter.calibration.repo.MeasurementVersionRepository;
import com.example.starter.calibration.repo.RejectRepository;
import com.example.starter.calibration.repo.ReleaseRepository;

/**
 * 测量服务：提交（证书血缘 + 环境补偿固化）、重算（新版本链）、驳回、历史明细、当前可用查询。
 */
@Service
public class MeasurementService {

    private final MeasurementRepository measurements;
    private final CertificateRepository certificates;
    private final ReleaseRepository releases;
    private final CompensationProfileRepository profiles;
    private final MeasurementVersionRepository versions;
    private final RejectRepository rejects;
    private final IdempotentExecutor idempotency;

    public MeasurementService(MeasurementRepository measurements,
                              CertificateRepository certificates,
                              ReleaseRepository releases,
                              CompensationProfileRepository profiles,
                              MeasurementVersionRepository versions,
                              RejectRepository rejects,
                              IdempotentExecutor idempotency) {
        this.measurements = measurements;
        this.certificates = certificates;
        this.releases = releases;
        this.profiles = profiles;
        this.versions = versions;
        this.rejects = rejects;
        this.idempotency = idempotency;
    }

    /**
     * 提交测量。匹配唯一有效证书并固化证书计算值；记录环境时同时匹配当前生效补偿系数版本、
     * 校验适用区间（超出 422 并返回区间）、固化补偿后值（6 位小数）与系数版本。
     * 测量键重复返回 409（由 measurement_key 唯一约束裁决，不用 calcKey 重放）；
     * 成功提交把含测量/证书/系数版本、环境与全部输入的 calcKey 写入指纹日志；失败不占键。
     */
    @Transactional
    public MeasurementResponse submit(SubmitMeasurementRequest request) {
        ParsedInput input = ParsedInput.ofSubmit(request);

        Certificate lockedCert = certificates.findByIdForUpdate(
                        certificates.findMatching(input.instrumentId, input.measuredAt())
                                .orElseThrow(() -> ApiException.unprocessable(
                                        "测量时刻无匹配的有效证书: instrument=" + input.instrumentId()))
                                .id())
                .orElseThrow();

        CompensationSnapshot compensation = resolveCompensation(input.model, input.temperature, input.humidity,
                input.reading, input.lower, input.upper);

        String fingerprint = Fingerprints.of("SUBMIT", input.key, input.instrumentId, input.model,
                input.measuredAt, input.reading, input.lower, input.upper,
                input.temperature, input.humidity, input.uncertainty, input.submittedBy,
                lockedCert.id(), compensation == null ? null : compensation.profile.id());

        MeasurementResponse response = doInsert(input, lockedCert, compensation, fingerprint);
        idempotency.recordSuccess(fingerprint, "SUBMIT", 201, fingerprint, response);
        return response;
    }

    private MeasurementResponse doInsert(ParsedInput input, Certificate cert, CompensationSnapshot compensation,
                                         String calcKey) {
        BigDecimal computed = cert.a().multiply(input.reading).add(cert.b());
        boolean passed = computed.compareTo(input.lower) >= 0 && computed.compareTo(input.upper) <= 0;
        Long profileId = compensation == null ? null : compensation.profile.id();
        BigDecimal compensated = compensation == null ? null : compensation.compensated;
        Boolean compensatedPassed = compensation == null ? null : compensation.compensatedPassed;
        Instant now = Instant.now();

        Measurement measurement = new Measurement(
                0L, input.key, input.instrumentId, input.model, input.measuredAt,
                input.reading, input.lower, input.upper, input.submittedBy,
                cert.id(), computed, passed,
                input.temperature, input.humidity, input.uncertainty,
                profileId, compensated, compensatedPassed,
                1, MeasurementStatus.PENDING, now);
        long id;
        try {
            id = measurements.insert(measurement);
        } catch (DuplicateKeyException ex) {
            throw ApiException.conflict("DUPLICATE_MEASUREMENT_KEY", "测量键已存在: " + input.key);
        }
        Measurement persisted = new Measurement(
                id, input.key, input.instrumentId, input.model, input.measuredAt,
                input.reading, input.lower, input.upper, input.submittedBy,
                cert.id(), computed, passed,
                input.temperature, input.humidity, input.uncertainty,
                profileId, compensated, compensatedPassed,
                1, MeasurementStatus.PENDING, now);
        versions.insert(new MeasurementVersion(
                0L, id, 1, input.temperature, input.humidity, input.uncertainty,
                cert.id(), profileId, computed, compensated, passed, compensatedPassed,
                null, calcKey, now));
        return assemble(persisted);
    }

    /**
     * 对未放行测量提交重算：一个事务内按新环境/不确定度与当前生效系数版本生成新测量版本，
     * 并把状态复位为待放行，随后整批可在放行时重新评估。已放行测量返回 409，快照不改写。
     */
    @Transactional
    public MeasurementResponse recalculate(RecalculateRequest request) {
        String key = Inputs.requireText(request.measurementKey(), "measurementKey");
        BigDecimal temperature = Inputs.requireDecimal(request.temperature(), "temperature");
        BigDecimal humidity = Inputs.requireDecimal(request.humidity(), "humidity");
        BigDecimal uncertainty = Inputs.optionalDecimal(request.uncertainty(), "uncertainty");

        Measurement current = measurements.findByKeyForUpdate(key)
                .orElseThrow(() -> ApiException.notFound("测量不存在: " + key));
        if (current.status() == MeasurementStatus.RELEASED) {
            throw ApiException.conflict("ALREADY_RELEASED", "已放行测量不可重算: " + key);
        }
        String model = request.instrumentModel() == null || request.instrumentModel().isBlank()
                ? current.instrumentModel()
                : Inputs.requireText(request.instrumentModel(), "instrumentModel");

        Certificate cert = certificates.findByIdForUpdate(
                        certificates.findMatching(current.instrumentId(), current.measuredAt())
                                .orElseThrow(() -> ApiException.unprocessable(
                                        "测量时刻无匹配的有效证书: instrument=" + current.instrumentId()))
                                .id())
                .orElseThrow();
        CompensationSnapshot compensation = resolveCompensation(model, temperature, humidity,
                current.rawReading(), current.lowerLimit(), current.upperLimit());

        int nextVersionNo = current.currentVersion() + 1;
        // 指纹不含自增版本号：相同环境/系数版本/输入的重算重放首次结果，不重复追加版本；
        // 不同环境或系数版本产生不同键，生成新版本。测量行锁已串行化同测量的并发重算。
        String fingerprint = Fingerprints.of("RECALCULATE", current.id(),
                current.instrumentId(), model, current.measuredAt(), current.rawReading(),
                current.lowerLimit(), current.upperLimit(), temperature, humidity, uncertainty,
                cert.id(), compensation == null ? null : compensation.profile.id());

        IdempotentExecutor.Outcome<MeasurementResponse> outcome = idempotency.run(
                fingerprint, "RECALCULATE", fingerprint, 200, MeasurementResponse.class,
                () -> doRecalculate(current, model, temperature, humidity, uncertainty,
                        cert, compensation, nextVersionNo, fingerprint));
        return outcome.body();
    }

    private MeasurementResponse doRecalculate(Measurement current, String model, BigDecimal temperature,
                                              BigDecimal humidity, BigDecimal uncertainty, Certificate cert,
                                              CompensationSnapshot compensation, int nextVersionNo, String calcKey) {
        BigDecimal computed = cert.a().multiply(current.rawReading()).add(cert.b());
        boolean passed = computed.compareTo(current.lowerLimit()) >= 0
                && computed.compareTo(current.upperLimit()) <= 0;
        Long profileId = compensation == null ? null : compensation.profile.id();
        BigDecimal compensated = compensation == null ? null : compensation.compensated;
        Boolean compensatedPassed = compensation == null ? null : compensation.compensatedPassed;
        Instant now = Instant.now();

        List<MeasurementVersion> chain = versions.findByMeasurementId(current.id());
        long parentVersionId = chain.stream().mapToLong(MeasurementVersion::id).max().orElseThrow();

        Measurement updated = new Measurement(
                current.id(), current.measurementKey(), current.instrumentId(), model, current.measuredAt(),
                current.rawReading(), current.lowerLimit(), current.upperLimit(), current.submittedBy(),
                cert.id(), computed, passed, temperature, humidity, uncertainty,
                profileId, compensated, compensatedPassed, nextVersionNo, MeasurementStatus.PENDING,
                current.createdAt());
        measurements.applyVersion(updated);
        versions.insert(new MeasurementVersion(
                0L, current.id(), nextVersionNo, temperature, humidity, uncertainty,
                cert.id(), profileId, computed, compensated, passed, compensatedPassed,
                parentVersionId, calcKey, now));
        return assemble(updated);
    }

    /**
     * 驳回未放行测量：记录驳回历史并置为 REJECTED；已放行不可驳回（409）。
     */
    @Transactional
    public MeasurementResponse reject(String key, RejectRequest request, String actor) {
        String rejectedBy = Inputs.requireText(actor, "X-Actor-Id");
        String reason = Inputs.requireText(request == null ? null : request.reason(), "reason");

        Measurement current = measurements.findByKeyForUpdate(key)
                .orElseThrow(() -> ApiException.notFound("测量不存在: " + key));
        if (current.status() == MeasurementStatus.RELEASED) {
            throw ApiException.conflict("ALREADY_RELEASED", "已放行测量不可驳回: " + key);
        }
        if (current.status() == MeasurementStatus.REJECTED) {
            throw ApiException.conflict("ALREADY_REJECTED", "测量已驳回: " + key);
        }

        // 驳回由测量行锁 + 状态机裁决（重复驳回 409）；成功才记录指纹，失败不占键。
        String fingerprint = Fingerprints.of("REJECT", current.id(), current.currentVersion(), rejectedBy, reason);
        measurements.markRejected(current.id());
        rejects.insert(current.id(), rejectedBy, reason, Instant.now());
        MeasurementResponse response = assemble(measurements.findByKey(key).orElseThrow());
        idempotency.recordSuccess(fingerprint, "REJECT", 200, fingerprint, response);
        return response;
    }

    /**
     * 历史明细：原始值、证书计算值、补偿值、系数版本、重算链、放行与驳回历史；不存在 404。
     */
    @Transactional(readOnly = true)
    public MeasurementResponse detail(String key) {
        Measurement measurement = measurements.findByKey(key)
                .orElseThrow(() -> ApiException.notFound("测量不存在: " + key));
        return assemble(measurement);
    }

    /**
     * 当前可用结果：已放行且证书未撤销。instrumentId 为 null 时返回全部仪器。
     */
    @Transactional(readOnly = true)
    public List<MeasurementResponse> usable(String instrumentId) {
        String instrument = instrumentId == null || instrumentId.isBlank() ? null : instrumentId.trim();
        return measurements.findUsable(instrument).stream().map(this::assemble).toList();
    }

    /**
     * 解析并锁定环境补偿：温湿度须同时提供；提供环境但无型号或无生效系数版本返回 422；
     * 超出适用区间返回 422 并携带区间。返回 null 表示本次未记录环境。
     * 合格区间在提交/重算上下文不同，故区间判定由调用方完成，这里只校验系数适用范围并算补偿量。
     */
    private CompensationSnapshot resolveCompensation(String model, BigDecimal temperature, BigDecimal humidity,
                                             BigDecimal rawReading, BigDecimal lower, BigDecimal upper) {
        boolean hasTemp = temperature != null;
        boolean hasHumidity = humidity != null;
        if (hasTemp != hasHumidity) {
            throw ApiException.badRequest("记录环境时温度与湿度必须同时提供");
        }
        if (!hasTemp) {
            return null;
        }
        if (model == null) {
            throw ApiException.badRequest("记录环境时必须提供 instrumentModel");
        }
        CompensationProfile profile = profiles.findActive(model)
                .orElseThrow(() -> ApiException.unprocessable(
                        "仪器型号未配置生效的环境补偿系数版本: " + model));
        CompensationProfile locked = profiles.findByIdForUpdate(profile.id()).orElseThrow();
        if (!Compensation.withinRange(locked, temperature, humidity)) {
            throw new EnvironmentOutOfRangeException(model, locked.id(),
                    EnvironmentOutOfRangeException.Range.of(
                            locked.tempMin(), locked.tempMax(), locked.humidityMin(), locked.humidityMax()));
        }
        BigDecimal compensated = Compensation.compensate(rawReading, locked, temperature, humidity);
        boolean compensatedPassed = Compensation.withinSpec(compensated, lower, upper);
        return new CompensationSnapshot(locked, compensated, compensatedPassed);
    }

    private MeasurementResponse assemble(Measurement m) {
        boolean certRevoked = certificates.findById(m.certificateId())
                .map(Certificate::revoked).orElse(true);
        var releaseList = releases.findByMeasurementId(m.id());
        var rejectList = rejects.findByMeasurementId(m.id());
        var versionList = versions.findByMeasurementId(m.id());
        Map<Long, Integer> profileVersionById = new HashMap<>();
        if (m.compensationProfileId() != null) {
            profiles.findById(m.compensationProfileId())
                    .ifPresent(p -> profileVersionById.put(p.id(), p.versionNo()));
        }
        for (MeasurementVersion v : versionList) {
            if (v.compensationProfileId() != null && !profileVersionById.containsKey(v.compensationProfileId())) {
                profiles.findById(v.compensationProfileId())
                        .ifPresent(p -> profileVersionById.put(p.id(), p.versionNo()));
            }
        }
        return DtoMapper.toResponse(m, certRevoked, releaseList, rejectList, versionList, profileVersionById);
    }

    /** 提交入参解析结果。 */
    private record ParsedInput(
            String key, String instrumentId, String model, Instant measuredAt,
            BigDecimal reading, BigDecimal lower, BigDecimal upper,
            BigDecimal temperature, BigDecimal humidity, BigDecimal uncertainty, String submittedBy) {

        static ParsedInput ofSubmit(SubmitMeasurementRequest request) {
            String key = Inputs.requireText(request.measurementKey(), "measurementKey");
            String instrumentId = Inputs.requireText(request.instrumentId(), "instrumentId");
            Instant measuredAt = Inputs.requireInstant(request.measuredAt(), "measuredAt");
            BigDecimal reading = Inputs.requireDecimal(request.reading(), "reading");
            BigDecimal lower = Inputs.requireDecimal(request.lowerLimit(), "lowerLimit");
            BigDecimal upper = Inputs.requireDecimal(request.upperLimit(), "upperLimit");
            String submittedBy = Inputs.requireText(request.submittedBy(), "submittedBy");
            if (lower.compareTo(upper) > 0) {
                throw ApiException.badRequest("lowerLimit 不能大于 upperLimit");
            }
            String model = request.instrumentModel() == null || request.instrumentModel().isBlank()
                    ? null
                    : request.instrumentModel().trim();
            BigDecimal temperature = Inputs.optionalDecimal(request.temperature(), "temperature");
            BigDecimal humidity = Inputs.optionalDecimal(request.humidity(), "humidity");
            BigDecimal uncertainty = Inputs.optionalDecimal(request.uncertainty(), "uncertainty");
            return new ParsedInput(key, instrumentId, model, measuredAt, reading, lower, upper,
                    temperature, humidity, uncertainty, submittedBy);
        }
    }

    /** 一次环境补偿的固化快照。 */
    private record CompensationSnapshot(
            CompensationProfile profile, BigDecimal compensated, boolean compensatedPassed) {
    }
}
