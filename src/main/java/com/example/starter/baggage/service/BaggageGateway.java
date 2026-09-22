package com.example.starter.baggage.service;

import com.example.starter.baggage.api.dto.ArrivalRequest;
import com.example.starter.baggage.api.dto.BagView;
import com.example.starter.baggage.api.dto.LegView;
import com.example.starter.baggage.api.dto.LoadRequest;
import com.example.starter.baggage.api.dto.LoadResult;
import com.example.starter.baggage.api.dto.ManifestView;
import com.example.starter.baggage.api.dto.RegisterBagRequest;
import com.example.starter.baggage.api.dto.RegisterLegRequest;
import com.example.starter.baggage.api.dto.SealRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

/**
 * 写操作门面：为每个写请求计算参数指纹，经 {@link IdempotencyService}
 * 在单事务内完成业务变更与去重记录的原子提交。
 */
@Service
public class BaggageGateway {

    private final BaggageService baggageService;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    public BaggageGateway(BaggageService baggageService, IdempotencyService idempotencyService,
                          ObjectMapper objectMapper) {
        this.baggageService = baggageService;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
    }

    public LegView registerLeg(RegisterLegRequest req) {
        return idempotencyService.execute(req.requestId(), fingerprint("registerLeg", req),
                LegView.class, () -> baggageService.registerLeg(req));
    }

    public BagView registerBag(RegisterBagRequest req) {
        return idempotencyService.execute(req.requestId(), fingerprint("registerBag", req),
                BagView.class, () -> baggageService.registerBag(req));
    }

    public LoadResult load(String legId, LoadRequest req) {
        return idempotencyService.execute(req.requestId(), fingerprint("load:" + legId, req),
                LoadResult.class, () -> baggageService.load(legId, req));
    }

    public ManifestView seal(String legId, SealRequest req) {
        return idempotencyService.execute(req.requestId(), fingerprint("seal:" + legId, req),
                ManifestView.class, () -> baggageService.seal(legId, req));
    }

    public ManifestView arrive(String legId, ArrivalRequest req) {
        return idempotencyService.execute(req.requestId(), fingerprint("arrive:" + legId, req),
                ManifestView.class, () -> baggageService.arrive(legId, req));
    }

    private String fingerprint(String operation, Object request) {
        try {
            return operation + "|" + objectMapper.writeValueAsString(request);
        } catch (Exception e) {
            throw new IllegalStateException("请求指纹计算失败", e);
        }
    }
}
