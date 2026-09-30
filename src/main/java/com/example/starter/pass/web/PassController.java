package com.example.starter.pass.web;

import com.example.starter.pass.api.AllocateRequest;
import com.example.starter.pass.api.CancelRequest;
import com.example.starter.pass.api.CompleteRequest;
import com.example.starter.pass.api.PredictionUpsertRequest;
import com.example.starter.pass.api.PredictionView;
import com.example.starter.pass.api.ReassignRequest;
import com.example.starter.pass.api.SlotView;
import com.example.starter.pass.service.PassService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 卫星过境接收计划业务入口。
 */
@RestController
@RequestMapping("/api")
public class PassController {

    private final PassService passService;

    public PassController(PassService passService) {
        this.passService = passService;
    }

    /** 提交/修订过境预测，返回固化后的新版本。 */
    @PostMapping("/predictions")
    @ResponseStatus(HttpStatus.CREATED)
    public PredictionView upsertPrediction(@Valid @RequestBody PredictionUpsertRequest request) {
        return passService.upsertPrediction(request);
    }

    /** 预测版本时间线。 */
    @GetMapping("/satellites/{satelliteCode}/predictions")
    public List<PredictionView> predictions(@PathVariable String satelliteCode) {
        return passService.getPredictionVersions(satelliteCode);
    }

    /** 申请接收时隙分配。 */
    @PostMapping("/slots/allocate")
    @ResponseStatus(HttpStatus.CREATED)
    public SlotView allocate(@Valid @RequestBody AllocateRequest request) {
        return passService.allocate(request);
    }

    /** 接收完成回报（遥测确认）。 */
    @PostMapping("/slots/{slotId}/complete")
    public SlotView complete(@PathVariable long slotId, @Valid @RequestBody CompleteRequest request) {
        return passService.complete(slotId, request);
    }

    /** 取消接收计划，释放天线。 */
    @PostMapping("/slots/{slotId}/cancel")
    public SlotView cancel(@PathVariable long slotId, @Valid @RequestBody CancelRequest request) {
        return passService.cancel(slotId, request);
    }

    /** 待重新分配时隙重新分配。 */
    @PostMapping("/slots/{slotId}/reassign")
    public SlotView reassign(@PathVariable long slotId, @Valid @RequestBody ReassignRequest request) {
        return passService.reassign(slotId, request);
    }

    /** 某卫星的时隙时间线（含各状态与接收实绩）。 */
    @GetMapping("/satellites/{satelliteCode}/slots")
    public List<SlotView> timeline(@PathVariable String satelliteCode) {
        return passService.getTimeline(satelliteCode);
    }

    /** 按状态查询时隙，例如 ?status=PENDING_REASSIGN。 */
    @GetMapping("/slots")
    public List<SlotView> slotsByStatus(@RequestParam String status) {
        return passService.getSlotsByStatus(status);
    }

    /** 单个时隙详情。 */
    @GetMapping("/slots/{slotId}")
    public SlotView slot(@PathVariable long slotId) {
        return passService.getSlot(slotId);
    }

    /** 按分配 requestId 查时隙（幂等调用方回执查询）。 */
    @GetMapping("/slots/by-request/{requestId}")
    public SlotView slotByRequest(@PathVariable String requestId) {
        return passService.findSlotByRequestId(requestId);
    }
}
