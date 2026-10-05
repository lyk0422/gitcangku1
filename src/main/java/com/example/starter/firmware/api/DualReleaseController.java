package com.example.starter.firmware.api;

import com.example.starter.firmware.service.ReleaseService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 双分区试启动发布单：设备试启动新分区，60 秒内健康确认才正式切换。
 */
@RestController
@RequestMapping("/api/dual-releases")
public class DualReleaseController {

    private final ReleaseService releaseService;

    public DualReleaseController(ReleaseService releaseService) {
        this.releaseService = releaseService;
    }

    @PostMapping
    public ReleaseView create(@Valid @RequestBody CreateDualReleaseRequest request) {
        return releaseService.createDual(request);
    }
}