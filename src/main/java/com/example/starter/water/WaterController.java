package com.example.starter.water;

import com.example.starter.water.dto.Dtos.AllocationResponse;
import com.example.starter.water.dto.Dtos.CapacityResponse;
import com.example.starter.water.dto.Dtos.CommandRequest;
import com.example.starter.water.dto.Dtos.CreateWindowRequest;
import com.example.starter.water.dto.Dtos.CurtailmentRequest;
import com.example.starter.water.dto.Dtos.CurtailmentResponse;
import com.example.starter.water.dto.Dtos.EmergencyBatchRequest;
import com.example.starter.water.dto.Dtos.EmergencyBatchResponse;
import com.example.starter.water.dto.Dtos.EmergencyWriteOffRequest;
import com.example.starter.water.dto.Dtos.EmergencyWriteOffResponse;
import com.example.starter.water.dto.Dtos.HistoryResponse;
import com.example.starter.water.dto.Dtos.RegularWriteOffRequest;
import com.example.starter.water.dto.Dtos.RegularWriteOffResponse;
import com.example.starter.water.dto.Dtos.ReserveStatusResponse;
import com.example.starter.water.dto.Dtos.SetReserveRequest;
import com.example.starter.water.dto.Dtos.SubmitAllocationRequest;
import com.example.starter.water.dto.Dtos.TransferListResponse;
import com.example.starter.water.dto.Dtos.TransferRequest;
import com.example.starter.water.dto.Dtos.TransferResponse;
import com.example.starter.water.dto.Dtos.WindowCloseResponse;
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

    /** 设置/调整窗口应急储备量（携带 expectedVersion，操作人由 X-Actor-Id 提供）。 */
    @PostMapping("/windows/{windowId}/reserve")
    public ReserveStatusResponse setReserve(@PathVariable long windowId,
                                            @RequestBody SetReserveRequest request,
                                            @RequestHeader("X-Actor-Id") String actor) {
        return service.setReserve(request.reserveKey(), windowId, request.reserveVolume(),
                request.expectedVersion(), actor);
    }

    /** 查询窗口储备余额、常规可用量、应急核销流水与阻断原因。 */
    @GetMapping("/windows/{windowId}/reserve")
    public ReserveStatusResponse getReserveStatus(@PathVariable long windowId) {
        return service.getReserveStatus(windowId);
    }

    /** 常规核销：仅从常规可用量扣减，不得使可用常规量低于储备量。 */
    @PostMapping("/windows/{windowId}/write-offs")
    public RegularWriteOffResponse regularWriteOff(@PathVariable long windowId,
                                                   @RequestBody RegularWriteOffRequest request) {
        return service.regularWriteOff(request.commandKey(), request.writeOffKey(), windowId,
                request.amount());
    }

    /** 单笔应急核销：必须声明 emergencyId 与审批人，仅从储备余额扣减。 */
    @PostMapping("/windows/{windowId}/emergency-write-offs")
    public EmergencyWriteOffResponse emergencyWriteOff(@PathVariable long windowId,
                                                       @RequestBody EmergencyWriteOffRequest request) {
        return service.emergencyWriteOff(request.commandKey(), request.writeOffKey(), windowId,
                request.emergencyId(), request.approver(), request.amount());
    }

    /** 批量应急核销：先整体校验，再单事务扣减，任一失败整单回滚。 */
    @PostMapping("/windows/{windowId}/emergency-write-offs/batch")
    public EmergencyBatchResponse emergencyWriteOffBatch(@PathVariable long windowId,
                                                         @RequestBody EmergencyBatchRequest request) {
        return service.emergencyWriteOffBatch(request.commandKey(), request.batchKey(), windowId,
                request.items());
    }

    /** 关闭窗口：关闭后不得新建应急核销，历史储备快照保留。 */
    @PostMapping("/windows/{windowId}/close")
    public WindowCloseResponse closeWindow(@PathVariable long windowId,
                                           @RequestBody CommandRequest request) {
        return service.closeWindow(request.commandKey(), windowId);
    }
}
