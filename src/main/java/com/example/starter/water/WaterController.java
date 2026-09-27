package com.example.starter.water;

import com.example.starter.water.dto.Dtos.AllocationResponse;
import com.example.starter.water.dto.Dtos.CapacityResponse;
import com.example.starter.water.dto.Dtos.CommandRequest;
import com.example.starter.water.dto.Dtos.CreateWindowRequest;
import com.example.starter.water.dto.Dtos.CurtailmentRequest;
import com.example.starter.water.dto.Dtos.CurtailmentResponse;
import com.example.starter.water.dto.Dtos.EvaporationRateRequest;
import com.example.starter.water.dto.Dtos.EvaporationRateResponse;
import com.example.starter.water.dto.Dtos.HistoryResponse;
import com.example.starter.water.dto.Dtos.RateDiagnosticResponse;
import com.example.starter.water.dto.Dtos.SettlementBatchRequest;
import com.example.starter.water.dto.Dtos.SettlementBatchResponse;
import com.example.starter.water.dto.Dtos.SettlementListResponse;
import com.example.starter.water.dto.Dtos.SettlementRequest;
import com.example.starter.water.dto.Dtos.SettlementResultResponse;
import com.example.starter.water.dto.Dtos.SettlementReversalRequest;
import com.example.starter.water.dto.Dtos.SettlementReversalResponse;
import com.example.starter.water.dto.Dtos.SubmitAllocationRequest;
import com.example.starter.water.dto.Dtos.TransferListResponse;
import com.example.starter.water.dto.Dtos.TransferRequest;
import com.example.starter.water.dto.Dtos.TransferResponse;
import com.example.starter.water.dto.Dtos.WindowResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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

    /** 登记/修改渠道 UTC 自然日蒸发率（0~20%，最多四位小数）。 */
    @PutMapping("/channels/{channelId}/evaporation-rates/{dayUtc}")
    public EvaporationRateResponse putRate(@PathVariable String channelId, @PathVariable String dayUtc,
                                           @RequestBody EvaporationRateRequest request) {
        return service.putEvaporationRate(request.commandKey(), channelId, dayUtc, request.rate());
    }

    /** 查询渠道全部日蒸发率诊断，按 UTC 日升序。 */
    @GetMapping("/channels/{channelId}/evaporation-rates")
    public RateDiagnosticResponse getRates(@PathVariable String channelId) {
        return service.getRateDiagnostic(channelId);
    }

    /** 单笔送达核销：同事务写入上游扣减、送达与损耗。 */
    @PostMapping("/settlements")
    public SettlementResultResponse settle(@RequestBody SettlementRequest request,
                                           @RequestHeader("X-Actor-Id") String actor) {
        return service.settleDelivery(request.commandKey(), request.settlementKey(), request.windowId(),
                request.allocationKey(), request.occurredUtc(), request.deliveredVolume(),
                request.downstreamUserId(), request.dayUtc(), actor);
    }

    /** 批量送达核销：完整最终余额与逐笔舍入预校验，任一失败整批回滚。 */
    @PostMapping("/settlements/batch")
    public SettlementBatchResponse settleBatch(@RequestBody SettlementBatchRequest request,
                                               @RequestHeader("X-Actor-Id") String actor) {
        return service.settleBatch(request.commandKey(), request.batchKey(), request.windowId(),
                request.items(), actor);
    }

    /** 已结算核销只能通过反向更正记录处理。 */
    @PostMapping("/settlements/{settlementKey}/reversal")
    public SettlementReversalResponse reverseSettlement(@PathVariable String settlementKey,
                                                         @RequestBody SettlementReversalRequest request,
                                                         @RequestHeader("X-Actor-Id") String actor) {
        return service.reverseSettlement(request.commandKey(), request.correctionKey(), settlementKey,
                request.reason(), actor);
    }

    /** 查询窗口全部核销与更正明细。 */
    @GetMapping("/windows/{windowId}/settlements")
    public SettlementListResponse getWindowSettlements(@PathVariable long windowId) {
        return service.getWindowSettlements(windowId);
    }

    /** 查询某上游申请的全部核销与更正明细。 */
    @GetMapping("/allocations/{allocationKey}/settlements")
    public SettlementListResponse getAllocationSettlements(@PathVariable String allocationKey) {
        return service.getAllocationSettlements(allocationKey);
    }
}
