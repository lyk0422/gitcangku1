package com.example.starter.calibration.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.calibration.api.dto.CreateInvalidationRequest;
import com.example.starter.calibration.api.dto.InvalidationClosureResponse;
import com.example.starter.calibration.service.InvalidationService;

/**
 * 标准器失效接口：失效预览（只读闭包）、创建失效单、双人确认激活、单据查询、按影响版本重现。
 * 创建与确认通过 X-Actor-Id 标识质量人员；创建通过 X-Request-Id 提供幂等键。
 */
@RestController
@RequestMapping("/api")
public class InvalidationController {

    private final InvalidationService invalidations;

    public InvalidationController(InvalidationService invalidations) {
        this.invalidations = invalidations;
    }

    /**
     * 失效预览：稳定排序返回完整闭包但不写数据。
     */
    @PostMapping("/invalidations/preview")
    public InvalidationClosureResponse preview(@RequestBody CreateInvalidationRequest request) {
        return invalidations.preview(request);
    }

    /**
     * 创建失效单：200 返回首次闭包快照；requestId 同参重放，异参 409；expectedVersion 过期 409。
     */
    @PostMapping("/invalidations")
    public InvalidationClosureResponse create(@RequestBody CreateInvalidationRequest request,
                                              @RequestHeader("X-Request-Id") String requestId,
                                              @RequestHeader("X-Actor-Id") String actor) {
        return invalidations.create(request, requestId, actor);
    }

    /**
     * 质量人员确认：同一人重复确认 409；两名不同人员齐备即整体冻结；闭包变化整单 409。
     */
    @PostMapping("/invalidations/{key}/confirmations")
    public InvalidationClosureResponse confirm(@PathVariable("key") String key,
                                               @RequestHeader("X-Actor-Id") String actor) {
        return invalidations.confirm(key, actor);
    }

    /**
     * 按失效单业务键查询闭包：待激活返回首次快照，已激活返回冻结结果。
     */
    @GetMapping("/invalidations/{key}")
    public InvalidationClosureResponse getByKey(@PathVariable("key") String key) {
        return invalidations.getByKey(key);
    }

    /**
     * 影响查询（只读）：按 impactVersion 重现冻结闭包、最短血缘路径与冻结后状态。
     */
    @GetMapping("/impacts/{impactVersion}")
    public InvalidationClosureResponse getByImpactVersion(
            @PathVariable("impactVersion") String impactVersion) {
        return invalidations.getByImpactVersion(impactVersion);
    }
}
