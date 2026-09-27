package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.api.dto.BudgetRevisionResponse;
import com.example.starter.calibration.api.dto.ComponentContribution;
import com.example.starter.calibration.api.dto.ComponentInput;
import com.example.starter.calibration.api.dto.SetComponentsRequest;
import com.example.starter.calibration.api.dto.ThresholdRequest;
import com.example.starter.calibration.api.dto.ThresholdResponse;
import com.example.starter.calibration.api.dto.UncertaintyBudgetResponse;
import com.example.starter.calibration.api.dto.UncertaintyComponentView;
import com.example.starter.calibration.api.dto.UncertaintyDiagnosticsResponse;
import com.example.starter.calibration.model.BudgetRevision;
import com.example.starter.calibration.model.Measurement;
import com.example.starter.calibration.model.MeasurementStatus;
import com.example.starter.calibration.model.UncertaintyComponent;
import com.example.starter.calibration.model.UncertaintyThreshold;
import com.example.starter.calibration.repo.BudgetRevisionRepository;
import com.example.starter.calibration.repo.CertificateRepository;
import com.example.starter.calibration.repo.MeasurementRepository;
import com.example.starter.calibration.repo.UncertaintyComponentRepository;
import com.example.starter.calibration.repo.UncertaintyThresholdRepository;

/**
 * 不确定度预算服务：仪器阈值版本管理、测量分量批量修改（整体替换、先校验完整预算）、
 * 合成不确定度（平方和开根，4 位小数）计算、明细/历史/诊断查询。
 * 分量名称重复、数值非法、阈值缺失返回 422；测量版本过期返回 410。
 */
@Service
public class UncertaintyService {

    /** 非负十进制：整数最多 30 位，小数最多 6 位。 */
    private static final Pattern NON_NEGATIVE_DECIMAL = Pattern.compile("^\\d{1,30}(\\.\\d{1,6})?$");

    private final MeasurementRepository measurements;
    private final UncertaintyComponentRepository components;
    private final UncertaintyThresholdRepository thresholds;
    private final BudgetRevisionRepository revisions;
    private final CertificateRepository certificates;
    private final IdempotencyService idempotency;

    public UncertaintyService(MeasurementRepository measurements,
                              UncertaintyComponentRepository components,
                              UncertaintyThresholdRepository thresholds,
                              BudgetRevisionRepository revisions,
                              CertificateRepository certificates,
                              IdempotencyService idempotency) {
        this.measurements = measurements;
        this.components = components;
        this.thresholds = thresholds;
        this.revisions = revisions;
        this.certificates = certificates;
        this.idempotency = idempotency;
    }

    /**
     * 设置仪器不确定度阈值：每次成功调用生成递增新版本，旧版本不改写。
     * 数值非法返回 422；通过仪器级行锁串行化版本分配。
     */
    @Transactional
    public ThresholdResponse setThreshold(String instrumentId, ThresholdRequest request, String actor) {
        String instrument = Inputs.requireText(instrumentId, "instrumentId");
        String operator = Inputs.requireText(actor, "X-Actor-Id");
        String requestId = Inputs.requireText(request.requestId(), "requestId");
        BigDecimal maxUncertainty = requireNonNegativeDecimal(request.maxUncertainty(),
                "maxUncertainty", "INVALID_THRESHOLD_VALUE");

        List<String> fields = List.of(instrument, normalize(maxUncertainty));
        return idempotency.execute(requestId, "SET_UNCERTAINTY_THRESHOLD", operator, fields, 201, () -> {
            certificates.lockInstrument(instrument);
            int nextVersion = thresholds.findCurrent(instrument)
                    .map(UncertaintyThreshold::version).orElse(0) + 1;
            Instant now = Instant.now();
            thresholds.insert(instrument, nextVersion, maxUncertainty, operator, now);
            return new ThresholdResponse(instrument, nextVersion,
                    DtoMapper.format(maxUncertainty), operator, now);
        });
    }

    /**
     * 查询仪器当前阈值；不存在返回 404（THRESHOLD_MISSING）。
     */
    @Transactional(readOnly = true)
    public ThresholdResponse currentThreshold(String instrumentId) {
        String instrument = Inputs.requireText(instrumentId, "instrumentId");
        return thresholds.findCurrent(instrument)
                .map(this::toThresholdResponse)
                .orElseThrow(() -> ApiException.notFound("仪器未设置不确定度阈值: " + instrument));
    }

    /**
     * 查询仪器阈值版本历史（按版本升序）。
     */
    @Transactional(readOnly = true)
    public List<ThresholdResponse> thresholdHistory(String instrumentId) {
        String instrument = Inputs.requireText(instrumentId, "instrumentId");
        return thresholds.findHistory(instrument).stream().map(this::toThresholdResponse).toList();
    }

    /**
     * 批量修改不确定度分量：整体替换，先校验完整预算再原子提交；任一失败不改变放行状态。
     * 已放行测量预算已固化，返回 409；测量版本过期返回 410；阈值缺失返回 422。
     */
    @Transactional
    public UncertaintyBudgetResponse setComponents(String key, SetComponentsRequest request, String actor) {
        String measurementKey = Inputs.requireText(key, "key");
        String operator = Inputs.requireText(actor, "X-Actor-Id");
        String requestId = Inputs.requireText(request.requestId(), "requestId");
        if (request.measurementVersion() == null || request.measurementVersion() < 1) {
            throw ApiException.badRequest("measurementVersion 必须为不小于 1 的整数");
        }
        int expectedVersion = request.measurementVersion();
        List<ComponentInput> inputs = request.components();
        if (inputs == null) {
            throw ApiException.badRequest("components 不能为空（可传空数组表示清空预算）");
        }

        // 先校验完整预算：任一分量非法则整体拒绝，不触碰任何状态
        List<NormalizedComponent> normalized = normalizeComponents(inputs);

        List<String> fields = new ArrayList<>();
        fields.add(measurementKey);
        fields.add(String.valueOf(expectedVersion));
        for (NormalizedComponent component : normalized) {
            fields.add(component.name());
            fields.add(normalize(component.value()));
        }
        return idempotency.execute(requestId, "SET_UNCERTAINTY_COMPONENTS", operator, fields, 200, () ->
                applyComponents(measurementKey, expectedVersion, normalized, operator, requestId));
    }

    private UncertaintyBudgetResponse applyComponents(String measurementKey, int expectedVersion,
                                                      List<NormalizedComponent> normalized,
                                                      String operator, String requestId) {
        Measurement measurement = measurements.findByKeyForUpdate(measurementKey)
                .orElseThrow(() -> ApiException.notFound("测量不存在: " + measurementKey));
        if (measurement.status() == MeasurementStatus.RELEASED) {
            throw ApiException.conflict("MEASUREMENT_RELEASED",
                    "测量已放行，不确定度预算已固化: " + measurementKey);
        }
        if (measurement.measurementVersion() != expectedVersion) {
            throw ApiException.gone("MEASUREMENT_VERSION_STALE",
                    "测量版本已过期: 期望 " + expectedVersion
                            + "，当前 " + measurement.measurementVersion());
        }
        UncertaintyThreshold threshold = thresholds.findCurrent(measurement.instrumentId())
                .orElseThrow(() -> ApiException.unprocessable("THRESHOLD_MISSING",
                        "仪器未设置不确定度阈值: " + measurement.instrumentId()));

        components.deleteByMeasurementId(measurement.id());
        Instant now = Instant.now();
        for (NormalizedComponent component : normalized) {
            components.insert(measurement.id(), component.name(), component.value(), now);
        }
        int newVersion = expectedVersion + 1;
        measurements.updateVersion(measurement.id(), newVersion);

        BigDecimal combined = UncertaintyMath.combine(
                normalized.stream().map(NormalizedComponent::value).toList());
        List<UncertaintyComponentView> views = normalized.stream()
                .map(c -> new UncertaintyComponentView(c.name(), DtoMapper.format(c.value())))
                .toList();
        revisions.insert(measurement.id(), newVersion, combined, threshold.version(),
                ComponentSnapshots.toJson(views), operator, requestId, now);

        return new UncertaintyBudgetResponse(measurementKey, newVersion, measurement.status().name(),
                UncertaintyMath.CALC_SPEC, combined.toPlainString(),
                threshold.version(), DtoMapper.format(threshold.maxUncertainty()),
                combined.compareTo(threshold.maxUncertainty()) <= 0, views, now);
    }

    /**
     * 预算明细查询：当前分量、合成不确定度、当前阈值与是否达标；测量不存在返回 404。
     */
    @Transactional(readOnly = true)
    public UncertaintyBudgetResponse budget(String key) {
        Measurement measurement = findMeasurement(key);
        List<UncertaintyComponent> current = components.findByMeasurementId(measurement.id());
        BigDecimal combined = UncertaintyMath.combine(
                current.stream().map(UncertaintyComponent::componentValue).toList());
        UncertaintyThreshold threshold = thresholds.findCurrent(measurement.instrumentId()).orElse(null);
        Instant lastRevisionAt = revisions.findByMeasurementId(measurement.id()).stream()
                .map(BudgetRevision::createdAt).max(Comparator.naturalOrder()).orElse(null);
        return new UncertaintyBudgetResponse(measurement.measurementKey(), measurement.measurementVersion(),
                measurement.status().name(), UncertaintyMath.CALC_SPEC, combined.toPlainString(),
                threshold == null ? null : threshold.version(),
                threshold == null ? null : DtoMapper.format(threshold.maxUncertainty()),
                threshold == null ? null : combined.compareTo(threshold.maxUncertainty()) <= 0,
                current.stream()
                        .map(c -> new UncertaintyComponentView(c.componentName(),
                                DtoMapper.format(c.componentValue())))
                        .toList(),
                lastRevisionAt);
    }

    /**
     * 预算修订历史查询（按测量版本升序）；测量不存在返回 404。
     */
    @Transactional(readOnly = true)
    public List<BudgetRevisionResponse> budgetHistory(String key) {
        Measurement measurement = findMeasurement(key);
        return revisions.findByMeasurementId(measurement.id()).stream()
                .map(revision -> new BudgetRevisionResponse(
                        revision.measurementVersion(),
                        revision.combinedUncertainty().toPlainString(),
                        revision.thresholdVersion(),
                        revision.changedBy(),
                        revision.requestId(),
                        revision.createdAt(),
                        ComponentSnapshots.fromJson(revision.componentsSnapshot())))
                .toList();
    }

    /**
     * 诊断查询：返回实际值（合成不确定度）、要求值（阈值）、差额与各分量贡献度；
     * 测量不存在返回 404。
     */
    @Transactional(readOnly = true)
    public UncertaintyDiagnosticsResponse diagnostics(String key) {
        Measurement measurement = findMeasurement(key);
        List<UncertaintyComponent> current = components.findByMeasurementId(measurement.id());
        List<BigDecimal> values = current.stream().map(UncertaintyComponent::componentValue).toList();
        BigDecimal combined = UncertaintyMath.combine(values);
        BigDecimal sumOfSquares = UncertaintyMath.sumOfSquares(values);
        UncertaintyThreshold threshold = thresholds.findCurrent(measurement.instrumentId()).orElse(null);

        List<ComponentContribution> contributions = current.stream()
                .map(component -> {
                    BigDecimal squared = component.componentValue().multiply(component.componentValue());
                    String share = sumOfSquares.compareTo(BigDecimal.ZERO) == 0
                            ? "0"
                            : squared.divide(sumOfSquares, 6, java.math.RoundingMode.HALF_UP).toPlainString();
                    return new ComponentContribution(component.componentName(),
                            DtoMapper.format(component.componentValue()),
                            DtoMapper.format(squared), share);
                })
                .toList();

        return new UncertaintyDiagnosticsResponse(measurement.measurementKey(),
                measurement.measurementVersion(), UncertaintyMath.CALC_SPEC, current.size(),
                combined.toPlainString(),
                threshold == null ? null : threshold.version(),
                threshold == null ? null : DtoMapper.format(threshold.maxUncertainty()),
                threshold == null ? null
                        : DtoMapper.format(threshold.maxUncertainty().subtract(combined)),
                threshold == null ? null : combined.compareTo(threshold.maxUncertainty()) <= 0,
                contributions);
    }

    private Measurement findMeasurement(String key) {
        String measurementKey = Inputs.requireText(key, "key");
        return measurements.findByKey(measurementKey)
                .orElseThrow(() -> ApiException.notFound("测量不存在: " + measurementKey));
    }

    private ThresholdResponse toThresholdResponse(UncertaintyThreshold threshold) {
        return new ThresholdResponse(threshold.instrumentId(), threshold.version(),
                DtoMapper.format(threshold.maxUncertainty()), threshold.createdBy(), threshold.createdAt());
    }

    /**
     * 校验并规范化完整分量集合：名称空白 422、数值非法或负数 422、名称重复 422；
     * 返回按名称升序的规范化列表。
     */
    private List<NormalizedComponent> normalizeComponents(List<ComponentInput> inputs) {
        List<NormalizedComponent> normalized = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (ComponentInput input : inputs) {
            if (input == null || input.name() == null || input.name().isBlank()) {
                throw ApiException.unprocessable("INVALID_COMPONENT_NAME", "分量名称不能为空");
            }
            String name = input.name().trim();
            if (name.length() > 128) {
                throw ApiException.unprocessable("INVALID_COMPONENT_NAME", "分量名称过长（最多 128 字符）");
            }
            BigDecimal value = requireNonNegativeDecimal(input.value(),
                    "components[" + name + "]", "INVALID_COMPONENT_VALUE");
            if (!names.add(name)) {
                throw ApiException.unprocessable("DUPLICATE_COMPONENT_NAME", "分量名称重复: " + name);
            }
            normalized.add(new NormalizedComponent(name, value));
        }
        normalized.sort(Comparator.comparing(NormalizedComponent::name));
        return normalized;
    }

    private static BigDecimal requireNonNegativeDecimal(String value, String field, String code) {
        if (value == null || !NON_NEGATIVE_DECIMAL.matcher(value.trim()).matches()) {
            throw ApiException.unprocessable(code,
                    field + " 必须为非负十进制字符串（最多 6 位小数）");
        }
        return new BigDecimal(value.trim());
    }

    private static String normalize(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    /** 规范化后的分量。 */
    private record NormalizedComponent(String name, BigDecimal value) {
    }
}
