package com.example.starter.baggage.api;

import com.example.starter.baggage.api.dto.ArrivalRequest;
import com.example.starter.baggage.api.dto.LegView;
import com.example.starter.baggage.api.dto.LoadRequest;
import com.example.starter.baggage.api.dto.LoadResult;
import com.example.starter.baggage.api.dto.ManifestView;
import com.example.starter.baggage.api.dto.RegisterLegRequest;
import com.example.starter.baggage.api.dto.SealRequest;
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
 * 航段接口：登记、批量装载、封舱、到达确认及航段/清单查询。
 */
@RestController
@RequestMapping("/api/legs")
public class LegController {

    private final BaggageGateway gateway;
    private final BaggageService baggageService;

    public LegController(BaggageGateway gateway, BaggageService baggageService) {
        this.gateway = gateway;
        this.baggageService = baggageService;
    }

    /**
     * 登记航段。
     */
    @PostMapping
    public LegView register(@Valid @RequestBody RegisterLegRequest req) {
        return gateway.registerLeg(req);
    }

    /**
     * 批量装载行李到航段。
     */
    @PostMapping("/{legId}/loads")
    public LoadResult load(@PathVariable String legId, @Valid @RequestBody LoadRequest req) {
        return gateway.load(legId, req);
    }

    /**
     * 封舱：固化只读装载清单并转 SEALED。
     */
    @PostMapping("/{legId}/seal")
    public ManifestView seal(@PathVariable String legId, @Valid @RequestBody SealRequest req) {
        return gateway.seal(legId, req);
    }

    /**
     * 到达确认：实际袋号集合须与封舱清单完全相同。
     */
    @PostMapping("/{legId}/arrival")
    public ManifestView arrive(@PathVariable String legId, @Valid @RequestBody ArrivalRequest req) {
        return gateway.arrive(legId, req);
    }

    /**
     * 查询航段。
     */
    @GetMapping("/{legId}")
    public LegView get(@PathVariable String legId) {
        return baggageService.getLeg(legId);
    }

    /**
     * 查询清单：OPEN 返回当前装载明细，SEALED/ARRIVED 返回只读封舱清单。
     */
    @GetMapping("/{legId}/manifest")
    public ManifestView manifest(@PathVariable String legId) {
        return baggageService.getManifest(legId);
    }
}
