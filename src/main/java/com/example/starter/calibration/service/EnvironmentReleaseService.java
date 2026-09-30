package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.api.BatchRejectedException;
import com.example.starter.calibration.api.EnvironmentRejectedException;
import com.example.starter.calibration.api.ItemFailure;
import com.example.starter.calibration.api.dto.EnvironmentItemFailure;
import com.example.starter.calibration.api.dto.EnvironmentReleaseRequest;
import com.example.starter.calibration.api.dto.EnvironmentReleaseResponse;
import com.example.starter.calibration.model.Certificate;
import com.example.starter.calibration.model.CoefficientVersion;
import com.example.starter.calibration.model.Measurement;
import com.example.starter.calibration.model.MeasurementStatus;
import com.example.starter.calibration.model.ReleaseRequestSnapshot;
import com.example.starter.calibration.repo.CertificateRepository;
import com.example.starter.calibration.repo.CoefficientVersionRepository;
import com.example.starter.calibration.repo.InstrumentLockRepository;
import com.example.starter.calibration.repo.MeasurementRepository;
import com.example.starter.calibration.repo.ReleaseRepository;
import com.example.starter.calibration.repo.ReleaseRequestRepository;

/**
 * 环境补偿放行服务。
 *
 * <p>校验与计算全部在单个事务内完成：先按测量键字典序加测量行锁，再按仪器字典序加仪器锁，
 * 随后读证书行锁与“当前时刻”唯一生效的系数版本（系数更新与本流程互斥，一条结果只引用一个
 * 完整版本）。温湿度越界、系数未生效或多版本命中时整批 422 且不写任何放行记录；
 * 状态类不满足条件（已放行/不合格/证书撤销/同人操作/测量不存在）整批 409。
 *
 * <p>requestId 作为幂等键：快照指纹（放行人＋各测量键与温湿度读数的规范 SHA-256）
 * 与首次一致时重放首次计算快照；读数或放行人变化返回 409，不重新计算。
 */
@Service
public class EnvironmentReleaseService {

    /** 单批最大条数。 */
    static final int MAX_BATCH_SIZE = 50;

    private final MeasurementRepository measurements;
    private final CertificateRepository certificates;
    private final CoefficientVersionRepository coefficientVersions;
    private final ReleaseRepository releases;
    private final ReleaseRequestRepository requestSnapshots;
    private final InstrumentLockRepository locks;
    private final BusinessClock clock;

    public EnvironmentReleaseService(MeasurementRepository measurements,
                                     CertificateRepository certificates,
                                     CoefficientVersionRepository coefficientVersions,
                                     ReleaseRepository releases,
                                     ReleaseRequestRepository requestSnapshots,
                                     InstrumentLockRepository locks,
                                     BusinessClock clock) {
        this.measurements = measurements;
        this.certificates = certificates;
        this.coefficientVersions = coefficientVersions;
        this.releases = releases;
        this.requestSnapshots = requestSnapshots;
        this.locks = locks;
        this.clock = clock;
    }

    /**
     * 原子环境补偿放行。首次成功返回计算结果；同 requestId 同快照重放返回首次结果；
     * 同 requestId 改读数/放行人返回 409。
     */
    @Transactional
    public EnvironmentReleaseResponse release(EnvironmentReleaseRequest request, String actorHeader) {
        String requestId = Inputs.requireText(request == null ? null : request.requestId(), "requestId");
        String releaser = Inputs.requireText(actorHeader, "X-Actor-Id");
        List<ParsedItem> items = parseItems(request);

        // 快照行锁串行化同一 requestId 的并发请求：已有快照则直接走重放分支。
        ReleaseRequestSnapshot existing = requestSnapshots.findByIdForUpdate(requestId).orElse(null);
        if (existing != null) {
            return replay(existing, releaser, items);
        }

        Instant now = clock.now();

        // 第一阶段：按测量键字典序加测量行锁，收集测量与仪器集合。
        Map<String, Measurement> lockedMeasurements = new LinkedHashMap<>();
        Set<String> instruments = new TreeSet<>();
        for (ParsedItem item : items.stream().sorted(Comparator.comparing(ParsedItem::key)).toList()) {
            Measurement measurement = measurements.findByKeyForUpdate(item.key()).orElse(null);
            if (measurement != null) {
                lockedMeasurements.put(item.key(), measurement);
                instruments.add(measurement.instrumentId());
            }
        }

        // 第二阶段：按仪器字典序加仪器锁，使系数“闭合旧版本＋追加新版本”与本放行严格互斥。
        for (String instrumentId : instruments) {
            locks.lock(instrumentId);
        }

        // 并发同 requestId 请求会在测量行锁上串行：首单提交后，后到者在此复查到快照即按重放返回，
        // 不会把“已放行”误判为 ALREADY_RELEASED。
        existing = requestSnapshots.findByIdForUpdate(requestId).orElse(null);
        if (existing != null) {
            return replay(existing, releaser, items);
        }

        // 第三阶段：状态类校验（409）。
        List<ItemFailure> stateFailures = new ArrayList<>();
        for (ParsedItem item : items) {
            Measurement measurement = lockedMeasurements.get(item.key());
            if (measurement == null) {
                stateFailures.add(new ItemFailure(item.key(), List.of("MEASUREMENT_NOT_FOUND")));
                continue;
            }
            List<String> reasons = new ArrayList<>();
            if (measurement.status() == MeasurementStatus.RELEASED) {
                reasons.add("ALREADY_RELEASED");
            } else if (measurement.status() != MeasurementStatus.PENDING) {
                reasons.add("NOT_PENDING");
            }
            if (!measurement.passed()) {
                reasons.add("NOT_PASSED");
            }
            Certificate cert = certificates.findByIdForUpdate(measurement.certificateId()).orElse(null);
            if (cert == null) {
                reasons.add("CERTIFICATE_MISSING");
            } else if (cert.revoked()) {
                reasons.add("CERTIFICATE_REVOKED");
            }
            if (measurement.submittedBy().equals(releaser)) {
                reasons.add("SAME_ACTOR");
            }
            if (!reasons.isEmpty()) {
                stateFailures.add(new ItemFailure(item.key(), reasons));
            }
        }
        if (!stateFailures.isEmpty()) {
            throw new BatchRejectedException(stateFailures);
        }

        // 第四阶段：环境与系数版本校验（422），并对命中的唯一版本加行锁。
        List<EnvironmentItemFailure> environmentFailures = new ArrayList<>();
        Map<String, PreparedItem> prepared = new HashMap<>();
        for (ParsedItem item : items) {
            Measurement measurement = lockedMeasurements.get(item.key());
            Certificate cert = certificates.findByIdForUpdate(measurement.certificateId()).orElseThrow();
            List<String> reasons = new ArrayList<>();
            if (!cert.hasEnvironmentRange()) {
                reasons.add("CERTIFICATE_ENV_RANGE_UNDEFINED");
            } else if (!EnvironmentCompensation.withinCertificateRange(cert, item.temperature(), item.humidity())) {
                reasons.add("ENVIRONMENT_OUT_OF_RANGE");
            }
            CoefficientVersion version = null;
            int effectiveCount = coefficientVersions.countEffectiveAt(measurement.instrumentId(), now);
            if (effectiveCount == 0) {
                reasons.add("COEFFICIENT_NOT_EFFECTIVE");
            } else if (effectiveCount > 1) {
                reasons.add("MULTIPLE_COEFFICIENT_VERSIONS");
            } else {
                version = coefficientVersions.findEffectiveAt(measurement.instrumentId(), now)
                        .stream().findFirst().orElseThrow();
                coefficientVersions.findByIdForUpdate(version.id());
            }
            if (!reasons.isEmpty()) {
                environmentFailures.add(new EnvironmentItemFailure(
                        item.key(), item.temperatureInput(), item.humidityInput(), reasons));
            } else {
                BigDecimal compensation = EnvironmentCompensation.compensation(
                        item.temperature(), item.humidity(), version);
                BigDecimal finalValue = EnvironmentCompensation.finalValue(
                        measurement.computedValue(), compensation);
                prepared.put(item.key(), new PreparedItem(measurement, version, compensation, finalValue));
            }
        }
        if (!environmentFailures.isEmpty()) {
            throw new EnvironmentRejectedException(environmentFailures);
        }

        // 第五阶段：生成批次与快照并固化结果。快照先落库以仲裁并发重放；
        // 若并发首单已提交则转为重放，若并发首单回滚（快照消失）则重试一次插入。
        String batchId = UUID.randomUUID().toString();
        Instant releasedAt = now;
        String fingerprint = fingerprint(releaser, items);
        ReleaseRequestSnapshot snapshot =
                new ReleaseRequestSnapshot(requestId, batchId, releaser, fingerprint, releasedAt);

        boolean snapshotInserted = false;
        for (int attempt = 0; attempt < 2 && !snapshotInserted; attempt++) {
            try {
                requestSnapshots.insert(snapshot);
                snapshotInserted = true;
            } catch (DuplicateKeyException duplicate) {
                ReleaseRequestSnapshot concurrent = requestSnapshots.findByIdForUpdate(requestId)
                        .orElse(null);
                if (concurrent != null) {
                    // 并发首单已提交：按重放语义返回（指纹不同则 409）。
                    return replay(concurrent, releaser, items);
                }
                // 并发首单已回滚，快照随之消失，重试插入。
            }
        }
        if (!snapshotInserted) {
            throw ApiException.conflict("RELEASE_REQUEST_RACE",
                    "放行请求并发冲突，请使用同一 requestId 重放: " + requestId);
        }

        List<EnvironmentReleaseResponse.ItemResult> results = new ArrayList<>();
        for (ParsedItem item : items) {
            PreparedItem ready = prepared.get(item.key());
            measurements.applyEnvironmentRelease(ready.measurement().id(), ready.version().id(),
                    item.temperature(), item.humidity(), ready.compensation(), ready.finalValue());
            releases.insert(batchId, ready.measurement().id(), releaser, releasedAt);
            results.add(new EnvironmentReleaseResponse.ItemResult(
                    item.key(),
                    DtoMapper.format(ready.measurement().rawReading()),
                    DtoMapper.format(item.temperature()),
                    DtoMapper.format(item.humidity()),
                    DtoMapper.format(ready.compensation()),
                    ready.finalValue().toPlainString(),
                    ready.version().id()));
        }
        return new EnvironmentReleaseResponse(requestId, batchId, releaser, releasedAt, results);
    }

    /**
     * 重放：requestId 已存在。指纹一致则从已固化数据重建首次快照结果；
     * 放行人或任一环境读数被修改返回 409，不重新计算。
     */
    private EnvironmentReleaseResponse replay(ReleaseRequestSnapshot snapshot,
                                              String releaser, List<ParsedItem> items) {
        String currentFingerprint = fingerprint(releaser, items);
        if (!snapshot.fingerprint().equals(currentFingerprint)) {
            throw ApiException.conflict("RELEASE_REQUEST_MISMATCH",
                    "requestId 已用于不同的放行请求（放行人或环境读数不一致）: " + snapshot.id());
        }
        List<EnvironmentReleaseResponse.ItemResult> results = new ArrayList<>();
        for (ParsedItem item : items) {
            Measurement measurement = measurements.findByKey(item.key())
                    .orElseThrow(() -> ApiException.conflict("RELEASE_SNAPSHOT_BROKEN",
                            "放行快照引用的测量不存在: " + item.key()));
            if (measurement.status() != MeasurementStatus.RELEASED || measurement.coeffVersionId() == null
                    || measurement.finalValue() == null) {
                throw ApiException.conflict("RELEASE_SNAPSHOT_BROKEN",
                        "放行快照对应的测量未完成环境补偿放行: " + item.key());
            }
            results.add(new EnvironmentReleaseResponse.ItemResult(
                    item.key(),
                    DtoMapper.format(measurement.rawReading()),
                    DtoMapper.format(measurement.temperature()),
                    DtoMapper.format(measurement.humidity()),
                    DtoMapper.format(measurement.compensationValue()),
                    measurement.finalValue().toPlainString(),
                    measurement.coeffVersionId()));
        }
        return new EnvironmentReleaseResponse(snapshot.id(), snapshot.batchId(),
                snapshot.releasedBy(), snapshot.releasedAt(), results);
    }

    /**
     * 解析并校验请求条目：非空、1～50 条、键不重复、温湿度为合法十进制；保持请求顺序。
     */
    private List<ParsedItem> parseItems(EnvironmentReleaseRequest request) {
        List<EnvironmentReleaseRequest.Item> rawItems = request == null ? null : request.items();
        if (rawItems == null || rawItems.isEmpty() || rawItems.size() > MAX_BATCH_SIZE) {
            throw ApiException.badRequest("环境补偿放行条数必须为 1～" + MAX_BATCH_SIZE);
        }
        List<ParsedItem> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (EnvironmentReleaseRequest.Item raw : rawItems) {
            if (raw == null) {
                throw ApiException.badRequest("放行条目不能为空");
            }
            String key = Inputs.requireText(raw.measurementKey(), "measurementKey");
            if (!seen.add(key)) {
                throw ApiException.badRequest("环境补偿放行包含重复测量键: " + key);
            }
            BigDecimal temperature = Inputs.requireDecimal(raw.temperature(), "temperature");
            BigDecimal humidity = Inputs.requireDecimal(raw.humidity(), "humidity");
            items.add(new ParsedItem(key, temperature, humidity,
                    raw.temperature().trim(), raw.humidity().trim()));
        }
        return items;
    }

    /**
     * 请求指纹：放行人＋按测量键排序的（键, 温度输入, 湿度输入）规范序列做 SHA-256。
     * 温度/湿度采用输入规范化后的 BigDecimal 值，“25”与“25.0”视为同一读数。
     */
    private String fingerprint(String releaser, List<ParsedItem> items) {
        StringBuilder canonical = new StringBuilder(releaser);
        items.stream().sorted(Comparator.comparing(ParsedItem::key)).forEach(item -> {
            canonical.append('|').append(item.key())
                    .append('=').append(item.temperature().stripTrailingZeros().toPlainString())
                    .append(',').append(item.humidity().stripTrailingZeros().toPlainString());
        });
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    /**
     * 解析后的放行测项：温度/湿度已转为 BigDecimal，输入原值用于 422 回执。
     */
    private record ParsedItem(String key, BigDecimal temperature, BigDecimal humidity,
                              String temperatureInput, String humidityInput) {
    }

    /**
     * 通过全部校验、待固化的单条结果。
     */
    private record PreparedItem(Measurement measurement, CoefficientVersion version,
                                BigDecimal compensation, BigDecimal finalValue) {
    }
}
