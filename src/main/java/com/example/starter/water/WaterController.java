package com.example.starter.water;

import com.example.starter.water.dto.Dtos.AllocationResponse;
import com.example.starter.water.dto.Dtos.CapacityResponse;
import com.example.starter.water.dto.Dtos.CommandRequest;
import com.example.starter.water.dto.Dtos.ConfigureSourcesRequest;
import com.example.starter.water.dto.Dtos.ConsumeRequest;
import com.example.starter.water.dto.Dtos.ConsumeResponse;
import com.example.starter.water.dto.Dtos.CreateWindowRequest;
import com.example.starter.water.dto.Dtos.CurtailmentRequest;
import com.example.starter.water.dto.Dtos.CurtailmentResponse;
import com.example.starter.water.dto.Dtos.HistoryResponse;
import com.example.starter.water.dto.Dtos.RebalanceActivateRequest;
import com.example.starter.water.dto.Dtos.RebalanceListResponse;
import com.example.starter.water.dto.Dtos.RebalancePreviewRequest;
import com.example.starter.water.dto.Dtos.RebalanceResponse;
import com.example.starter.water.dto.Dtos.SliceListResponse;
import com.example.starter.water.dto.Dtos.SourceListResponse;
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
                request.userId(), request.amount(), request.sourceId(), actor);
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

    /** 配置/整体替换窗口水源及供给上限（1~10 个）。 */
    @PostMapping("/windows/{windowId}/sources")
    public SourceListResponse configureSources(@PathVariable long windowId,
                                               @RequestBody ConfigureSourcesRequest request) {
        return service.configureSources(request.commandKey(), windowId, request.sources());
    }

    /** 查询窗口水源配置。 */
    @GetMapping("/windows/{windowId}/sources")
    public SourceListResponse getSources(@PathVariable long windowId) {
        return service.getSources(windowId);
    }

    /** 关闭窗口：关闭后不可重平衡。 */
    @PostMapping("/windows/{windowId}/close")
    public WindowResponse closeWindow(@PathVariable long windowId, @RequestBody CommandRequest request) {
        return service.closeWindow(request.commandKey(), windowId);
    }

    /** 用水核销：在指定水源分片上登记已核销用水量。 */
    @PostMapping("/allocations/{allocationKey}/consumptions")
    public ConsumeResponse consume(@PathVariable String allocationKey,
                                   @RequestBody ConsumeRequest request) {
        return service.consume(request.commandKey(), allocationKey, request.sourceId(), request.amount());
    }

    /** 查询申请的水源分片矩阵行。 */
    @GetMapping("/allocations/{allocationKey}/slices")
    public SliceListResponse getSlices(@PathVariable String allocationKey) {
        return service.getSlices(allocationKey);
    }

    /** 重平衡预览：规范化明细并按完整矩阵计算后态，只读不落库。 */
    @PostMapping("/windows/{windowId}/rebalances/preview")
    public RebalanceResponse previewRebalance(@PathVariable long windowId,
                                              @RequestBody RebalancePreviewRequest request) {
        return service.previewRebalance(windowId, request.items(), request.expectedVersions());
    }

    /** 激活重平衡单：单事务校验并一次性应用，冻结快照。 */
    @PostMapping("/windows/{windowId}/rebalances")
    public RebalanceResponse activateRebalance(@PathVariable long windowId,
                                               @RequestBody RebalanceActivateRequest request) {
        return service.activateRebalance(request.requestId(), request.rebalanceKey(), windowId,
                request.items(), request.expectedVersions());
    }

    /** 查询重平衡单冻结快照（只读证据）。 */
    @GetMapping("/windows/{windowId}/rebalances/{rebalanceKey}")
    public RebalanceResponse getRebalance(@PathVariable long windowId, @PathVariable String rebalanceKey) {
        return service.getRebalance(windowId, rebalanceKey);
    }

    /** 查询窗口全部重平衡单。 */
    @GetMapping("/windows/{windowId}/rebalances")
    public RebalanceListResponse listRebalances(@PathVariable long windowId) {
        return service.listRebalances(windowId);
    }
}
