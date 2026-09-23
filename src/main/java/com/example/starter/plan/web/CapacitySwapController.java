package com.example.starter.plan.web;

import com.example.starter.plan.service.CapacitySwapService;
import com.example.starter.plan.web.dto.PlanActionRequest;
import com.example.starter.plan.web.dto.SwapCreateRequest;
import com.example.starter.plan.web.dto.SwapResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 多计划容量占用闭环原子交换 API：创建（预览）、激活、交换证据查询。
 */
@Validated
@RestController
@RequestMapping("/api/v1/capacity-swaps")
public class CapacitySwapController {

    private final CapacitySwapService service;

    public CapacitySwapController(CapacitySwapService service) {
        this.service = service;
    }

    /**
     * 创建交换单（仅预览）：返回全部计划版本、交换前占用与按完整后态计算的冲突，不改变任何占用。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SwapResponse create(@Valid @RequestBody SwapCreateRequest request) {
        return service.createPreview(request);
    }

    /**
     * 激活交换单：单事务重读校验并整体替换全部占用，写入不可变前后快照。
     */
    @PostMapping("/{swapKey}/activate")
    public SwapResponse activate(@PathVariable String swapKey,
                                 @Valid @RequestBody PlanActionRequest request) {
        return service.activate(swapKey, request.requestKey());
    }

    /**
     * 查询交换证据（只读，稳定排序）。
     */
    @GetMapping("/{swapKey}/evidence")
    public SwapResponse evidence(@PathVariable String swapKey) {
        return service.getEvidence(swapKey);
    }
}
