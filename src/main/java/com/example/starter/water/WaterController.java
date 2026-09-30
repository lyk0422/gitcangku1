package com.example.starter.water;

import com.example.starter.water.dto.Dtos.AllocationResponse;
import com.example.starter.water.dto.Dtos.CapacityResponse;
import com.example.starter.water.dto.Dtos.CommandRequest;
import com.example.starter.water.dto.Dtos.CreateWindowRequest;
import com.example.starter.water.dto.Dtos.CurtailmentRequest;
import com.example.starter.water.dto.Dtos.CurtailmentResponse;
import com.example.starter.water.dto.Dtos.HistoryResponse;
import com.example.starter.water.dto.Dtos.OutageResponse;
import com.example.starter.water.dto.Dtos.PublishScheduleRequest;
import com.example.starter.water.dto.Dtos.RegisterOutageRequest;
import com.example.starter.water.dto.Dtos.ScheduleResponse;
import com.example.starter.water.dto.Dtos.SettleUsageRequest;
import com.example.starter.water.dto.Dtos.SettlementResponse;
import com.example.starter.water.dto.Dtos.SubmitAllocationRequest;
import com.example.starter.water.dto.Dtos.TransferListResponse;
import com.example.starter.water.dto.Dtos.TransferRequest;
import com.example.starter.water.dto.Dtos.TransferResponse;
import com.example.starter.water.dto.Dtos.WindowResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 灌区配水 REST 接口。所有写操作携带 commandKey 保证幂等；
 * 申请提交/取消通过 X-Actor-Id 识别操作人。
 */
@RestController
@RequestMapping("/api")
public class WaterController {

    private final WaterService service;
    private final OutageService outageService;

    public WaterController(WaterService service, OutageService outageService) {
        this.service = service;
        this.outageService = outageService;
    }

    /** 创建供水窗口。 */
    @PostMapping("/windows")
    public WindowResponse createWindow(@RequestBody CreateWindowRequest request) {
        return service.createWindow(request.commandKey(), request.windowKey(), request.channelId(),
                request.startUtc(), request.endUtc(), request.plannedVolume());
    }

    /** 提交配水申请。 */
    @PostMapping("/allocations")
    public AllocationResponse submitAllocation(@RequestBody SubmitAllocationRequest request,
                                               @RequestHeader("X-Actor-Id") String actor) {
        return service.submitAllocation(request.commandKey(), request.allocationKey(), request.windowId(),
                request.userId(), request.amount(), actor);
    }

    /** 批准配水申请。 */
    @PostMapping("/allocations/{allocationKey}/approve")
    public AllocationResponse approveAllocation(@PathVariable String allocationKey,
                                                @RequestBody CommandRequest request) {
        return service.approveAllocation(request.commandKey(), allocationKey);
    }

    /** 取消配水申请（仅申请人本人）。 */
    @PostMapping("/allocations/{allocationKey}/cancel")
    public AllocationResponse cancelAllocation(@PathVariable String allocationKey,
                                               @RequestBody CommandRequest request,
                                               @RequestHeader("X-Actor-Id") String actor) {
        return service.cancelAllocation(request.commandKey(), allocationKey, actor);
    }

    /** 创建窗口限供。 */
    @PostMapping("/windows/{windowId}/curtailment")
    public CurtailmentResponse createCurtailment(@PathVariable long windowId,
                                                 @RequestBody CurtailmentRequest request) {
        return service.createCurtailment(request.commandKey(), windowId, request.volume());
    }

    /** 取消窗口当前生效限供。 */
    @PostMapping("/windows/{windowId}/curtailment/cancel")
    public CurtailmentResponse cancelCurtailment(@PathVariable long windowId,
                                                 @RequestBody CommandRequest request) {
        return service.cancelCurtailment(request.commandKey(), windowId);
    }

    /** 同窗口额度原子转让（仅源申请人本人）。 */
    @PostMapping("/transfers")
    public TransferResponse transfer(@RequestBody TransferRequest request,
                                     @RequestHeader("X-Actor-Id") String actor) {
        return service.transferAllocation(request.commandKey(), request.transferKey(),
                request.sourceAllocationKey(), request.targetAllocationKey(), actor);
    }

    /** 查询窗口转让流水。 */
    @GetMapping("/windows/{windowId}/transfers")
    public TransferListResponse getTransfers(@PathVariable long windowId) {
        return service.getTransfers(windowId);
    }

    /** 查询窗口当前可用容量。 */
    @GetMapping("/windows/{windowId}/capacity")
    public CapacityResponse getCapacity(@PathVariable long windowId) {
        return service.getCapacity(windowId);
    }

    /** 查询窗口历史明细。 */
    @GetMapping("/windows/{windowId}/history")
    public HistoryResponse getHistory(@PathVariable long windowId) {
        return service.getHistory(windowId);
    }

    /** 发布灌渠排班（渠道取自所属配水窗口）。 */
    @PostMapping("/schedules")
    public ScheduleResponse publishSchedule(@RequestBody PublishScheduleRequest request) {
        return outageService.publishSchedule(request.commandKey(), request.scheduleKey(), request.windowId(),
                request.startUtc(), request.endUtc());
    }

    /** 查询排班：原排班、当前窗口、中断原因及历次顺延对应关系。 */
    @GetMapping("/schedules/{scheduleKey}")
    public ScheduleResponse getSchedule(@PathVariable String scheduleKey) {
        return outageService.getSchedule(scheduleKey);
    }

    /** 登记灌渠中断并整批顺延受影响且未开始的排班；requestId 即 commandKey。 */
    @PostMapping("/outages")
    public OutageResponse registerOutage(@RequestBody RegisterOutageRequest request) {
        return outageService.registerOutage(request.commandKey(), request.outageKey(), request.channelId(),
                request.startUtc(), request.endUtc(), request.reason(), request.postponeMinutes());
    }

    /** 查询中断及首次登记时受影响排班的原排班、新窗口与顺延对应关系。 */
    @GetMapping("/outages/{outageKey}")
    public OutageResponse getOutage(@PathVariable String outageKey) {
        return outageService.getOutage(outageKey);
    }

    /** 历史用水核销：快照核销当时的排班窗口，后续顺延不改写。 */
    @PostMapping("/schedules/{scheduleKey}/settlements")
    public SettlementResponse settleUsage(@PathVariable String scheduleKey,
                                          @RequestBody SettleUsageRequest request) {
        return outageService.settleUsage(request.commandKey(), request.settlementKey(), scheduleKey,
                request.volume());
    }

    /** 查询历史用水核销（当时排班窗口快照）。 */
    @GetMapping("/settlements/{settlementKey}")
    public SettlementResponse getSettlement(@PathVariable String settlementKey) {
        return outageService.getSettlement(settlementKey);
    }
}
