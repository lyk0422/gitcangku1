package com.example.starter.firmware.api;

import com.example.starter.firmware.service.CompatibilityService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * MODULE_BLOCKED 阻断诊断查询（只读，不修改任何状态）。可按发布单或设备查询。
 */
@RestController
@RequestMapping("/api/module-blocks")
public class ModuleBlockController {

    private final CompatibilityService compatibilityService;

    public ModuleBlockController(CompatibilityService compatibilityService) {
        this.compatibilityService = compatibilityService;
    }

    @GetMapping("/releases/{releaseId}")
    public List<BlockRecordView> byRelease(@PathVariable long releaseId) {
        return compatibilityService.listBlocksByRelease(releaseId);
    }

    @GetMapping("/devices/{deviceId}")
    public List<BlockRecordView> byDevice(@PathVariable String deviceId) {
        return compatibilityService.listBlocksByDevice(deviceId);
    }
}
