package com.example.starter.calibration.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.calibration.api.dto.BudgetRevisionResponse;
import com.example.starter.calibration.api.dto.SetComponentsRequest;
import com.example.starter.calibration.api.dto.ThresholdRequest;
import com.example.starter.calibration.api.dto.ThresholdResponse;
import com.example.starter.calibration.api.dto.UncertaintyBudgetResponse;
import com.example.starter.calibration.api.dto.UncertaintyDiagnosticsResponse;
import com.example.starter.calibration.service.UncertaintyService;

/**
 * 不确定度预算接口：仪器阈值版本管理、测量分量批量修改、预算明细/历史/诊断查询。
 * 修改类接口需要 X-Actor-Id 请求头与请求体内的 requestId（幂等键）。
 */
@RestController
@RequestMapping("/api")
public class UncertaintyController {

    private final UncertaintyService uncertainty;

    public UncertaintyController(UncertaintyService uncertainty) {
        this.uncertainty = uncertainty;
    }

    /**
     * 设置仪器不确定度阈值（生成新版本）：201；数值非法 422；requestId 异参 409。
     */
    @PutMapping("/instruments/{instrumentId}/uncertainty-threshold")
    public ResponseEntity<ThresholdResponse> setThreshold(@PathVariable String instrumentId,
                                                          @RequestBody ThresholdRequest request,
                                                          @RequestHeader("X-Actor-Id") String actor) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(uncertainty.setThreshold(instrumentId, request, actor));
    }

    /**
     * 查询仪器当前阈值：200；未设置 404。
     */
    @GetMapping("/instruments/{instrumentId}/uncertainty-threshold")
    public ThresholdResponse currentThreshold(@PathVariable String instrumentId) {
        return uncertainty.currentThreshold(instrumentId);
    }

    /**
     * 查询仪器阈值版本历史（按版本升序）。
     */
    @GetMapping("/instruments/{instrumentId}/uncertainty-threshold/history")
    public List<ThresholdResponse> thresholdHistory(@PathVariable String instrumentId) {
        return uncertainty.thresholdHistory(instrumentId);
    }

    /**
     * 批量修改测量不确定度分量（整体替换）：200；分量名称重复/数值非法/阈值缺失 422；
     * 测量版本过期 410；已放行 409；requestId 异参 409。
     */
    @PutMapping("/measurements/{key}/uncertainty-components")
    public UncertaintyBudgetResponse setComponents(@PathVariable String key,
                                                   @RequestBody SetComponentsRequest request,
                                                   @RequestHeader("X-Actor-Id") String actor) {
        return uncertainty.setComponents(key, request, actor);
    }

    /**
     * 预算明细：当前分量、合成不确定度（4 位小数）、当前阈值与是否达标；测量不存在 404。
     */
    @GetMapping("/measurements/{key}/uncertainty")
    public UncertaintyBudgetResponse budget(@PathVariable String key) {
        return uncertainty.budget(key);
    }

    /**
     * 预算修订历史（按测量版本升序）；测量不存在 404。
     */
    @GetMapping("/measurements/{key}/uncertainty/history")
    public List<BudgetRevisionResponse> budgetHistory(@PathVariable String key) {
        return uncertainty.budgetHistory(key);
    }

    /**
     * 诊断查询：实际值、要求值、差额与各分量贡献度；测量不存在 404。
     */
    @GetMapping("/measurements/{key}/uncertainty/diagnostics")
    public UncertaintyDiagnosticsResponse diagnostics(@PathVariable String key) {
        return uncertainty.diagnostics(key);
    }
}
