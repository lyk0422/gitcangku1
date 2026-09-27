package com.example.starter.water;

import com.example.starter.water.dto.Dtos.AllocationResponse;
import com.example.starter.water.dto.Dtos.BatchApproveRequest;
import com.example.starter.water.dto.Dtos.BatchApproveResponse;
import com.example.starter.water.dto.Dtos.BatchTransferRequest;
import com.example.starter.water.dto.Dtos.BatchTransferResponse;
import com.example.starter.water.dto.Dtos.CapacityResponse;
import com.example.starter.water.dto.Dtos.CommandRequest;
import com.example.starter.water.dto.Dtos.CreateWindowRequest;
import com.example.starter.water.dto.Dtos.CurtailmentRequest;
import com.example.starter.water.dto.Dtos.CurtailmentResponse;
import com.example.starter.water.dto.Dtos.DrawdownCurveRequest;
import com.example.starter.water.dto.Dtos.DrawdownCurveResponse;
import com.example.starter.water.dto.Dtos.DrawdownStatusResponse;
import com.example.starter.water.dto.Dtos.HistoryResponse;
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
 * 灌区配水 REST 接口。所有写操作携带 commandKey（requestId）保证幂等；
 * 申请提交/取消/核销通过 X-Actor-Id 识别操作人。所有时间按 UTC 处理，小时区间左闭右开。
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

    /** 提交配水申请；有曲线窗口必须携带逐小时 plan。 */
    @PostMapping("/allocations")
    public AllocationResponse submitAllocation(@RequestBody SubmitAllocationRequest request,
                                               @RequestHeader("X-Actor-Id") String actor) {
        return service.submitAllocation(request.commandKey(), request.allocationKey(), request.windowId(),
                request.userId(), request.amount(), actor, request.plan());
    }

    /** 批准配水申请。 */
    @PostMapping("/allocations/{allocationKey}/approve")
    public AllocationResponse approveAllocation(@PathVariable String allocationKey,
                                                @RequestBody CommandRequest request) {
        return service.approveAllocation(request.commandKey(), allocationKey);
    }

    /** 批量批准配水申请：任一失败整批回滚。 */
    @PostMapping("/allocations/batch-approve")
    public BatchApproveResponse batchApprove(@RequestBody BatchApproveRequest request) {
        return service.batchApprove(request.commandKey(), request.allocationKeys());
    }

    /** 取消配水申请（仅申请人本人）。 */
    @PostMapping("/allocations/{allocationKey}/cancel")
    public AllocationResponse cancelAllocation(@PathVariable String allocationKey,
                                               @RequestBody CommandRequest request,
                                               @RequestHeader("X-Actor-Id") String actor) {
        return service.cancelAllocation(request.commandKey(), allocationKey, actor);
    }

    /** 实际下泄核销（仅申请人本人，只扣减同一小时额度）。 */
    @PostMapping("/allocations/{allocationKey}/writeoffs")
    public WriteoffResponse writeoff(@PathVariable String allocationKey,
                                     @RequestBody WriteoffRequest request,
                                     @RequestHeader("X-Actor-Id") String actor) {
        return service.writeoff(request.commandKey(), allocationKey, request.writeoffKey(),
                request.hourUtc(), request.amount(), actor);
    }

    /** 查询单条申请（含批准时固化的曲线版本与逐小时预留快照，只读）。 */
    @GetMapping("/allocations/{allocationKey}")
    public AllocationResponse getAllocation(@PathVariable String allocationKey) {
        return service.getAllocation(allocationKey);
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

    /** 设置/修改窗口下泄曲线（版本化；已开始小时修改返回 409）。 */
    @PostMapping("/windows/{windowId}/drawdown-curve")
    public DrawdownCurveResponse setDrawdownCurve(@PathVariable long windowId,
                                                  @RequestBody DrawdownCurveRequest request) {
        return service.setDrawdownCurve(request.commandKey(), windowId, request.points());
    }

    /** 查询窗口逐小时下泄诊断（只读）。 */
    @GetMapping("/windows/{windowId}/drawdown")
    public DrawdownStatusResponse getDrawdownStatus(@PathVariable long windowId) {
        return service.getDrawdownStatus(windowId);
    }

    /** 同窗口额度原子转让（仅源申请人本人）。 */
    @PostMapping("/transfers")
    public TransferResponse transfer(@RequestBody TransferRequest request,
                                     @RequestHeader("X-Actor-Id") String actor) {
        return service.transferAllocation(request.commandKey(), request.transferKey(),
                request.sourceAllocationKey(), request.targetAllocationKey(), actor);
    }

    /** 批量转让：基于完整最终额度与逐小时预留一次裁决，任一失败整批回滚。 */
    @PostMapping("/transfers/batch")
    public BatchTransferResponse batchTransfer(@RequestBody BatchTransferRequest request,
                                               @RequestHeader("X-Actor-Id") String actor) {
        return service.batchTransfer(request.commandKey(), request.transfers(), actor);
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
}
