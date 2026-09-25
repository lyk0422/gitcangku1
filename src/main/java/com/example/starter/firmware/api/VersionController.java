package com.example.starter.firmware.api;

import com.example.starter.firmware.service.VersionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 固件版本链：登记直接前置版本与版本链明细查询。
 */
@RestController
@RequestMapping("/api/versions")
public class VersionController {

    private final VersionService versionService;

    public VersionController(VersionService versionService) {
        this.versionService = versionService;
    }

    @PostMapping
    public VersionChainView register(@Valid @RequestBody RegisterVersionRequest request) {
        return versionService.register(request);
    }

    @GetMapping("/{version}/chain")
    public VersionChainView chain(@PathVariable String version) {
        return versionService.chain(version);
    }
}
