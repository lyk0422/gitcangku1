package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.api.dto.BudgetReplaceRequest;
import com.example.starter.calibration.api.dto.BudgetResponse;
import com.example.starter.calibration.api.dto.ComponentRequest;
import com.example.starter.calibration.model.Measurement;
import com.example.starter.calibration.model.UncertaintyComponent;
import com.example.starter.calibration.repo.MeasurementRepository;
import com.example.starter.calibration.repo.UncertaintyComponentRepository;

/**
 * 不确定度预算服务：整批登记/替换分量预算。
 * 替换语义：先校验完整后态（名称、数值、重复性），全部通过后在一个事务内追加为新预算版本；
 * 任一校验失败则整体失败，不产生新版本、不改变放行状态。历史预算版本只增不删。
 */
@Service
public class UncertaintyBudgetService {

    /** 单次预算分量上限。 */
    static final int MAX_COMPONENTS = 100;

    private final MeasurementRepository measurements;
    private final UncertaintyComponentRepository components;
    private final IdempotencyService idempotency;

    public UncertaintyBudgetService(MeasurementRepository measurements,
                                    UncertaintyComponentRepository components,
                                    IdempotencyService idempotency) {
        this.measurements = measurements;
        this.components = components;
        this.idempotency = idempotency;
    }

    /**
     * 整批替换某测量的不确定度预算（首次登记即版本 1）。
     * 名称重复 422、数值为负 422、格式错误 400、测量不存在 404、预算版本过期 410。
     */
    @Transactional
    public BudgetResponse replace(String measurementKey, BudgetReplaceRequest request,
                                  String actor, String requestId) {
        String operator = Inputs.requireText(actor, "X-Actor-Id");
        String key = Inputs.requireText(measurementKey, "measurementKey");
        List<ValidatedComponent> validated = validate(request);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("measurementKey", key);
        params.put("expectedBudgetVersion", request.expectedBudgetVersion());
        params.put("components", validated.stream()
                .map(c -> Map.of("name", c.name(), "magnitude", c.magnitude().toPlainString()))
                .toList());
        var gate = idempotency.begin(requestId, "BUDGET:" + key, operator, "REPLACE_BUDGET", params);

        Measurement measurement = measurements.findByKeyForUpdate(key)
                .orElseThrow(() -> ApiException.notFound("测量不存在: " + key));
        long currentVersion = components.currentBudgetVersion(measurement.id());
        if (request.expectedBudgetVersion() != null
                && request.expectedBudgetVersion() != currentVersion) {
            throw ApiException.gone("BUDGET_VERSION_STALE",
                    "预算版本已过期：要求 " + request.expectedBudgetVersion() + "，当前 " + currentVersion);
        }

        long newVersion = currentVersion + 1;
        Instant now = Instant.now();
        try {
            for (ValidatedComponent c : validated) {
                components.insert(measurement.id(), newVersion, c.name(), c.magnitude(), now);
            }
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            // 并发替换算出相同新版本号，唯一约束裁决：后提交者失败回滚（不产生部分分量）。
            throw ApiException.conflict("BUDGET_REPLACE_RACE",
                    "预算并发修改冲突：预算版本已被其他事务推进，请刷新后重试");
        }
        BudgetResponse response = DtoMapper.toBudgetResponse(
                measurement, components.findByBudgetVersion(measurement.id(), newVersion),
                newVersion, now);
        idempotency.recordSuccess(gate, 200, response);
        return response;
    }

    /**
     * 查询当前预算；测量不存在 404。只读，不修改状态。
     */
    @Transactional(readOnly = true)
    public BudgetResponse get(String measurementKey) {
        String key = Inputs.requireText(measurementKey, "measurementKey");
        Measurement measurement = measurements.findByKey(key)
                .orElseThrow(() -> ApiException.notFound("测量不存在: " + key));
        long version = components.currentBudgetVersion(measurement.id());
        List<UncertaintyComponent> current = version == 0
                ? List.of()
                : components.findByBudgetVersion(measurement.id(), version);
        Instant updatedAt = current.isEmpty() ? measurement.createdAt() : current.get(0).createdAt();
        return DtoMapper.toBudgetResponse(measurement, current, version, updatedAt);
    }

    /**
     * 完整后态校验：非空、条数 1～100、名称非空且不重复、数值为非负十进制。
     * 返回按名称升序的规范化分量列表。
     */
    private List<ValidatedComponent> validate(BudgetReplaceRequest request) {
        if (request == null || request.components() == null || request.components().isEmpty()) {
            throw ApiException.badRequest("不确定度分量列表必须为 1～" + MAX_COMPONENTS + " 项");
        }
        List<ComponentRequest> raw = request.components();
        if (raw.size() > MAX_COMPONENTS) {
            throw ApiException.badRequest("不确定度分量列表必须为 1～" + MAX_COMPONENTS + " 项");
        }
        List<ValidatedComponent> parsed = new ArrayList<>();
        TreeSet<String> names = new TreeSet<>();
        int index = 0;
        for (ComponentRequest item : raw) {
            if (item == null) {
                throw ApiException.badRequest("components[" + index + "] 不能为空");
            }
            String name = Inputs.requireText(item.name(), "components[" + index + "].name");
            if (!names.add(name)) {
                throw ApiException.unprocessable("DUPLICATE_COMPONENT_NAME",
                        "不确定度分量名称重复: " + name);
            }
            BigDecimal magnitude = Inputs.requireNonNegativeDecimal(
                    item.magnitude(), "components[" + index + "].magnitude");
            parsed.add(new ValidatedComponent(name, magnitude));
            index++;
        }
        parsed.sort((a, b) -> a.name().compareTo(b.name()));
        return parsed;
    }

    /** 校验通过的规范化分量。 */
    private record ValidatedComponent(String name, BigDecimal magnitude) {
    }
}
