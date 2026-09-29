package com.example.starter.water;

import com.example.starter.water.dto.Dtos.AllocationResponse;
import com.example.starter.water.dto.Dtos.CapacityResponse;
import com.example.starter.water.dto.Dtos.CommandRequest;
import com.example.starter.water.dto.Dtos.CreateWindowRequest;
import com.example.starter.water.dto.Dtos.CurtailmentRequest;
import com.example.starter.water.dto.Dtos.CurtailmentResponse;
import com.example.starter.water.dto.Dtos.HistoryResponse;
import com.example.starter.water.dto.Dtos.RainConfirmRequest;
import com.example.starter.water.dto.Dtos.RainDiagnosticsResponse;
import com.example.starter.water.dto.Dtos.RainListResponse;
import com.example.starter.water.dto.Dtos.RainRecordResponse;
import com.example.starter.water.dto.Dtos.RainRegisterRequest;
import com.example.starter.water.dto.Dtos.RainRevokeRequest;
import com.example.starter.water.dto.Dtos.SubmitAllocationRequest;
import com.example.starter.water.dto.Dtos.TransferListResponse;
import com.example.starter.water.dto.Dtos.TransferRequest;
import com.example.starter.water.dto.Dtos.TransferResponse;
import com.example.starter.water.dto.Dtos.WindowResponse;
import com.example.starter.water.dto.Dtos.WriteoffRequest;
import com.example.starter.water.dto.Dtos.WriteoffResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 灌区配水 REST 接口。所有写操作携带 commandKey 保证幂等；
 * 申请提交/取消、降雨登记/确认/撤销、核销通过 X-Actor-Id 识别操作人。
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

    /** 提交配水申请（可携带每毫米降雨抵扣系数 creditCoefficient）。 */
    @PostMapping("/allocations")
    public AllocationResponse submitAllocation(@RequestBody SubmitAllocationRequest request,
                                               @RequestHeader("X-Actor-Id") String actor) {
        return service.submitAllocation(request.commandKey(), request.allocationKey(), request.windowId(),
                request.userId(), request.amount(), request.creditCoefficient(), actor);
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

    /** 登记窗口降雨记录（PENDING，需两名不同水务角色确认）。 */
    @PostMapping("/windows/{windowId}/rain")
    public RainRecordResponse registerRain(@PathVariable long windowId,
                                           @RequestBody RainRegisterRequest request,
                                           @RequestHeader("X-Actor-Id") String actor) {
        return service.registerRain(request.commandKey(), request.rainKey(), windowId,
                request.rainMillis(), request.observedUtc(), actor);
    }

    /** 查询窗口全部降雨记录（含确认证据），按登记先后稳定排序。 */
    @GetMapping("/windows/{windowId}/rain")
    public RainListResponse getRainRecords(@PathVariable long windowId) {
        return service.getRainRecords(windowId);
    }

    /** 水务角色确认降雨；两名不同角色确认后生效。 */
    @PostMapping("/rain/{rainKey}/confirm")
    public RainRecordResponse confirmRain(@PathVariable String rainKey,
                                          @RequestBody RainConfirmRequest request,
                                          @RequestHeader("X-Actor-Id") String actor) {
        return service.confirmRain(request.commandKey(), rainKey, actor);
    }

    /** 撤销降雨记录；不影响已结算核销。 */
    @PostMapping("/rain/{rainKey}/revoke")
    public RainRecordResponse revokeRain(@PathVariable String rainKey,
                                         @RequestBody RainRevokeRequest request,
                                         @RequestHeader("X-Actor-Id") String actor) {
        return service.revokeRain(request.commandKey(), rainKey, actor);
    }

    /** 单笔或批量核销：先计降雨抵扣再扣配额，金额守恒；明细按请求顺序稳定排序。 */
    @PostMapping("/windows/{windowId}/writeoffs")
    public WriteoffResponse writeoffBatch(@PathVariable long windowId,
                                          @RequestBody WriteoffRequest request,
                                          @RequestHeader("X-Actor-Id") String actor) {
        return service.writeoffBatch(request.commandKey(), request.writeoffKey(), windowId,
                request.items(), actor);
    }

    /** 查询核销单（含不可变明细与降雨证据快照）。 */
    @GetMapping("/writeoffs/{writeoffKey}")
    public WriteoffResponse getWriteoff(@PathVariable String writeoffKey) {
        return service.getWriteoff(writeoffKey);
    }

    /** 窗口降雨与核销诊断：生效降雨、各申请抵扣预览、实际/要求/差额。 */
    @GetMapping("/windows/{windowId}/rain/diagnostics")
    public RainDiagnosticsResponse getRainDiagnostics(@PathVariable long windowId) {
        return service.getRainDiagnostics(windowId);
    }
}
