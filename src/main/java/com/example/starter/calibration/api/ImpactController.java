package com.example.starter.calibration.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.calibration.api.dto.ImpactResponse;
import com.example.starter.calibration.service.InvalidationService;

/**
 * 失效影响查询接口：只读，可按 impactVersion 重现激活时冻结的完整明细。
 */
@RestController
@RequestMapping("/api/impacts")
public class ImpactController {

    private final InvalidationService invalidations;

    public ImpactController(InvalidationService invalidations) {
        this.invalidations = invalidations;
    }

    /**
     * 按影响版本号查询冻结明细：200；不存在 404。
     */
    @GetMapping("/{impactVersion}")
    public ImpactResponse impact(@PathVariable String impactVersion) {
        return invalidations.impact(impactVersion);
    }
}
