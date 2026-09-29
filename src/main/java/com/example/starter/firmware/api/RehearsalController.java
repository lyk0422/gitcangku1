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

import java.util.List;

/**
 * 固件回滚演练：开始、批量提交结果、明细、历史与门禁诊断查询。
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
    public RehearsalView submitResults(@PathVariable long rehearsalId,
                                       @Valid @RequestBody RehearsalResultRequest request) {
        return rehearsalService.submitResults(rehearsalId, request);
    }

    @GetMapping("/{rehearsalId}")
    public RehearsalView detail(@PathVariable long rehearsalId) {
        return rehearsalService.detail(rehearsalId);
    }

    @GetMapping
    public RehearsalHistoryResponse history(@RequestParam(required = false) String model,
                                            @RequestParam(required = false) String targetVersion) {
        return rehearsalService.history(model, targetVersion);
    }

    /**
     * 门禁诊断：可按型号、目标版本过滤，按授予顺序稳定排序。
     */
    @GetMapping("/gates")
    public List<GateView> gates(@RequestParam(required = false) String model,
                                @RequestParam(required = false) String targetVersion) {
        return rehearsalService.gates(model, targetVersion);
    }
}
