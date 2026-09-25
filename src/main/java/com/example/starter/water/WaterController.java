package com.example.starter.water;

import com.example.starter.water.dto.Dtos.AllocationResponse;
import com.example.starter.water.dto.Dtos.BatchSettleRequest;
import com.example.starter.water.dto.Dtos.BatchSettleResponse;
import com.example.starter.water.dto.Dtos.CapacityResponse;
import com.example.starter.water.dto.Dtos.CommandRequest;
import com.example.starter.water.dto.Dtos.CreateOutageRequest;
import com.example.starter.water.dto.Dtos.CreateWindowRequest;
import com.example.starter.water.dto.Dtos.CurtailmentRequest;
import com.example.starter.water.dto.Dtos.CurtailmentResponse;
import com.example.starter.water.dto.Dtos.HistoryResponse;
import com.example.starter.water.dto.Dtos.OutageCommandRequest;
import com.example.starter.water.dto.Dtos.OutageImpactResponse;
import com.example.starter.water.dto.Dtos.OutageResponse;
import com.example.starter.water.dto.Dtos.RecoverOutageRequest;
import com.example.starter.water.dto.Dtos.RiskListResponse;
import com.example.starter.water.dto.Dtos.SettleRequest;
import com.example.starter.water.dto.Dtos.SettlementCheckResponse;
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
import org.springframework.web.bind.annotation.RequestParam;
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

    /** 下达停运窗口（携带渠道 expectedVersion 与受影响申请集合）。 */
    @PostMapping("/channels/{channelId}/outages")
    public OutageResponse createOutage(@PathVariable String channelId,
                                       @RequestBody CreateOutageRequest request) {
        return service.createOutage(request.commandKey(), request.outageKey(), channelId,
                request.expectedVersion(), request.startUtc(), request.endUtc(), request.allocationKeys());
    }

    /** 删除停运窗口（仅未开始可删除）。 */
    @PostMapping("/channels/{channelId}/outages/{outageKey}/delete")
    public OutageResponse deleteOutage(@PathVariable String channelId, @PathVariable String outageKey,
                                       @RequestBody OutageCommandRequest request) {
        return service.deleteOutage(request.commandKey(), channelId, outageKey, request.expectedVersion());
    }

    /** 记录停运窗口提前恢复时刻（不得早于当前时刻，仅影响之后的核销）。 */
    @PostMapping("/channels/{channelId}/outages/{outageKey}/recover")
    public OutageResponse recoverOutage(@PathVariable String channelId, @PathVariable String outageKey,
                                        @RequestBody RecoverOutageRequest request) {
        return service.recoverOutage(request.commandKey(), channelId, outageKey, request.expectedVersion(),
                request.recoveredUtc());
    }

    /** 查询停运影响：受影响申请与已写入的供应风险。 */
    @GetMapping("/channels/{channelId}/outages/{outageKey}/impact")
    public OutageImpactResponse getOutageImpact(@PathVariable String channelId,
                                                @PathVariable String outageKey) {
        return service.getOutageImpact(channelId, outageKey);
    }

    /** 查询申请的供应风险。 */
    @GetMapping("/allocations/{allocationKey}/risks")
    public RiskListResponse getAllocationRisks(@PathVariable String allocationKey) {
        return service.getAllocationRisks(allocationKey);
    }

    /** 单笔核销。 */
    @PostMapping("/allocations/{allocationKey}/settlements")
    public SettlementResponse settle(@PathVariable String allocationKey,
                                     @RequestBody SettleRequest request) {
        return service.settle(request.commandKey(), request.settlementKey(), allocationKey, request.amount());
    }

    /** 批量核销：任一明细失败全部回滚。 */
    @PostMapping("/settlements/batch")
    public BatchSettleResponse settleBatch(@RequestBody BatchSettleRequest request) {
        return service.settleBatch(request.commandKey(), request.batchKey(), request.items());
    }

    /** 查询核销可行性及可区分拒绝原因。 */
    @GetMapping("/allocations/{allocationKey}/settlement-check")
    public SettlementCheckResponse checkSettlement(@PathVariable String allocationKey,
                                                   @RequestParam(required = false) String amount) {
        return service.checkSettlement(allocationKey, amount);
    }
}
