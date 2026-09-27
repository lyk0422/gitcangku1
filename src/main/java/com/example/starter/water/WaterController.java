package com.example.starter.water;

import com.example.starter.water.dto.Dtos.AllocationResponse;
import com.example.starter.water.dto.Dtos.BatchSettleRequest;
import com.example.starter.water.dto.Dtos.BatchSettleResponse;
import com.example.starter.water.dto.Dtos.CapacityResponse;
import com.example.starter.water.dto.Dtos.CommandRequest;
import com.example.starter.water.dto.Dtos.CreateWindowRequest;
import com.example.starter.water.dto.Dtos.CurtailmentRequest;
import com.example.starter.water.dto.Dtos.CurtailmentResponse;
import com.example.starter.water.dto.Dtos.EvaporationDiagnosticsResponse;
import com.example.starter.water.dto.Dtos.EvaporationRateResponse;
import com.example.starter.water.dto.Dtos.HistoryResponse;
import com.example.starter.water.dto.Dtos.RegisterEvaporationRateRequest;
import com.example.starter.water.dto.Dtos.SettleRequest;
import com.example.starter.water.dto.Dtos.SettlementListResponse;
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

    /** 登记渠道日蒸发率（新版本）；该日首次核销后修改返回 409。 */
    @PostMapping("/channels/{channelId}/evaporation-rates")
    public EvaporationRateResponse registerEvaporationRate(@PathVariable String channelId,
                                                           @RequestBody RegisterEvaporationRateRequest request) {
        return service.registerEvaporationRate(request.commandKey(), channelId, request.date(), request.rate());
    }

    /** 单笔核销送达水量：按当日渠道率计算损耗与上游扣减，同事务写入。 */
    @PostMapping("/settlements")
    public SettlementResponse settle(@RequestBody SettleRequest request) {
        return service.settle(request.commandKey(), request.allocationKey(), request.date(), request.delivered());
    }

    /** 批量核销：任一 item 预校验失败整批回滚。 */
    @PostMapping("/settlements/batch")
    public BatchSettleResponse settleBatch(@RequestBody BatchSettleRequest request) {
        return service.settleBatch(request.commandKey(), request.items());
    }

    /** 反向更正已结算核销：新增 CORRECTION 记录并回补上游额度。 */
    @PostMapping("/settlements/{settlementId}/correct")
    public SettlementResponse correctSettlement(@PathVariable long settlementId,
                                                @RequestBody CommandRequest request) {
        return service.correctSettlement(request.commandKey(), settlementId);
    }

    /** 核销记录明细查询。 */
    @GetMapping("/settlements/{settlementId}")
    public SettlementResponse getSettlement(@PathVariable long settlementId) {
        return service.getSettlement(settlementId);
    }

    /** 申请维度核销历史（含更正记录）。 */
    @GetMapping("/allocations/{allocationKey}/settlements")
    public SettlementListResponse getAllocationSettlements(@PathVariable String allocationKey) {
        return service.getAllocationSettlements(allocationKey);
    }

    /** 渠道日蒸发诊断：版本历史、锁定状态与核销净额汇总。 */
    @GetMapping("/channels/{channelId}/evaporation-diagnostics")
    public EvaporationDiagnosticsResponse getEvaporationDiagnostics(@PathVariable String channelId,
                                                                    @RequestParam String date) {
        return service.getEvaporationDiagnostics(channelId, date);
    }
}
