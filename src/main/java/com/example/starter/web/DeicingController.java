package com.example.starter.web;

import com.example.starter.model.DeicingCompletion;
import com.example.starter.model.DeicingSlot;
import com.example.starter.model.TakeoffClearance;
import com.example.starter.model.TakeoffRejection;
import com.example.starter.service.DeicingService;
import com.example.starter.service.QueryService;
import com.example.starter.service.TakeoffService;
import com.example.starter.web.dto.ApplySlotRequest;
import com.example.starter.web.dto.CompleteSlotRequest;
import com.example.starter.web.dto.TakeoffConfirmRequest;
import com.example.starter.web.dto.VehicleTimelineEntry;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 除冰时隙与起飞确认接口。
 */
@RestController
@RequestMapping("/api/deicing")
public class DeicingController {

    private final DeicingService deicingService;
    private final TakeoffService takeoffService;
    private final QueryService queryService;

    public DeicingController(DeicingService deicingService, TakeoffService takeoffService,
            QueryService queryService) {
        this.deicingService = deicingService;
        this.takeoffService = takeoffService;
        this.queryService = queryService;
    }

    /**
     * 申请除冰时隙。
     */
    @PostMapping("/slots")
    public DeicingSlot applySlot(@Valid @RequestBody ApplySlotRequest request) {
        return deicingService.applySlot(request.requestId(), request.vehicleId(),
                request.flightId(), request.startUtc(), request.endUtc());
    }

    /**
     * 完成除冰作业。
     */
    @PostMapping("/slots/{slotId}/complete")
    public DeicingCompletion completeSlot(@PathVariable long slotId,
            @Valid @RequestBody CompleteSlotRequest request) {
        return deicingService.completeSlot(slotId, request.fluidType());
    }

    /**
     * 取消未开始的时隙。
     */
    @PostMapping("/slots/{slotId}/cancel")
    public DeicingSlot cancelSlot(@PathVariable long slotId) {
        return deicingService.cancelSlot(slotId);
    }

    /**
     * 确认航班起飞。
     */
    @PostMapping("/flights/{flightId}/takeoff-confirmations")
    public TakeoffClearance confirmTakeoff(@PathVariable long flightId,
            @Valid @RequestBody TakeoffConfirmRequest request) {
        return takeoffService.confirmTakeoff(request.requestId(), flightId);
    }

    /**
     * 查询车辆时间轴。
     */
    @GetMapping("/vehicles/{vehicleId}/timeline")
    public List<VehicleTimelineEntry> vehicleTimeline(@PathVariable long vehicleId) {
        return queryService.vehicleTimeline(vehicleId);
    }

    /**
     * 查询航班放行记录。
     */
    @GetMapping("/flights/{flightId}/clearances")
    public List<TakeoffClearance> flightClearances(@PathVariable long flightId) {
        return queryService.flightClearances(flightId);
    }

    /**
     * 查询航班起飞拒绝历史。
     */
    @GetMapping("/flights/{flightId}/rejections")
    public List<TakeoffRejection> flightRejections(@PathVariable long flightId) {
        return queryService.flightRejections(flightId);
    }
}