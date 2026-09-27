package com.example.starter.calibration.api;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.calibration.api.dto.BudgetResponse;
import com.example.starter.calibration.api.dto.BudgetRevisionResponse;
import com.example.starter.calibration.api.dto.UpdateComponentsRequest;
import com.example.starter.calibration.service.BudgetService;

/**
 * 不确定度预算接口：分量批量修改、预算明细与诊断、修订历史。
 */
@RestController
@RequestMapping("/api/measurements/{key}")
public class BudgetController {

    private final BudgetService budgets;

    public BudgetController(BudgetService budgets) {
        this.budgets = budgets;
    }

    /**
     * 批量修改分量（整体替换，先校验完整预算再单事务提交）：200；
     * 参数非法 400；名称重复/数值非法 422；版本过期 410；已放行固化 409；
     * requestId 同参重放返回首次快照，异参 409。
     * 修改人通过 X-Actor-Id 请求头提供。
     */
    @PutMapping("/components")
    public BudgetResponse updateComponents(@PathVariable String key,
                                           @RequestBody UpdateComponentsRequest request,
                                           @RequestHeader("X-Actor-Id") String actor) {
        return budgets.updateComponents(key, request, actor);
    }

    /**
     * 预算明细与诊断：当前分量、合成不确定度（实际值）、当前阈值（要求值）、差额与阻碍原因。
     */
    @GetMapping("/budget")
    public BudgetResponse budget(@PathVariable String key) {
        return budgets.budget(key);
    }

    /**
     * 预算修订历史：每次分量批量修改固化一条，按版本升序。
     */
    @GetMapping("/budget/history")
    public List<BudgetRevisionResponse> history(@PathVariable String key) {
        return budgets.history(key);
    }
}
