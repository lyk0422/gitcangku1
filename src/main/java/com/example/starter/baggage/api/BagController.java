package com.example.starter.baggage.api;

import com.example.starter.baggage.api.dto.BagView;
import com.example.starter.baggage.api.dto.RegisterBagRequest;
import com.example.starter.baggage.api.dto.TraceView;
import com.example.starter.baggage.service.BaggageGateway;
import com.example.starter.baggage.service.BaggageService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 行李接口：登记、详情及轨迹查询。
 */
@RestController
@RequestMapping("/api/bags")
public class BagController {

    private final BaggageGateway gateway;
    private final BaggageService baggageService;

    public BagController(BaggageGateway gateway, BaggageService baggageService) {
        this.gateway = gateway;
        this.baggageService = baggageService;
    }

    /**
     * 登记行李及其有序行程。
     */
    @PostMapping
    public BagView register(@Valid @RequestBody RegisterBagRequest req) {
        return gateway.registerBag(req);
    }

    /**
     * 查询行李当前状态。
     */
    @GetMapping("/{bagTag}")
    public BagView get(@PathVariable String bagTag) {
        return baggageService.getBag(bagTag);
    }

    /**
     * 查询行李轨迹。
     */
    @GetMapping("/{bagTag}/trace")
    public TraceView trace(@PathVariable String bagTag) {
        return baggageService.getTrace(bagTag);
    }
}
