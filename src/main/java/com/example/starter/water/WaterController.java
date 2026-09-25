package com.example.starter.water;

import com.example.starter.water.dto.Dtos.AllocationBlendSummaryResponse;
import com.example.starter.water.dto.Dtos.AllocationResponse;
import com.example.starter.water.dto.Dtos.BlendSnapshotListResponse;
import com.example.starter.water.dto.Dtos.BlendSnapshotResponse;
import com.example.starter.water.dto.Dtos.BlendWriteOffRequest;
import com.example.starter.water.dto.Dtos.CapacityResponse;
import com.example.starter.water.dto.Dtos.CommandRequest;
import com.example.starter.water.dto.Dtos.CreateSourceRequest;
import com.example.starter.water.dto.Dtos.CreateWindowRequest;
import com.example.starter.water.dto.Dtos.CurtailmentRequest;
import com.example.starter.water.dto.Dtos.CurtailmentResponse;
import com.example.starter.water.dto.Dtos.HistoryResponse;
import com.example.starter.water.dto.Dtos.SourceResponse;
import com.example.starter.water.dto.Dtos.SubmitAllocationRequest;
import com.example.starter.water.dto.Dtos.TransferListResponse;
import com.example.starter.water.dto.Dtos.TransferRequest;
import com.example.starter.water.dto.Dtos.TransferResponse;
import com.example.starter.water.dto.Dtos.UpdateSourceSalinityRequest;
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

    /** 提交配水申请（可声明盐度上限 salinityLimit，单位 mg/L）。 */
    @PostMapping("/allocations")
    public AllocationResponse submitAllocation(@RequestBody SubmitAllocationRequest request,
                                               @RequestHeader("X-Actor-Id") String actor) {
        return service.submitAllocation(request.commandKey(), request.allocationKey(), request.windowId(),
                request.userId(), request.amount(), request.salinityLimit(), actor);
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

    // ------------------------------------------------------------------
    // 水源与水质掺配
    // ------------------------------------------------------------------

    /** 注册水源（含可用量与盐度 mg/L）。 */
    @PostMapping("/sources")
    public SourceResponse createSource(@RequestBody CreateSourceRequest request) {
        return service.createSource(request.commandKey(), request.sourceKey(),
                request.availableAmount(), request.salinity());
    }

    /** 查询水源当前余量与盐度。 */
    @GetMapping("/sources/{sourceKey}")
    public SourceResponse getSource(@PathVariable String sourceKey) {
        return service.getSource(sourceKey);
    }

    /** 携带 expectedVersion 修改水源盐度；只影响后续核销，不改写历史快照。 */
    @PostMapping("/sources/{sourceKey}/salinity")
    public SourceResponse updateSourceSalinity(@PathVariable String sourceKey,
                                               @RequestBody UpdateSourceSalinityRequest request) {
        return service.updateSourceSalinity(request.commandKey(), sourceKey, request.expectedVersion(),
                request.salinity());
    }

    /** 水质掺配核销：1 至 5 个水源联合核销一笔申请，跨表原子扣减并写不可变快照。 */
    @PostMapping("/blends")
    public BlendSnapshotResponse blendWriteOff(@RequestBody BlendWriteOffRequest request,
                                               @RequestHeader("X-Actor-Id") String actor) {
        return service.blendWriteOff(request.commandKey(), request.blendKey(), request.allocationKey(),
                request.allocationVersion(), request.writeOffAmount(), request.items(), actor);
    }

    /** 按掺配业务键查询不可变快照。 */
    @GetMapping("/blends/{blendKey}")
    public BlendSnapshotResponse getBlend(@PathVariable String blendKey) {
        return service.getBlendSnapshot(blendKey);
    }

    /** 查询申请全部掺配快照。 */
    @GetMapping("/allocations/{allocationKey}/blends")
    public BlendSnapshotListResponse getBlends(@PathVariable String allocationKey) {
        return service.listBlendSnapshots(allocationKey);
    }

    /** 查询申请水源余量口径与累计加权盐度。 */
    @GetMapping("/allocations/{allocationKey}/blend-summary")
    public AllocationBlendSummaryResponse getBlendSummary(@PathVariable String allocationKey) {
        return service.getAllocationBlendSummary(allocationKey);
    }
}
