package com.example.starter.water;

import com.example.starter.water.dto.Dtos.AllocationBatchRequest;
import com.example.starter.water.dto.Dtos.AllocationBatchResponse;
import com.example.starter.water.dto.Dtos.AllocationResponse;
import com.example.starter.water.dto.Dtos.CapacityResponse;
import com.example.starter.water.dto.Dtos.CommandRequest;
import com.example.starter.water.dto.Dtos.CreateWindowRequest;
import com.example.starter.water.dto.Dtos.CurtailmentRequest;
import com.example.starter.water.dto.Dtos.CurtailmentResponse;
import com.example.starter.water.dto.Dtos.DrawdownCurveResponse;
import com.example.starter.water.dto.Dtos.DrawdownRequest;
import com.example.starter.water.dto.Dtos.DrawdownUsageResponse;
import com.example.starter.water.dto.Dtos.HistoryResponse;
import com.example.starter.water.dto.Dtos.SubmitAllocationRequest;
import com.example.starter.water.dto.Dtos.TransferBatchRequest;
import com.example.starter.water.dto.Dtos.TransferBatchResponse;
import com.example.starter.water.dto.Dtos.TransferListResponse;
import com.example.starter.water.dto.Dtos.TransferRequest;
import com.example.starter.water.dto.Dtos.TransferResponse;
import com.example.starter.water.dto.Dtos.WindowResponse;
import com.example.starter.water.dto.Dtos.WriteoffRequest;
import com.example.starter.water.dto.Dtos.WriteoffResponse;
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
 * 申请提交/取消、转让、核销通过 X-Actor-Id 识别操作人。时间一律 UTC，小时区间左闭右开。
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

    /** 提交配水申请（曲线窗口携带 usageStartUtc/usageEndUtc 指定覆盖小时区间）。 */
    @PostMapping("/allocations")
    public AllocationResponse submitAllocation(@RequestBody SubmitAllocationRequest request,
                                               @RequestHeader("X-Actor-Id") String actor) {
        return service.submitAllocation(request.commandKey(), request.allocationKey(), request.windowId(),
                request.userId(), request.amount(), request.usageStartUtc(), request.usageEndUtc(), actor);
    }

    /** 批量批准配水申请：任一失败整批回滚。 */
    @PostMapping("/allocations/batch-approve")
    public AllocationBatchResponse approveAllocations(@RequestBody AllocationBatchRequest request) {
        return service.approveAllocations(request.commandKey(), request.allocationKeys());
    }

    /** 批准配水申请。 */
    @PostMapping("/allocations/{allocationKey}/approve")
    public AllocationResponse approveAllocation(@PathVariable String allocationKey,
                                                @RequestBody CommandRequest request) {
        return service.approveAllocation(request.commandKey(), allocationKey);
    }

    /** 取消配水申请（仅申请人本人），释放对应小时未核销预留。 */
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

    /** 全量替换窗口下泄曲线（新版本）；已开始小时修改返回 409。 */
    @PutMapping("/windows/{windowId}/drawdown")
    public DrawdownCurveResponse putDrawdownCurve(@PathVariable long windowId,
                                                  @RequestBody DrawdownRequest request) {
        return service.putDrawdownCurve(request.commandKey(), windowId, request.points());
    }

    /** 查询窗口当前生效下泄曲线版本（只读）。 */
    @GetMapping("/windows/{windowId}/drawdown")
    public DrawdownCurveResponse getDrawdownCurve(@PathVariable long windowId) {
        return service.getActiveCurve(windowId);
    }

    /** 查询窗口逐小时下泄诊断（上限、净预留、释放、核销、剩余，只读）。 */
    @GetMapping("/windows/{windowId}/drawdown/usage")
    public DrawdownUsageResponse getDrawdownUsage(@PathVariable long windowId) {
        return service.getDrawdownUsage(windowId);
    }

    /** 同窗口额度原子转让（仅源申请人本人）。 */
    @PostMapping("/transfers")
    public TransferResponse transfer(@RequestBody TransferRequest request,
                                     @RequestHeader("X-Actor-Id") String actor) {
        return service.transferAllocation(request.commandKey(), request.transferKey(),
                request.sourceAllocationKey(), request.targetAllocationKey(), actor);
    }

    /** 批量同窗口额度原子转让：基于完整最终状态一次提交，任一失败整批回滚。 */
    @PostMapping("/transfers/batch")
    public TransferBatchResponse transferBatch(@RequestBody TransferBatchRequest request,
                                               @RequestHeader("X-Actor-Id") String actor) {
        return service.transferAllocations(request.commandKey(), request.transfers(), actor);
    }

    /** 实际下泄核销：按发生小时扣减同一申请同一小时预留。 */
    @PostMapping("/writeoffs")
    public WriteoffResponse writeoff(@RequestBody WriteoffRequest request,
                                     @RequestHeader("X-Actor-Id") String actor) {
        return service.writeoff(request.commandKey(), request.writeoffKey(), request.allocationKey(),
                request.hourStartUtc(), request.volume(), actor);
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

    /** 查询窗口历史明细（含曲线版本、逐小时预留快照与核销流水，只读）。 */
    @GetMapping("/windows/{windowId}/history")
    public HistoryResponse getHistory(@PathVariable long windowId) {
        return service.getHistory(windowId);
    }
}
