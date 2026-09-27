package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.api.dto.BudgetResponse;
import com.example.starter.calibration.api.dto.BudgetRevisionResponse;
import com.example.starter.calibration.api.dto.ComponentInput;
import com.example.starter.calibration.api.dto.UpdateComponentsRequest;
import com.example.starter.calibration.model.InstrumentThreshold;
import com.example.starter.calibration.model.Measurement;
import com.example.starter.calibration.model.MeasurementStatus;
import com.example.starter.calibration.model.UncertaintyComponent;
import com.example.starter.calibration.repo.BudgetRevisionRepository;
import com.example.starter.calibration.repo.ComponentRepository;
import com.example.starter.calibration.repo.ComponentRepository.ComponentValue;
import com.example.starter.calibration.repo.MeasurementRepository;
import com.example.starter.calibration.repo.ThresholdRepository;

/**
 * 不确定度预算服务：分量批量修改（整体替换、版本校验、幂等）、预算明细与诊断、修订历史。
 * 分量修改与放行通过测量行锁按事务提交顺序裁决；放行在同一事务内读取一致预算。
 */
@Service
public class BudgetService {

    /** 非负十进制：整数最多 30 位，小数最多 6 位。 */
    private static final Pattern NON_NEGATIVE_DECIMAL = Pattern.compile("^\\d{1,30}(\\.\\d{1,6})?$");

    private final MeasurementRepository measurements;
    private final ComponentRepository components;
    private final BudgetRevisionRepository revisions;
    private final ThresholdRepository thresholds;
    private final IdempotencyExecutor idempotency;

    public BudgetService(MeasurementRepository measurements, ComponentRepository components,
                         BudgetRevisionRepository revisions, ThresholdRepository thresholds,
                         IdempotencyExecutor idempotency) {
        this.measurements = measurements;
        this.components = components;
        this.revisions = revisions;
        this.thresholds = thresholds;
        this.idempotency = idempotency;
    }

    /**
     * 批量修改分量：先校验完整预算（名称重复 422、数值非法 422），再校验测量版本（410），
     * 最后在一个事务内整体替换并固化修订历史；任一失败不改变放行状态与当前分量。
     * requestId 同参重放返回首次快照，异参 409，失败不占键。
     */
    public BudgetResponse updateComponents(String key, UpdateComponentsRequest request, String actor) {
        String changer = Inputs.requireText(actor, "X-Actor-Id");
        String requestId = Inputs.requireText(request == null ? null : request.requestId(), "requestId");
        if (request.expectedVersion() == null) {
            throw ApiException.badRequest("expectedVersion 不能为空");
        }
        if (request.components() == null) {
            throw ApiException.badRequest("components 不能为空");
        }
        List<ComponentValue> parsed = parseComponents(request.components());

        List<String> tokens = new ArrayList<>(List.of(
                "UPDATE_COMPONENTS", changer, key, String.valueOf(request.expectedVersion())));
        for (ComponentValue component : parsed) {
            tokens.add(component.name());
            tokens.add(component.value().toPlainString());
        }
        String fingerprint = Fingerprint.of(tokens);

        return idempotency.idempotent(requestId, "UPDATE_COMPONENTS", changer, fingerprint,
                BudgetResponse.class,
                () -> doUpdate(key, request.expectedVersion(), parsed, changer, requestId));
    }

    /**
     * 预算明细与诊断：当前分量、合成不确定度（实际值）、当前阈值（要求值）、差额与阻碍原因。
     */
    @Transactional(readOnly = true)
    public BudgetResponse budget(String key) {
        Measurement measurement = measurements.findByKey(key)
                .orElseThrow(() -> ApiException.notFound("测量不存在: " + key));
        return toBudget(measurement);
    }

    /**
     * 预算修订历史：每次分量批量修改固化一条，按版本升序。
     */
    @Transactional(readOnly = true)
    public List<BudgetRevisionResponse> history(String key) {
        Measurement measurement = measurements.findByKey(key)
                .orElseThrow(() -> ApiException.notFound("测量不存在: " + key));
        return revisions.findByMeasurementId(measurement.id()).stream()
                .map(DtoMapper::toResponse)
                .toList();
    }

    private BudgetResponse doUpdate(String key, int expectedVersion, List<ComponentValue> parsed,
                                    String changer, String requestId) {
        Measurement measurement = measurements.findByKeyForUpdate(key)
                .orElseThrow(() -> ApiException.notFound("测量不存在: " + key));
        if (measurement.version() != expectedVersion) {
            throw ApiException.gone("VERSION_EXPIRED",
                    "测量版本已过期: 要求版本=" + expectedVersion + ", 实际版本=" + measurement.version());
        }
        if (measurement.status() == MeasurementStatus.RELEASED) {
            throw ApiException.conflict("COMPONENTS_FROZEN", "测量已放行，分量集合已固化: " + key);
        }
        Instant now = Instant.now();
        components.replaceAll(measurement.id(), parsed, now);
        measurements.incrementVersion(measurement.id());
        int newVersion = measurement.version() + 1;
        BigDecimal combined = UncertaintyCalculator.combine(parsed);
        revisions.insert(measurement.id(), newVersion, combined, UncertaintyCalculator.FORMULA,
                changer, requestId, now, parsed);
        return toBudget(measurements.findByKey(key).orElseThrow());
    }

    private BudgetResponse toBudget(Measurement measurement) {
        List<UncertaintyComponent> current = components.findByMeasurementId(measurement.id());
        List<ComponentValue> values = current.stream()
                .map(c -> new ComponentValue(c.componentName(), c.componentValue()))
                .toList();
        BigDecimal combined = UncertaintyCalculator.combine(values);
        Optional<InstrumentThreshold> threshold = thresholds.findCurrent(measurement.instrumentId());
        return DtoMapper.toBudget(measurement, current, combined, threshold.orElse(null));
    }

    /**
     * 校验并规范化完整分量集合：名称空白 400；名称重复 422；数值缺失/非法/为负 422。
     * 返回按名称升序的列表（规范化对象集合，供指纹与存储使用）。
     */
    private List<ComponentValue> parseComponents(List<ComponentInput> inputs) {
        List<ComponentValue> parsed = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (ComponentInput input : inputs) {
            if (input == null) {
                throw ApiException.badRequest("components[] 不能为 null");
            }
            String name = Inputs.requireText(input.name(), "components[].name");
            if (!names.add(name)) {
                throw ApiException.unprocessable("DUPLICATE_COMPONENT_NAME", "分量名称重复: " + name);
            }
            String raw = input.value();
            if (raw == null || !NON_NEGATIVE_DECIMAL.matcher(raw.trim()).matches()) {
                throw ApiException.unprocessable("INVALID_COMPONENT_VALUE",
                        "分量数值非法（须为非负、最多 6 位小数的十进制）: " + name);
            }
            parsed.add(new ComponentValue(name, new BigDecimal(raw.trim())));
        }
        parsed.sort(Comparator.comparing(ComponentValue::name));
        return parsed;
    }
}
