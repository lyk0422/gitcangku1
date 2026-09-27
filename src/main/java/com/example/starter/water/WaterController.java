package com.example.starter.water;

import com.example.starter.water.dto.Dtos.AllocationResponse;
import com.example.starter.water.dto.Dtos.AnomalyDetailResponse;
import com.example.starter.water.dto.Dtos.CapacityResponse;
import com.example.starter.water.dto.Dtos.ChannelConfigRequest;
import com.example.starter.water.dto.Dtos.ChannelConfigResponse;
import com.example.starter.water.dto.Dtos.ChannelHistoryResponse;
import com.example.starter.water.dto.Dtos.CommandRequest;
import com.example.starter.water.dto.Dtos.CreateWindowRequest;
import com.example.starter.water.dto.Dtos.CurtailmentRequest;
import com.example.starter.water.dto.Dtos.CurtailmentResponse;
import com.example.starter.water.dto.Dtos.HistoryResponse;
import com.example.starter.water.dto.Dtos.ReadingsRequest;
import com.example.starter.water.dto.Dtos.ReadingsResponse;
import com.example.starter.water.dto.Dtos.ResolveAnomalyRequest;
import com.example.starter.water.dto.Dtos.SubmitAllocationRequest;
import com.example.starter.water.dto.Dtos.TransferListResponse;
import com.example.starter.water.dto.Dtos.TransferRequest;
import com.example.starter.water.dto.Dtos.TransferResponse;
import com.example.starter.water.dto.Dtos.WindowResponse;
import com.example.starter.water.dto.Dtos.WriteoffListResponse;
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

    /** 配置渠道计量异常规则（产生不可变新版本）。 */
    @PostMapping("/channels/{channelId}/config")
    public ChannelConfigResponse configureChannel(@PathVariable String channelId,
                                                  @RequestBody ChannelConfigRequest request,
                                                  @RequestHeader("X-Actor-Id") String actor) {
        return service.configureChannel(request.commandKey(), channelId, request.maxFlowRate(),
                request.maxIntervalMinutes(), request.effectiveFromUtc(), actor);
    }

    /** 提交渠道一批累计读数；整批校验，任一错误整批不写入。 */
    @PostMapping("/channels/{channelId}/readings")
    public ReadingsResponse submitReadings(@PathVariable String channelId,
                                           @RequestBody ReadingsRequest request,
                                           @RequestHeader("X-Actor-Id") String actor) {
        // 路径渠道与请求体一致，避免同一份读数误提交到其他渠道
        if (request.channelId() != null && !request.channelId().equals(channelId)) {
            throw ApiException.badRequest("CHANNEL_MISMATCH",
                    "请求体 channelId 与路径不一致: " + request.channelId() + " != " + channelId);
        }
        return service.submitReadings(request.commandKey(), channelId, request.readings(), actor);
    }

    /** 解除异常（不同操作人 + 诊断说明 + 替代读数）。 */
    @PostMapping("/anomalies/{anomalyId}/resolve")
    public AnomalyDetailResponse resolveAnomaly(@PathVariable long anomalyId,
                                                @RequestBody ResolveAnomalyRequest request,
                                                @RequestHeader("X-Actor-Id") String actor) {
        return service.resolveAnomaly(request.commandKey(), anomalyId, request.diagnosisNote(),
                request.replacementAtUtc(), request.replacementValue(), actor);
    }

    /** 实际核销已批准申请的持有额度；渠道冻结中被拦截。 */
    @PostMapping("/writeoffs")
    public WriteoffResponse writeoff(@RequestBody WriteoffRequest request,
                                     @RequestHeader("X-Actor-Id") String actor) {
        return service.writeoff(request.commandKey(), request.writeoffKey(), request.allocationKey(),
                request.amount(), request.atUtc(), actor);
    }

    /** 查询窗口核销流水。 */
    @GetMapping("/windows/{windowId}/writeoffs")
    public WriteoffListResponse getWriteoffs(@PathVariable long windowId) {
        return service.getWriteoffs(windowId);
    }

    /** 查询渠道读数/异常/规则版本历史。 */
    @GetMapping("/channels/{channelId}/history")
    public ChannelHistoryResponse getChannelHistory(@PathVariable String channelId) {
        return service.getChannelHistory(channelId);
    }

    /** 查询异常诊断明细。 */
    @GetMapping("/anomalies/{anomalyId}")
    public AnomalyDetailResponse getAnomalyDetail(@PathVariable long anomalyId) {
        return service.getAnomalyDetail(anomalyId);
    }
}
