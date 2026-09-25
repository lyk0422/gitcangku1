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
 * 冻结令：创建、批量创建、修订、撤销，以及有效冻结令、范围命中与紧急例外查询。
 */
@RestController
@RequestMapping("/api/freeze-orders")
public class FreezeController {

    private final FreezeService freezeService;

    public FreezeController(FreezeService freezeService) {
        this.freezeService = freezeService;
    }

    @PostMapping
    public FreezeOrderView create(@Valid @RequestBody CreateFreezeRequest request) {
        return freezeService.create(request);
    }

    @PostMapping("/batch")
    public BatchFreezeResponse createBatch(@Valid @RequestBody BatchCreateFreezeRequest request) {
        return freezeService.createBatch(request);
    }

    @PostMapping("/{freezeId}/revise")
    public FreezeOrderView revise(@PathVariable long freezeId, @Valid @RequestBody ReviseFreezeRequest request) {
        return freezeService.revise(freezeId, request);
    }

    @PostMapping("/{freezeId}/revoke")
    public FreezeOrderView revoke(@PathVariable long freezeId, @Valid @RequestBody RequestIdBody request) {
        return freezeService.revoke(freezeId, request.requestId());
    }

    @GetMapping
    public List<FreezeOrderView> list(@RequestParam(required = false, defaultValue = "false") boolean effective) {
        return freezeService.list(effective);
    }

    @GetMapping("/hit")
    public FreezeHitResponse hit(@RequestParam(required = false) String model,
                                 @RequestParam(required = false) Long releaseId) {
        return freezeService.hit(model, releaseId);
    }

    @GetMapping("/exceptions")
    public List<ExceptionRecordView> exceptions() {
        return freezeService.exceptions();
    }

    @GetMapping("/{freezeId}")
    public FreezeOrderView get(@PathVariable long freezeId) {
        return freezeService.get(freezeId);
    }
}
