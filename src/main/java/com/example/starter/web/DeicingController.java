package com.example.starter.web;

import com.example.starter.domain.ClearanceStatus;
import com.example.starter.domain.DeicingCompletion;
import com.example.starter.domain.DeicingSlot;
import com.example.starter.domain.FluidType;
import com.example.starter.domain.TakeoffClearance;
import com.example.starter.service.BadRequestException;
import com.example.starter.service.DeicingService;
import com.example.starter.web.dto.ApplySlotRequest;
import com.example.starter.web.dto.ClearanceResponse;
import com.example.starter.web.dto.CompleteSlotRequest;
import com.example.starter.web.dto.CompletionResponse;
import com.example.starter.web.dto.SlotResponse;
import com.example.starter.web.dto.TakeoffConfirmRequest;
import com.example.starter.web.dto.TimelineResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 除冰时隙业务接口。 */
@RestController
@RequestMapping("/api/deicing")
public class DeicingController {

    private final DeicingService deicingService;

    public DeicingController(DeicingService deicingService) {
        this.deicingService = deicingService;
    }

    /** 申请除冰时隙；requestId同参重放返回原记录，异参或时隙重叠返回409。 */
    @PostMapping("/slots")
    public SlotResponse applySlot(@Valid @RequestBody ApplySlotRequest request) {
        DeicingSlot slot = deicingService.applySlot(request.requestId(), request.flightId(),
                request.vehicleId(), request.slotStart(), request.slotEnd());
        return SlotResponse.of(slot);
    }

    /** 完成除冰作业，记录液型与完成时刻（UTC）。 */
    @PostMapping("/slots/{slotId}/complete")
    public CompletionResponse completeSlot(@PathVariable long slotId,
            @Valid @RequestBody CompleteSlotRequest request) {
        FluidType fluidType;
        try {
            fluidType = FluidType.of(request.fluidType());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("非法液型: " + request.fluidType());
        }
        DeicingCompletion completion = deicingService.completeSlot(slotId, fluidType);
        return CompletionResponse.of(completion);
    }

    /** 取消未开始时隙并释放车辆。 */
    @PostMapping("/slots/{slotId}/cancel")
    public SlotResponse cancelSlot(@PathVariable long slotId) {
        return SlotResponse.of(deicingService.cancelSlot(slotId));
    }

    /** 起飞确认；放行返回200，拒绝（逾期/作业未完成）返回409且留痕。 */
    @PostMapping("/takeoff-confirmations")
    public ResponseEntity<ClearanceResponse> confirmTakeoff(
            @Valid @RequestBody TakeoffConfirmRequest request) {
        TakeoffClearance clearance = deicingService.confirmTakeoff(request.requestId(),
                request.flightId());
        HttpStatus status = clearance.status() == ClearanceStatus.CLEARED
                ? HttpStatus.OK : HttpStatus.CONFLICT;
        return ResponseEntity.status(status).body(ClearanceResponse.of(clearance));
    }

    /** 车辆时间轴：时隙、液型与截止时刻。 */
    @GetMapping("/vehicles/{vehicleId}/timeline")
    public TimelineResponse vehicleTimeline(@PathVariable long vehicleId) {
        return TimelineResponse.of(deicingService.vehicleTimeline(vehicleId));
    }

    /** 航班放行记录（含拒绝历史）。 */
    @GetMapping("/flights/{flightId}/clearances")
    public List<ClearanceResponse> flightClearances(@PathVariable long flightId) {
        return deicingService.flightClearances(flightId).stream()
                .map(ClearanceResponse::of).toList();
    }
}