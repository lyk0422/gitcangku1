package com.example.starter.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.service.BerthAllocationService;

import jakarta.validation.Valid;

/**
 * 泊位靠泊分配接口：申请、潮高版本发布、复核、申请详情与泊位时间轴。
 */
@RestController
@RequestMapping("/api/berths")
public class BerthController {

    private final BerthAllocationService service;

    public BerthController(BerthAllocationService service) {
        this.service = service;
    }

    /**
     * 提交靠泊申请并完成校核分配。
     */
    @PostMapping("/applications")
    @ResponseStatus(HttpStatus.CREATED)
    public ApplicationResponse apply(@Valid @RequestBody ApplyRequest request) {
        return service.apply(request);
    }

    /**
     * 发布新的潮高版本（潮高修订），并联动未开始的已确认靠泊。
     */
    @PostMapping("/tide-versions")
    @ResponseStatus(HttpStatus.CREATED)
    public TideVersionResponse publishTide(@Valid @RequestBody TidePublishRequest request) {
        return service.publishTide(request);
    }

    /**
     * 对待复核申请进行人工复核。
     */
    @PostMapping("/applications/{requestId}/review")
    public ApplicationResponse review(@PathVariable String requestId,
                                      @Valid @RequestBody ReviewRequest request) {
        if (!requestId.equals(request.requestId())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "REQUEST_ID_MISMATCH",
                    "路径与请求体 requestId 不一致");
        }
        return service.review(request);
    }

    /**
     * 查询申请详情（潮高版本、最低潮高、余量、拒绝原因）。
     */
    @GetMapping("/applications/{requestId}")
    public ApplicationResponse getApplication(@PathVariable String requestId) {
        return service.getApplication(requestId);
    }

    /**
     * 查询泊位时间轴：泊位、当前潮高版本区间及该泊位申请。
     */
    @GetMapping("/{berthCode}/timeline")
    public BerthTimelineResponse timeline(@PathVariable String berthCode) {
        return service.berthTimeline(berthCode);
    }
}
