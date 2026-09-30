package com.example.starter.web;

import com.example.starter.service.AllocationService;
import com.example.starter.web.dto.ApplyRequest;
import com.example.starter.web.dto.CancelRequest;
import com.example.starter.web.dto.RequestView;
import com.example.starter.web.dto.TimelineView;
import org.springframework.stereotype.Component;

/**
 * 控制器与服务之间的薄适配层，便于控制器保持参数绑定简洁。
 */
@Component
public class AllocationControllerDelegate {

    private final AllocationService allocationService;

    public AllocationControllerDelegate(AllocationService allocationService) {
        this.allocationService = allocationService;
    }

    public RequestView apply(ApplyRequest request) {
        return allocationService.apply(request);
    }

    public RequestView approve(String requestNo, String requestId) {
        return allocationService.approve(requestNo, requestId);
    }

    public RequestView cancel(String requestNo, CancelRequest body) {
        return allocationService.cancel(requestNo, body.researchGroup(), body.requestId());
    }

    public RequestView getRequest(String requestNo) {
        return allocationService.getRequest(requestNo);
    }

    public TimelineView getTimeline(String instrumentId) {
        return allocationService.getTimeline(instrumentId);
    }
}
