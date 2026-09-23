package com.example.starter.plan.web;

import com.example.starter.plan.service.SwapService;
import com.example.starter.plan.web.dto.ActivateSwapRequest;
import com.example.starter.plan.web.dto.CreateSwapRequest;
import com.example.starter.plan.web.dto.SwapResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 容量交换单 API：创建预览、激活闭环原子交换、只读交换证据查询。
 */
@RestController
@RequestMapping("/api/v1/capacity-swaps")
public class CapacitySwapController {

    private final SwapService service;

    public CapacitySwapController(SwapService service) {
        this.service = service;
    }

    /**
     * 创建交换单（仅预览，不改变任何占用）。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SwapResponse preview(@Valid @RequestBody CreateSwapRequest request) {
        return service.preview(request);
    }

    /**
     * 激活交换单：单事务整体裁决并一次性替换全部占用。
     */
    @PostMapping("/{swapKey}/activate")
    public SwapResponse activate(@PathVariable String swapKey,
                                 @Valid @RequestBody ActivateSwapRequest request) {
        return service.activate(swapKey, request.requestKey());
    }

    /**
     * 查询交换证据（只读，稳定排序）。
     */
    @GetMapping("/{swapKey}")
    public SwapResponse getSwap(@PathVariable String swapKey) {
        return service.getSwap(swapKey);
    }
}
