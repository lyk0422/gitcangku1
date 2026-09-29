package com.example.starter.firmware.api;

import com.example.starter.firmware.service.RehearsalService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 固件回滚演练与正式投放前门禁：开始演练、提交结果、明细、历史与门禁诊断查询。
 */
@RestController
@RequestMapping("/api/rehearsals")
public class RehearsalController {

    private final RehearsalService rehearsalService;

    public RehearsalController(RehearsalService rehearsalService) {
        this.rehearsalService = rehearsalService;
    }

    @PostMapping
    public RehearsalView start(@Valid @RequestBody StartRehearsalRequest request) {
        return rehearsalService.start(request);
    }

    @PostMapping("/{rehearsalId}/results")
    public RehearsalDetailResponse complete(@PathVariable long rehearsalId,
                                            @Valid @RequestBody CompleteRehearsalRequest request) {
        return rehearsalService.complete(rehearsalId, request);
    }

    @GetMapping("/{rehearsalId}")
    public RehearsalDetailResponse detail(@PathVariable long rehearsalId) {
        return rehearsalService.detail(rehearsalId);
    }

    @GetMapping
    public RehearsalHistoryResponse history(@RequestParam(required = false) String model,
                                            @RequestParam(required = false) String targetVersion,
                                            @RequestParam(required = false) String rollbackVersion) {
        return rehearsalService.history(model, targetVersion, rollbackVersion);
    }

    @GetMapping("/gates/diagnostics")
    public GateDiagnosticsResponse gates(@RequestParam(required = false) String model,
                                         @RequestParam(required = false) String targetVersion,
                                         @RequestParam(required = false) String rollbackVersion) {
        return rehearsalService.gates(model, targetVersion, rollbackVersion);
    }
}
