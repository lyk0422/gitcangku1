package com.example.starter.calibration.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.calibration.api.dto.BudgetHistoryResponse;
import com.example.starter.calibration.api.dto.BudgetResponse;
import com.example.starter.calibration.api.dto.SaveBudgetRequest;
import com.example.starter.calibration.service.BudgetService;
import com.example.starter.calibration.service.IdempotentResult;

/**
 * 不确定度预算接口：登记/批量修订（完整替换，只增版本）、当前明细、版本历史。
 * 查询接口不修改任何状态。
 */
@RestController
@RequestMapping("/api/measurements/{measurementKey}/budget")
public class BudgetController {

    private final BudgetService budgets;

    public BudgetController(BudgetService budgets) {
        this.budgets = budgets;
    }

    /**
     * 登记首个预算或批量修订分量：200；名称重复/数值非法/阈值缺失 422；
     * expectedVersion 过期 410；测量已放行 409；测量不存在 404；
     * X-Request-Id 同参重放返回首次完整响应快照，异参 409，失败不占键。
     */
    @PutMapping
    public ResponseEntity<BudgetResponse> save(@PathVariable String measurementKey,
                                               @RequestBody SaveBudgetRequest request,
                                               @RequestHeader("X-Actor-Id") String actor,
                                               @RequestHeader(value = "X-Request-Id",
                                                       required = false) String requestId) {
        IdempotentResult<BudgetResponse> result = budgets.save(measurementKey, request, actor, requestId);
        return ResponseEntity.status(result.httpStatus()).body(result.body());
    }

    /**
     * 当前预算明细（含分量、actual/required/difference 诊断）：200；
     * 测量不存在 404；从未登记预算 422。只读。
     */
    @GetMapping
    public BudgetResponse current(@PathVariable String measurementKey) {
        return budgets.current(measurementKey);
    }

    /**
     * 预算版本历史（不可变、按版本号升序）：200；测量不存在 404。只读。
     */
    @GetMapping("/history")
    public BudgetHistoryResponse history(@PathVariable String measurementKey) {
        return budgets.history(measurementKey);
    }
}
