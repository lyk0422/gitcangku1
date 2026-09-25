package com.example.starter.water;

import com.example.starter.water.dto.Dtos.AllocationResponse;
import com.example.starter.water.dto.Dtos.AllocationSchedulesResponse;
import com.example.starter.water.dto.Dtos.CapacityResponse;
import com.example.starter.water.dto.Dtos.ChannelScheduleResponse;
import com.example.starter.water.dto.Dtos.CommandRequest;
import com.example.starter.water.dto.Dtos.CreateWindowRequest;
import com.example.starter.water.dto.Dtos.CurtailmentRequest;
import com.example.starter.water.dto.Dtos.CurtailmentResponse;
import com.example.starter.water.dto.Dtos.HistoryResponse;
import com.example.starter.water.dto.Dtos.ScheduleCancelRequest;
import com.example.starter.water.dto.Dtos.ScheduleCreateRequest;
import com.example.starter.water.dto.Dtos.ScheduleResponse;
import com.example.starter.water.dto.Dtos.SubmitAllocationRequest;
import com.example.starter.water.dto.Dtos.TransferListResponse;
import com.example.starter.water.dto.Dtos.TransferRequest;
import com.example.starter.water.dto.Dtos.TransferResponse;
import com.example.starter.water.dto.Dtos.UsageResponse;
import com.example.starter.water.dto.Dtos.UsageWriteOffRequest;
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

    public WaterController(WaterService service) {
        this.service = service;
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

    /** 为已批准申请申请轮灌引水时段；同渠道重叠返回 409 并给出冲突时段。 */
    @PostMapping("/schedules")
    public ScheduleResponse createSchedule(@RequestBody ScheduleCreateRequest request,
                                           @RequestHeader("X-Actor-Id") String actor) {
        return service.createSchedule(request.commandKey(), request.scheduleKey(), request.allocationKey(),
                request.startUtc(), request.endUtc(), actor);
    }

    /** 取消轮灌时段；起始时刻已到返回 409。 */
    @PostMapping("/schedules/{scheduleKey}/cancel")
    public ScheduleResponse cancelSchedule(@PathVariable String scheduleKey,
                                           @RequestBody ScheduleCancelRequest request,
                                           @RequestHeader("X-Actor-Id") String actor) {
        return service.cancelSchedule(request.commandKey(), scheduleKey, actor);
    }

    /** 用水核销；用水时刻须落在该申请某个生效时段内，否则 422 并给出最近可用时段。 */
    @PostMapping("/usages")
    public UsageResponse writeOffUsage(@RequestBody UsageWriteOffRequest request,
                                       @RequestHeader("X-Actor-Id") String actor) {
        return service.writeOffUsage(request.commandKey(), request.usageKey(), request.allocationKey(),
                request.volume(), request.usedAtUtc(), actor);
    }

    /** 查询渠道排班表（含已取消历史记录）。 */
    @GetMapping("/channels/{channelId}/schedules")
    public ChannelScheduleResponse getChannelSchedule(@PathVariable String channelId) {
        return service.getChannelSchedule(channelId);
    }

    /** 查询申请时段明细（含已取消历史记录）。 */
    @GetMapping("/allocations/{allocationKey}/schedules")
    public AllocationSchedulesResponse getAllocationSchedules(@PathVariable String allocationKey) {
        return service.getAllocationSchedules(allocationKey);
    }
}
