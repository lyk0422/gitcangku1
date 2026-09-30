package com.example.starter.firmware.api;

import com.example.starter.firmware.service.FreezeService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 版本正式发布冻结：创建冻结、申请恢复、异于人审批恢复、当前冻结与审计查询。
 */
@RestController
@RequestMapping("/api/freezes")
public class FreezeController {

    private final FreezeService freezeService;

    public FreezeController(FreezeService freezeService) {
        this.freezeService = freezeService;
    }

    @PostMapping
    public FreezeView freeze(@Valid @RequestBody FreezeVersionRequest request) {
        return freezeService.freeze(request);
    }

    @GetMapping("/{version}")
    public FreezeStatusResponse status(@PathVariable String version) {
        return freezeService.status(version);
    }

    @PostMapping("/{version}/restores")
    public RestoreView requestRestore(@PathVariable String version,
                                      @Valid @RequestBody RestoreFreezeRequest request) {
        return freezeService.requestRestore(version, request);
    }

    @PostMapping("/{version}/restores/{restoreId}/approve")
    public RestoreView approve(@PathVariable String version, @PathVariable long restoreId,
                               @Valid @RequestBody ApproveRestoreRequest request) {
        return freezeService.approve(version, restoreId, request);
    }
}
