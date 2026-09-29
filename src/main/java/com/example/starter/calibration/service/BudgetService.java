package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.api.dto.BudgetHistoryResponse;
import com.example.starter.calibration.api.dto.BudgetResponse;
import com.example.starter.calibration.api.dto.ComponentRequest;
import com.example.starter.calibration.api.dto.SaveBudgetRequest;
import com.example.starter.calibration.model.IdempotentRequest;
import com.example.starter.calibration.model.Measurement;
import com.example.starter.calibration.model.MeasurementStatus;
import com.example.starter.calibration.model.UncertaintyBudget;
import com.example.starter.calibration.model.UncertaintyComponent;
import com.example.starter.calibration.model.UncertaintyMethod;
import com.example.starter.calibration.model.UncertaintyThreshold;
import com.example.starter.calibration.repo.BudgetRepository;
import com.example.starter.calibration.repo.IdempotentRequestRepository;
import com.example.starter.calibration.repo.MeasurementRepository;
import com.example.starter.calibration.repo.ThresholdRepository;

/**
 * 不确定度预算服务：登记/批量修订（完整替换、只增版本）、当前明细与历史查询。
 * 修订在测量行锁内完成“先校验完整后态、再单事务提交”：
 * 名称重复/数值非法/阈值缺失 422，版本过期 410，已放行或并发冲突 409，任一失败不留部分结果。
 */
@Service
public class BudgetService {

    /** 幂等操作类型标识，参与指纹。 */
    public static final String OPERATION = "BUDGET_REVISE";

    /** 单次登记/修订允许的分量数量区间（完整预算，非空）。 */
    static final int MIN_COMPONENTS = 1;
    static final int MAX_COMPONENTS = 100;

    /** 分量名称最大长度。 */
    static final int MAX_NAME_LENGTH = 128;

    private final MeasurementRepository measurements;
    private final ThresholdRepository thresholds;
    private final BudgetRepository budgets;
    private final IdempotentRequestRepository idempotentRequests;
    private final IdempotencySupport idempotency;
    private final Clock clock;

    public BudgetService(MeasurementRepository measurements,
                         ThresholdRepository thresholds,
                         BudgetRepository budgets,
                         IdempotentRequestRepository idempotentRequests,
                         IdempotencySupport idempotency,
                         Clock clock) {
        this.measurements = measurements;
        this.thresholds = thresholds;
        this.budgets = budgets;
        this.idempotentRequests = idempotentRequests;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    /**
     * 登记首个预算或批量修订分量（完整替换语义，旧版本保留）。
     * requestId 非空时：同参重放返回首次完整响应，异参 409，失败回滚不占键。
     */
    @Transactional
    public IdempotentResult<BudgetResponse> save(String measurementKey, SaveBudgetRequest request,
                                                 String actor, String requestId) {
        String key = Inputs.requireText(measurementKey, "measurementKey");
        String operator = Inputs.requireText(actor, "X-Actor-Id");
        List<ValidatedComponent> components = validateComponents(request);

        Map<String, Object> fingerprintBody = Fingerprints.ordered();
        fingerprintBody.put("operationType", OPERATION);
        fingerprintBody.put("operator", operator);
        fingerprintBody.put("measurementKey", key);
        fingerprintBody.put("expectedVersion", request.expectedVersion());
        List<Map<String, Object>> normalized = components.stream()
                .sorted(java.util.Comparator.comparing(ValidatedComponent::name))
                .map(c -> {
                    Map<String, Object> item = Fingerprints.ordered();
                    item.put("name", c.name());
                    item.put("value", c.value().stripTrailingZeros().toPlainString());
                    return item;
                })
                .toList();
        fingerprintBody.put("components", normalized);
        String fingerprint = Fingerprints.sha256(fingerprintBody);

        IdempotentRequest existing = idempotency.acquire(requestId, operator, OPERATION, fingerprint);
        if (existing != null) {
            return idempotency.replay(existing, BudgetResponse.class);
        }

        Measurement measurement = measurements.findByKeyForUpdate(key)
                .orElseThrow(() -> ApiException.notFound("测量不存在: " + key));
        if (measurement.status() == MeasurementStatus.RELEASED) {
            throw ApiException.conflict("MEASUREMENT_RELEASED",
                    "测量已放行，证据不可回写: " + key);
        }
        if (request.expectedVersion() != null && request.expectedVersion() != measurement.version()) {
            throw ApiException.gone("VERSION_STALE",
                    "测量修订版本已过期：expected=" + request.expectedVersion()
                            + ", actual=" + measurement.version());
        }

        UncertaintyThreshold threshold = thresholds.findCurrent(measurement.instrumentId())
                .orElseThrow(() -> ApiException.unprocessable("THRESHOLD_MISSING",
                        "仪器尚未发布不确定度阈值: " + measurement.instrumentId()));

        BigDecimal combined = UncertaintyCalculator.combineToStorage(
                components.stream().map(ValidatedComponent::value).toList());
        int nextBudgetVersion = budgets.nextBudgetVersionNumber(measurement.id()) + 1;
        Instant now = clock.instant();
        UncertaintyBudget draft = new UncertaintyBudget(
                0L, measurement.id(), nextBudgetVersion, UncertaintyMethod.RSS_V1, combined,
                threshold.id(), threshold.version(), threshold.maxUncertainty(),
                operator, now, List.of());
        long budgetId = budgets.insertBudget(draft);
        for (int i = 0; i < components.size(); i++) {
            ValidatedComponent component = components.get(i);
            budgets.insertComponent(budgetId, component.name(), component.value(), i, now);
        }

        long nextMeasurementVersion = measurement.version() + 1;
        int updated = measurements.compareAndSetBudget(
                measurement.id(), measurement.version(), nextMeasurementVersion, budgetId);
        if (updated == 0) {
            // 行锁已持有仍更新失败意味着版本被锁内其他途径推进，按并发冲突拒绝整笔修订。
            throw ApiException.conflict("VERSION_CONFLICT",
                    "测量修订版本并发冲突: " + key);
        }

        UncertaintyBudget saved = new UncertaintyBudget(
                budgetId, measurement.id(), nextBudgetVersion, UncertaintyMethod.RSS_V1, combined,
                threshold.id(), threshold.version(), threshold.maxUncertainty(),
                operator, now, loadComponents(budgetId));
        BudgetResponse response = DtoMapper.toBudgetResponse(key, saved);
        idempotency.complete(requestId, 200, response);
        return IdempotentResult.first(200, response);
    }

    /**
     * 当前预算明细（含分量与 actual/required/difference 诊断）。
     * 测量不存在 404；从未登记预算 422。
     */
    @Transactional(readOnly = true)
    public BudgetResponse current(String measurementKey) {
        String key = Inputs.requireText(measurementKey, "measurementKey");
        Measurement measurement = measurements.findByKey(key)
                .orElseThrow(() -> ApiException.notFound("测量不存在: " + key));
        if (measurement.currentBudgetId() == null) {
            throw ApiException.unprocessable("BUDGET_MISSING", "测量尚未登记不确定度预算: " + key);
        }
        UncertaintyBudget budget = loadBudget(measurement.currentBudgetId());
        return DtoMapper.toBudgetResponse(key, budget);
    }

    /**
     * 预算版本历史：只增不改，按版本号稳定升序，分量按提交序号稳定升序。
     * 测量不存在 404。
     */
    @Transactional(readOnly = true)
    public BudgetHistoryResponse history(String measurementKey) {
        String key = Inputs.requireText(measurementKey, "measurementKey");
        Measurement measurement = measurements.findByKey(key)
                .orElseThrow(() -> ApiException.notFound("测量不存在: " + key));
        List<BudgetResponse> versions = budgets.findVersionsByMeasurement(measurement.id()).stream()
                .map(header -> DtoMapper.toBudgetResponse(key, loadBudget(header.id())))
                .toList();
        return new BudgetHistoryResponse(
                key, measurement.instrumentId(), measurement.version(),
                measurement.currentBudgetId(), versions);
    }

    private List<ValidatedComponent> validateComponents(SaveBudgetRequest request) {
        if (request == null || request.components() == null) {
            throw ApiException.badRequest("components 不能为空");
        }
        List<ComponentRequest> raw = request.components();
        if (raw.size() < MIN_COMPONENTS || raw.size() > MAX_COMPONENTS) {
            throw ApiException.unprocessable("INVALID_BUDGET",
                    "分量数量必须为 " + MIN_COMPONENTS + "～" + MAX_COMPONENTS + " 个");
        }
        List<ValidatedComponent> components = new ArrayList<>(raw.size());
        Set<String> names = new HashSet<>();
        for (ComponentRequest item : raw) {
            if (item == null) {
                throw ApiException.unprocessable("INVALID_COMPONENT", "分量项不能为空");
            }
            String name = item.name();
            if (name == null || name.isBlank()) {
                throw ApiException.unprocessable("INVALID_COMPONENT", "分量名称不能为空");
            }
            String trimmedName = name.trim();
            if (trimmedName.length() > MAX_NAME_LENGTH) {
                throw ApiException.unprocessable("INVALID_COMPONENT",
                        "分量名称长度不能超过 " + MAX_NAME_LENGTH);
            }
            if (!names.add(trimmedName)) {
                throw ApiException.unprocessable("DUPLICATE_COMPONENT_NAME",
                        "分量名称重复: " + trimmedName);
            }
            BigDecimal value = Inputs.requireNonNegativeDecimal(item.value(), "component.value");
            components.add(new ValidatedComponent(trimmedName, value));
        }
        return components;
    }

    private List<UncertaintyComponent> loadComponents(long budgetId) {
        return budgets.findComponents(budgetId);
    }

    private UncertaintyBudget loadBudget(long budgetId) {
        UncertaintyBudget header = budgets.findById(budgetId)
                .orElseThrow(() -> ApiException.notFound("预算版本不存在: " + budgetId));
        return new UncertaintyBudget(
                header.id(), header.measurementId(), header.budgetVersion(), header.method(),
                header.combinedUncertainty(), header.thresholdId(), header.thresholdVersion(),
                header.thresholdValue(), header.createdBy(), header.createdAt(),
                loadComponents(budgetId));
    }

    private record ValidatedComponent(String name, BigDecimal value) {
    }
}
