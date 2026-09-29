package com.example.starter.water;

import com.example.starter.water.dto.Dtos.AllocationResponse;
import com.example.starter.water.dto.Dtos.CapacityResponse;
import com.example.starter.water.dto.Dtos.CommandRequest;
import com.example.starter.water.dto.Dtos.CreateWindowRequest;
import com.example.starter.water.dto.Dtos.CreditFactorRequest;
import com.example.starter.water.dto.Dtos.CurtailmentRequest;
import com.example.starter.water.dto.Dtos.CurtailmentResponse;
import com.example.starter.water.dto.Dtos.DiagnosticsResponse;
import com.example.starter.water.dto.Dtos.HistoryResponse;
import com.example.starter.water.dto.Dtos.RainfallConfirmRequest;
import com.example.starter.water.dto.Dtos.RainfallListResponse;
import com.example.starter.water.dto.Dtos.RainfallRegisterRequest;
import com.example.starter.water.dto.Dtos.RainfallResponse;
import com.example.starter.water.dto.Dtos.SubmitAllocationRequest;
import com.example.starter.water.dto.Dtos.TransferListResponse;
import com.example.starter.water.dto.Dtos.TransferRequest;
import com.example.starter.water.dto.Dtos.TransferResponse;
import com.example.starter.water.dto.Dtos.WindowResponse;
import com.example.starter.water.dto.Dtos.WriteOffBatchRequest;
import com.example.starter.water.dto.Dtos.WriteOffBatchResponse;
import com.example.starter.water.dto.Dtos.WriteOffListResponse;
import com.example.starter.water.dto.Dtos.WriteOffRequest;
import com.example.starter.water.dto.Dtos.WriteOffResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 灌区配水 REST 接口。所有写操作携带 commandKey 保证幂等；
 * 申请提交/取消、转让、降雨登记/确认/撤销、系数声明与核销通过 X-Actor-Id 识别操作人。
 * 所有时间为 UTC，窗口区间左闭右开。
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

    /** 声明申请的降雨抵扣系数（仅申请人本人）。 */
    @PostMapping("/allocations/{allocationKey}/credit-factor")
    public AllocationResponse declareCreditFactor(@PathVariable String allocationKey,
                                                  @RequestBody CreditFactorRequest request,
                                                  @RequestHeader("X-Actor-Id") String actor) {
        return service.declareCreditFactor(request.commandKey(), allocationKey, request.coefficient(), actor);
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

    /** 登记窗口降雨记录（待两名不同水务角色确认）。 */
    @PostMapping("/windows/{windowId}/rainfall")
    public RainfallResponse registerRainfall(@PathVariable long windowId,
                                             @RequestBody RainfallRegisterRequest request,
                                             @RequestHeader("X-Actor-Id") String actor) {
        return service.registerRainfall(request.commandKey(), windowId, request.rainKey(),
                request.rainfallMm(), actor);
    }

    /** 水务角色确认降雨记录，两名不同角色确认后生效。 */
    @PostMapping("/windows/{windowId}/rainfall/{rainKey}/confirm")
    public RainfallResponse confirmRainfall(@PathVariable long windowId, @PathVariable String rainKey,
                                            @RequestBody RainfallConfirmRequest request,
                                            @RequestHeader("X-Actor-Id") String actor) {
        return service.confirmRainfall(request.commandKey(), windowId, rainKey, request.roleId(), actor);
    }

    /** 撤销窗口降雨记录；不影响已结算核销。 */
    @PostMapping("/windows/{windowId}/rainfall/{rainKey}/revoke")
    public RainfallResponse revokeRainfall(@PathVariable long windowId, @PathVariable String rainKey,
                                           @RequestBody CommandRequest request,
                                           @RequestHeader("X-Actor-Id") String actor) {
        return service.revokeRainfall(request.commandKey(), windowId, rainKey, actor);
    }

    /** 查询窗口全部降雨记录（含已撤销），按登记顺序返回。 */
    @GetMapping("/windows/{windowId}/rainfall")
    public RainfallListResponse getRainfall(@PathVariable long windowId) {
        return service.getRainfall(windowId);
    }

    /** 单笔核销：先降雨抵扣再扣减配额，固化业务证据。 */
    @PostMapping("/windows/{windowId}/write-offs")
    public WriteOffResponse writeOff(@PathVariable long windowId, @RequestBody WriteOffRequest request,
                                     @RequestHeader("X-Actor-Id") String actor) {
        return service.writeOff(request.commandKey(), windowId, request.writeOffKey(),
                request.allocationKey(), request.amount(), actor);
    }

    /** 批量核销：先验完整最终余额，再在一个事务内整体提交。 */
    @PostMapping("/windows/{windowId}/write-offs/batch")
    public WriteOffBatchResponse writeOffBatch(@PathVariable long windowId,
                                               @RequestBody WriteOffBatchRequest request,
                                               @RequestHeader("X-Actor-Id") String actor) {
        return service.writeOffBatch(request.commandKey(), windowId, request.batchKey(),
                request.items(), actor);
    }

    /** 查询窗口全部核销流水（不可变），按核销顺序返回。 */
    @GetMapping("/windows/{windowId}/write-offs")
    public WriteOffListResponse getWriteOffs(@PathVariable long windowId) {
        return service.getWriteOffs(windowId);
    }

    /** 查询窗口降雨抵扣与核销诊断。 */
    @GetMapping("/windows/{windowId}/diagnostics")
    public DiagnosticsResponse getDiagnostics(@PathVariable long windowId) {
        return service.getDiagnostics(windowId);
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
}
