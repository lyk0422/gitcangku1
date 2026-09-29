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
import com.example.starter.calibration.api.dto.BudgetResponse;
import com.example.starter.calibration.api.dto.ComponentRequest;
import com.example.starter.calibration.api.dto.UpdateBudgetRequest;
import com.example.starter.calibration.model.Measurement;
import com.example.starter.calibration.model.MeasurementStatus;
import com.example.starter.calibration.model.UncertaintyBudget;
import com.example.starter.calibration.model.UncertaintyComponent;
import com.example.starter.calibration.repo.MeasurementRepository;
import com.example.starter.calibration.repo.UncertaintyBudgetRepository;
import com.example.starter.calibration.repo.UncertaintyComponentRepository;

/**
 * 不确定度预算服务：登记/整体替换某测量当前版本的分量预算。
 *
 * <p>每次修改先对完整后态校验（名称非空不重复、数值非负合法），任一失败整体拒绝（422/410），
 * 不写入任何新版本、不改变放行状态；通过后在一个事务内追加预算版本头与整组分量明细。
 * 预算版本只增不改；放行读取一致预算（同一预算版本）。
 */
@Service
public class BudgetService {

    /** 单预算分量条数上限，防止滥用。 */
    static final int MAX_COMPONENTS = 200;

    private final MeasurementRepository measurements;
    private final UncertaintyBudgetRepository budgets;
    private final UncertaintyComponentRepository components;
    private final BusinessClock clock;

    public BudgetService(MeasurementRepository measurements,
                         UncertaintyBudgetRepository budgets,
                         UncertaintyComponentRepository components,
                         BusinessClock clock) {
        this.measurements = measurements;
        this.budgets = budgets;
        this.components = components;
        this.clock = clock;
    }

    /**
     * 查询当前预算（读操作，不改状态）。不存在测量返回 404。
     */
    @Transactional(readOnly = true)
    public BudgetResponse get(String measurementKey) {
        Measurement latest = measurements.findLatestByKey(Inputs.requireText(measurementKey, "measurementKey"))
                .orElseThrow(() -> ApiException.notFound("测量不存在: " + measurementKey));
        return toResponse(latest);
    }

    /**
     * 整体替换预算（核心写操作，须由外层幂等执行器包裹）。
     *
     * <p>对测量最新版本行加行锁串行化预算修改与放行；expectedVersion 与当前最新测量版本不一致返回 410。
     */
    @Transactional
    public BudgetResponse replace(UpdateBudgetRequest request, String actor) {
        String key = Inputs.requireText(request.measurementKey(), "measurementKey");
        String operator = Inputs.requireText(actor, "X-Actor-Id");
        Integer expectedVersion = request.expectedVersion();
        List<ComponentRequest> requested = request.components();
        if (requested == null) {
            throw ApiException.badRequest("components 不能为空（空预算请传 []）");
        }
        if (requested.size() > MAX_COMPONENTS) {
            throw ApiException.badRequest("分量条数不能超过 " + MAX_COMPONENTS);
        }

        // 完整后态校验：先全部校验，任一失败不写库
        List<ParsedComponent> parsed = validateCompleteBudget(requested);

        // 锁测量最新版本行，串行化预算修改/测量修订/放行
        Measurement latest = measurements.findLatestByKeyForUpdate(key)
                .orElseThrow(() -> ApiException.notFound("测量不存在: " + key));
        if (latest.status() == MeasurementStatus.SUPERSEDED) {
            // 理论上最新版本不会是 SUPERSEDED，防御性处理
            throw ApiException.gone("STALE_MEASUREMENT_VERSION", "测量版本已过期: " + key);
        }
        if (expectedVersion != null && latest.version() != expectedVersion) {
            throw ApiException.gone("STALE_MEASUREMENT_VERSION",
                    "测量版本已过期：期望基准版本 " + expectedVersion + "，当前最新版本 " + latest.version());
        }

        int nextBudgetVersion = budgets.maxBudgetVersion(latest.id()) + 1;
        Instant now = clock.now();
        budgets.insert(latest.id(), nextBudgetVersion, operator, now);
        for (ParsedComponent component : parsed) {
            components.insert(latest.id(), nextBudgetVersion, component.name(), component.magnitude(), now);
        }
        return toResponse(latest);
    }

    /**
     * 完整后态校验：名称 trim 后非空、不区分大小写以外的精确重复（按 trim 后名称判重）、
     * 数值为非负十进制。先收集所有错误不必要——题干要求任一失败即拒绝，返回首个非法项的 422。
     * 为提供可诊断信息，按输入顺序校验名称与数值。
     */
    private List<ParsedComponent> validateCompleteBudget(List<ComponentRequest> requested) {
        List<ParsedComponent> parsed = new ArrayList<>();
        TreeSet<String> seenNames = new TreeSet<>();
        for (int i = 0; i < requested.size(); i++) {
            ComponentRequest item = requested.get(i);
            if (item == null) {
                throw ApiException.unprocessable("INVALID_COMPONENT",
                        "第 " + (i + 1) + " 个分量不能为空");
            }
            String name = item.name();
            if (name == null || name.isBlank()) {
                throw ApiException.unprocessable("INVALID_COMPONENT",
                        "第 " + (i + 1) + " 个分量名称不能为空");
            }
            String trimmedName = name.trim();
            if (!seenNames.add(trimmedName)) {
                throw ApiException.unprocessable("DUPLICATE_COMPONENT_NAME",
                        "分量名称重复: " + trimmedName);
            }
            BigDecimal magnitude = Inputs.requireNonNegativeDecimal(item.magnitude(),
                    "第 " + (i + 1) + " 个分量(" + trimmedName + ")大小", "INVALID_COMPONENT");
            parsed.add(new ParsedComponent(trimmedName, magnitude));
        }
        // 按名称升序规范化存储顺序，保证放行读取一致预算与稳定排序
        parsed.sort((a, b) -> a.name().compareTo(b.name()));
        return parsed;
    }

    private BudgetResponse toResponse(Measurement measurement) {
        int budgetVersion = budgets.maxBudgetVersion(measurement.id());
        if (budgetVersion == 0) {
            return DtoMapper.toBudgetResponse(measurement, 0, null, List.of());
        }
        UncertaintyBudget header = budgets.find(measurement.id(), budgetVersion).orElseThrow();
        List<UncertaintyComponent> list =
                components.findByMeasurementAndVersion(measurement.id(), budgetVersion);
        return DtoMapper.toBudgetResponse(measurement, budgetVersion, header, list);
    }

    /**
     * 构造预算请求的规范化指纹字段（集合按名称排序，含全部影响结果字段）。
     */
    public static Map<String, Object> fingerprintFields(UpdateBudgetRequest request) {
        List<Map<String, String>> normalized = new ArrayList<>();
        if (request.components() != null) {
            for (ComponentRequest c : request.components()) {
                Map<String, String> entry = new LinkedHashMap<>();
                entry.put("name", c == null || c.name() == null ? "" : c.name().trim());
                entry.put("magnitude", c == null || c.magnitude() == null ? "" : c.magnitude().trim());
                normalized.add(entry);
            }
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("measurementKey", request.measurementKey() == null ? "" : request.measurementKey().trim());
        fields.put("expectedVersion", request.expectedVersion());
        fields.put("components", normalized);
        return fields;
    }

    /** 解析后的分量。 */
    private record ParsedComponent(String name, BigDecimal magnitude) {
    }
}
