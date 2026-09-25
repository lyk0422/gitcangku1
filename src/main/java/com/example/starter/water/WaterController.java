package com.example.starter.water;

import com.example.starter.water.dto.Dtos.AllocationResponse;
import com.example.starter.water.dto.Dtos.BalanceEvolutionResponse;
import com.example.starter.water.dto.Dtos.CapacityResponse;
import com.example.starter.water.dto.Dtos.CommandRequest;
import com.example.starter.water.dto.Dtos.CorrectionApproveRequest;
import com.example.starter.water.dto.Dtos.CorrectionApproveResponse;
import com.example.starter.water.dto.Dtos.CorrectionRequest;
import com.example.starter.water.dto.Dtos.CorrectionResponse;
import com.example.starter.water.dto.Dtos.CreateWindowRequest;
import com.example.starter.water.dto.Dtos.CurtailmentRequest;
import com.example.starter.water.dto.Dtos.CurtailmentResponse;
import com.example.starter.water.dto.Dtos.HistoryResponse;
import com.example.starter.water.dto.Dtos.LedgerResponse;
import com.example.starter.water.dto.Dtos.RejectionListResponse;
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

    /** 核销：扣减 APPROVED 申请持有额度并写不可变原流水。 */
    @PostMapping("/allocations/{allocationKey}/writeoffs")
    public WriteoffResponse createWriteoff(@PathVariable String allocationKey,
                                           @RequestBody WriteoffRequest request,
                                           @RequestHeader("X-Actor-Id") String actor) {
        return service.createWriteoff(request.commandKey(), allocationKey, request.writeoffKey(),
                request.amount(), request.meterUtc(), actor);
    }

    /** 查询申请核销记录（原流水）。 */
    @GetMapping("/allocations/{allocationKey}/writeoffs")
    public WriteoffListResponse getWriteoffs(@PathVariable String allocationKey) {
        return service.getWriteoffs(allocationKey);
    }

    /** 查询申请反向流水（更正/撤销）。 */
    @GetMapping("/allocations/{allocationKey}/ledger")
    public LedgerResponse getReverseLedger(@PathVariable String allocationKey) {
        return service.getReverseLedger(allocationKey);
    }

    /** 查询申请余额演算（按业务事件时刻重放的余额曲线）。 */
    @GetMapping("/allocations/{allocationKey}/balance-evolution")
    public BalanceEvolutionResponse getBalanceEvolution(@PathVariable String allocationKey) {
        return service.getBalanceEvolution(allocationKey);
    }

    /** 登记计量更正（meterKey 指纹幂等；窗口关闭后仍可登记）。 */
    @PostMapping("/corrections")
    public CorrectionResponse requestCorrection(@RequestBody CorrectionRequest request,
                                                @RequestHeader("X-Actor-Id") String actor) {
        return service.requestCorrection(request.meterKey(), request.writeoffKey(),
                request.originalVersion(), request.correctedAmount(), request.meterUtc(),
                request.reason(), actor);
    }

    /** 批量批准计量更正：任一失败全部回滚。 */
    @PostMapping("/corrections/approve")
    public CorrectionApproveResponse approveCorrections(@RequestBody CorrectionApproveRequest request) {
        return service.approveCorrections(request.commandKey(), request.meterKeys());
    }

    /** 撤销已批准更正：再产生反向流水并通过最终态校验。 */
    @PostMapping("/corrections/{meterKey}/revoke")
    public CorrectionResponse revokeCorrection(@PathVariable String meterKey,
                                               @RequestBody CommandRequest request) {
        return service.revokeCorrection(request.commandKey(), meterKey);
    }

    /** 查询计量更正详情。 */
    @GetMapping("/corrections/{meterKey}")
    public CorrectionResponse getCorrection(@PathVariable String meterKey) {
        return service.getCorrection(meterKey);
    }

    /** 查询窗口拒绝原因日志。 */
    @GetMapping("/windows/{windowId}/rejections")
    public RejectionListResponse getRejections(@PathVariable long windowId) {
        return service.getRejections(windowId);
    }
}
