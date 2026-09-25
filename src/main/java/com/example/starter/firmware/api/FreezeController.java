package com.example.starter.firmware.api;

import com.example.starter.firmware.service.FreezeService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 固件发布冻结令：确认人登记、冻结令创建/批量/修订/撤销、有效冻结与范围命中查询、
 * 任务冻结快照（经任务接口）、紧急例外与撤销影响查询。
 */
@RestController
@RequestMapping("/api/freeze")
public class FreezeController {

    private final FreezeService freezeService;

    public FreezeController(FreezeService freezeService) {
        this.freezeService = freezeService;
    }

    @PostMapping("/confirmers")
    public RegisterConfirmerRequest.ConfirmerView registerConfirmer(
            @Valid @RequestBody RegisterConfirmerRequest request) {
        return freezeService.registerConfirmer(request);
    }

    @GetMapping("/confirmers")
    public List<String> listConfirmers() {
        return freezeService.listConfirmers();
    }

    @PostMapping("/orders")
    public FreezeView create(@Valid @RequestBody CreateFreezeRequest request) {
        return freezeService.createFreeze(request);
    }

    @PostMapping("/orders/batch")
    public FreezeBatchResponse createBatch(@Valid @RequestBody CreateFreezeRequest.Batch request) {
        return freezeService.createFreezeBatch(request);
    }

    @PostMapping("/orders/{freezeId}/revise")
    public FreezeView revise(@PathVariable long freezeId, @Valid @RequestBody ReviseFreezeRequest request) {
        return freezeService.revise(freezeId, request);
    }

    @PostMapping("/orders/{freezeId}/revoke")
    public FreezeService.RevokeResult revoke(@PathVariable long freezeId,
                                             @Valid @RequestBody RequestIdBody request) {
        return freezeService.revoke(freezeId, request.requestId());
    }

    @GetMapping("/orders/active")
    public List<FreezeView> active() {
        return freezeService.activeFreezes();
    }

    @GetMapping("/orders/{freezeId}")
    public FreezeView get(@PathVariable long freezeId) {
        return freezeService.get(freezeId);
    }

    @GetMapping("/orders/{freezeId}/hit")
    public FreezeService.FreezeHit hit(@PathVariable long freezeId,
                                       @RequestParam(required = false) String model,
                                       @RequestParam(required = false) Long releaseId) {
        return freezeService.checkHit(freezeId, model, releaseId);
    }

    @GetMapping("/orders/{freezeId}/exceptions")
    public List<EmergencyExceptionView> exceptions(@PathVariable long freezeId) {
        return freezeService.exceptions(freezeId);
    }
}
