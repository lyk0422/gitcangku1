package com.example.starter.water;

import com.example.starter.water.dto.Dtos.AllocationResponse;
import com.example.starter.water.dto.Dtos.AnomalyListResponse;
import com.example.starter.water.dto.Dtos.CapacityResponse;
import com.example.starter.water.dto.Dtos.ChannelRuleListResponse;
import com.example.starter.water.dto.Dtos.ChannelRuleRequest;
import com.example.starter.water.dto.Dtos.ChannelStatusResponse;
import com.example.starter.water.dto.Dtos.CommandRequest;
import com.example.starter.water.dto.Dtos.CreateWindowRequest;
import com.example.starter.water.dto.Dtos.CurtailmentRequest;
import com.example.starter.water.dto.Dtos.CurtailmentResponse;
import com.example.starter.water.dto.Dtos.HistoryResponse;
import com.example.starter.water.dto.Dtos.ReadingListResponse;
import com.example.starter.water.dto.Dtos.ReleaseRequest;
import com.example.starter.water.dto.Dtos.ReleaseResponse;
import com.example.starter.water.dto.Dtos.RuleVersionResponse;
import com.example.starter.water.dto.Dtos.SettlementListResponse;
import com.example.starter.water.dto.Dtos.SettlementRequest;
import com.example.starter.water.dto.Dtos.SettlementResponse;
import com.example.starter.water.dto.Dtos.SubmitReadingsRequest;
import com.example.starter.water.dto.Dtos.SubmitReadingsResponse;
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

    /** 配置渠道异常判定规则（生成新的不可变版本）。 */
    @PostMapping("/channels/{channelId}/rules")
    public RuleVersionResponse configureChannelRule(@PathVariable String channelId,
                                                    @RequestBody ChannelRuleRequest request,
                                                    @RequestHeader("X-Actor-Id") String actor) {
        return service.configureChannelRule(request.commandKey(), channelId, request.maxFlowRate(),
                request.maxIntervalMinutes(), actor);
    }

    /** 查询渠道规则版本历史。 */
    @GetMapping("/channels/{channelId}/rules")
    public ChannelRuleListResponse getChannelRules(@PathVariable String channelId) {
        return service.getChannelRules(channelId);
    }

    /** 批量提交渠道计量点累计读数。 */
    @PostMapping("/channels/{channelId}/readings")
    public SubmitReadingsResponse submitReadings(@PathVariable String channelId,
                                                 @RequestBody SubmitReadingsRequest request,
                                                 @RequestHeader("X-Actor-Id") String actor) {
        return service.submitReadings(request.commandKey(), channelId, request.readings(), actor);
    }

    /** 查询渠道读数历史。 */
    @GetMapping("/channels/{channelId}/readings")
    public ReadingListResponse getReadings(@PathVariable String channelId) {
        return service.getReadings(channelId);
    }

    /** 查询渠道异常与诊断明细。 */
    @GetMapping("/channels/{channelId}/anomalies")
    public AnomalyListResponse getAnomalies(@PathVariable String channelId) {
        return service.getAnomalies(channelId);
    }

    /** 查询渠道冻结状态。 */
    @GetMapping("/channels/{channelId}/status")
    public ChannelStatusResponse getChannelStatus(@PathVariable String channelId) {
        return service.getChannelStatus(channelId);
    }

    /** 解除异常（须由与触发读数提交人不同的操作人确认诊断说明与替代读数）。 */
    @PostMapping("/anomalies/{anomalyId}/release")
    public ReleaseResponse releaseAnomaly(@PathVariable long anomalyId,
                                          @RequestBody ReleaseRequest request,
                                          @RequestHeader("X-Actor-Id") String actor) {
        return service.releaseAnomaly(request.commandKey(), anomalyId, request.diagnosis(),
                request.substituteValue(), request.substituteObservedUtc(), actor);
    }

    /** 实际核销：扣减 APPROVED 申请持有额度并写不可变核销流水。 */
    @PostMapping("/settlements")
    public SettlementResponse settle(@RequestBody SettlementRequest request,
                                     @RequestHeader("X-Actor-Id") String actor) {
        return service.settle(request.commandKey(), request.settlementKey(), request.allocationKey(),
                request.amount(), actor);
    }

    /** 查询申请核销历史。 */
    @GetMapping("/allocations/{allocationKey}/settlements")
    public SettlementListResponse getSettlements(@PathVariable String allocationKey) {
        return service.getSettlements(allocationKey);
    }
}
