package com.example.starter.baggage;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.baggage.ContainerDtos.ConfirmResealRequest;
import com.example.starter.baggage.ContainerDtos.CreateResealOrderRequest;
import com.example.starter.baggage.ContainerDtos.ResealOrderResponse;

/**
 * 容器重封单 REST 入口：创建预览、双人确认激活与证据查询。
 */
@RestController
@RequestMapping("/api/reseal-orders")
public class ResealController {

    private final ResealService resealService;

    public ResealController(ResealService resealService) {
        this.resealService = resealService;
    }

    /** 创建重封单：只预览清单差异与各行李当前扫描状态，不改变任何容器或行李。 */
    @PostMapping
    public ResponseEntity<ResealOrderResponse> createOrder(
            @Valid @RequestBody CreateResealOrderRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(resealService.createOrder(request));
    }

    /** 双人确认：操作人与复核人两名不同人员均确认后，在同一事务内原子激活。 */
    @PostMapping("/{repackKey}/confirm")
    public ResealOrderResponse confirm(@PathVariable String repackKey,
                                       @Valid @RequestBody ConfirmResealRequest request) {
        return resealService.confirm(repackKey, request);
    }

    /** 重封单证据查询：只读并稳定排序。 */
    @GetMapping("/{repackKey}")
    public ResealOrderResponse getOrder(@PathVariable String repackKey) {
        return resealService.getOrder(repackKey);
    }
}
