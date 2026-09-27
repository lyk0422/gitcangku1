package com.example.starter.calibration.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.calibration.api.dto.BudgetReplaceRequest;
import com.example.starter.calibration.api.dto.BudgetResponse;
import com.example.starter.calibration.api.dto.MeasurementResponse;
import com.example.starter.calibration.api.dto.ReleaseRequest;
import com.example.starter.calibration.api.dto.ReleaseResponse;
import com.example.starter.calibration.api.dto.ReviseMeasurementRequest;
import com.example.starter.calibration.api.dto.SubmitMeasurementRequest;
import com.example.starter.calibration.service.MeasurementService;
import com.example.starter.calibration.service.ReleaseService;
import com.example.starter.calibration.service.UncertaintyBudgetService;

/**
 * 测量接口：提交、不确定度预算登记/替换/查询、测量修订、批量放行、历史明细、当前可用结果查询。
 */
@RestController
@RequestMapping("/api/measurements")
public class MeasurementController {

    private final MeasurementService measurements;
    private final ReleaseService releases;
    private final UncertaintyBudgetService budgets;

    public MeasurementController(MeasurementService measurements, ReleaseService releases,
                                 UncertaintyBudgetService budgets) {
        this.measurements = measurements;
        this.releases = releases;
        this.budgets = budgets;
    }

    /**
     * 提交测量：201；参数非法 400；测量键重复 409；无匹配有效证书 422。
     */
    @PostMapping
    public ResponseEntity<MeasurementResponse> submit(@RequestBody SubmitMeasurementRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(measurements.submit(request));
    }

    /**
     * 整批替换不确定度预算：200；测量不存在 404；名称重复/数值非法 422；预算版本过期 410。
     * 先校验完整后态，任一失败整体拒绝，不改变放行状态。X-Request-Id 提供幂等。
     */
    @PostMapping("/{key}/budget")
    public BudgetResponse replaceBudget(@PathVariable String key,
                                        @RequestBody BudgetReplaceRequest request,
                                        @RequestHeader("X-Actor-Id") String actor,
                                        @RequestHeader(value = "X-Request-Id", required = false)
                                        String requestId) {
        return budgets.replace(key, request, actor, requestId);
    }

    /**
     * 查询当前不确定度预算：200；测量不存在 404。只读。
     */
    @GetMapping("/{key}/budget")
    public BudgetResponse getBudget(@PathVariable String key) {
        return budgets.get(key);
    }

    /**
     * 测量修订：200；测量不存在 404；无匹配证书 422；版本过期 410。X-Request-Id 提供幂等。
     */
    @PostMapping("/{key}/revisions")
    public MeasurementResponse revise(@PathVariable String key,
                                      @RequestBody ReviseMeasurementRequest request,
                                      @RequestHeader("X-Actor-Id") String actor,
                                      @RequestHeader(value = "X-Request-Id", required = false)
                                      String requestId) {
        return measurements.revise(key, request, actor, requestId);
    }

    /**
     * 批量放行（1～50 条，原子）：200；批次非法 400；任一项不满足条件整批拒绝 409 并返回各项原因。
     * 放行人通过 X-Actor-Id 请求头提供；X-Request-Id 提供幂等（同参重放首次快照，异参 409）。
     */
    @PostMapping("/release")
    public ReleaseResponse release(@RequestBody ReleaseRequest request,
                                   @RequestHeader("X-Actor-Id") String actor,
                                   @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return releases.release(request.keys(), actor, requestId);
    }

    /**
     * 当前可用结果：已放行且证书未撤销；可按仪器过滤。只读。
     */
    @GetMapping("/usable")
    public List<MeasurementResponse> usable(@RequestParam(required = false) String instrumentId) {
        return measurements.usable(instrumentId);
    }

    /**
     * 历史明细：原始测量、计算值、显示值、不确定度预算、修订历史与放行历史；不存在 404。只读。
     */
    @GetMapping("/{key}")
    public MeasurementResponse detail(@PathVariable String key) {
        return measurements.detail(key);
    }
}
