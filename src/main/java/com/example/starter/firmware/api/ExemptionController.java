package com.example.starter.firmware.api;

import com.example.starter.firmware.service.CompatibilityService;
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
 * 发布单设备组模块豁免配置、查询与历史（只读）。
 */
@RestController
@RequestMapping("/api/releases/{releaseId}/exemptions")
public class ExemptionController {

    private final CompatibilityService compatibilityService;

    public ExemptionController(CompatibilityService compatibilityService) {
        this.compatibilityService = compatibilityService;
    }

    @PostMapping
    public ExemptionView configure(@PathVariable long releaseId,
                                   @Valid @RequestBody ExemptionRequest request) {
        return compatibilityService.configureExemption(releaseId, request);
    }

    @GetMapping
    public ExemptionView get(@PathVariable long releaseId, @RequestParam String group) {
        return compatibilityService.getExemption(releaseId, group);
    }

    @GetMapping("/history")
    public List<ExemptionHistoryView> history(@PathVariable long releaseId, @RequestParam String group) {
        return compatibilityService.getExemptionHistory(releaseId, group);
    }
}
