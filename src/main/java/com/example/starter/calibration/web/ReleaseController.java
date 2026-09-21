package com.example.starter.calibration.web;

import com.example.starter.calibration.service.ReleaseService;
import com.example.starter.calibration.web.dto.ReleaseRequest;
import com.example.starter.calibration.web.dto.ReleaseResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 放行接口：原子批量放行，放行人通过 X-Actor-Id 提供。
 */
@RestController
@RequestMapping("/api/releases")
public class ReleaseController {

    private final ReleaseService releaseService;

    public ReleaseController(ReleaseService releaseService) {
        this.releaseService = releaseService;
    }

    /**
     * 批量放行。任一条目校验失败则整批拒绝并返回各项原因（404/409）。
     */
    @PostMapping
    public ReleaseResponse release(
            @RequestHeader("X-Actor-Id") String actor,
            @Valid @RequestBody ReleaseRequest request) {
        return ReleaseResponse.from(releaseService.releaseBatch(request.ids(), actor));
    }
}
