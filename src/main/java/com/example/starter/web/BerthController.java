package com.example.starter.web;

import com.example.starter.domain.Allocation;
import com.example.starter.domain.Berth;
import com.example.starter.repository.BerthRepository;
import com.example.starter.service.BerthService;
import com.example.starter.web.dto.AllocationResponse;
import com.example.starter.web.dto.ApplyRequest;
import com.example.starter.web.dto.BerthTimelineResponse;
import com.example.starter.web.dto.PublishTideRequest;
import com.example.starter.web.dto.ReviewRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 泊位靠泊申请、潮高版本发布与复核接口。
 */
@RestController
@RequestMapping("/api/berths")
public class BerthController {

    private final BerthService berthService;

    private final BerthRepository berthRepository;

    /**
     * @param berthService    泊位业务服务
     * @param berthRepository 泊位仓储（用于详情拼装泊位编码）
     */
    public BerthController(BerthService berthService, BerthRepository berthRepository) {
        this.berthService = berthService;
        this.berthRepository = berthRepository;
    }

    /**
     * 提交靠泊申请。
     *
     * @param request 申请参数
     * @return 申请详情
     */
    @PostMapping("/allocations")
    @ResponseStatus(HttpStatus.CREATED)
    public AllocationResponse apply(@Valid @RequestBody ApplyRequest request) {
        Allocation allocation = berthService.apply(request);
        return AllocationResponse.from(allocation, berthRepository.findById(allocation.getBerthId())
                .orElse(null));
    }

    /**
     * 发布新的潮高版本。
     *
     * @param request 版本与潮高区间
     * @return 新版本号
     */
    @PostMapping("/tide-versions")
    @ResponseStatus(HttpStatus.CREATED)
    public int publishTide(@Valid @RequestBody PublishTideRequest request) {
        return berthService.publishTide(request);
    }

    /**
     * 对待复核申请进行人工复核。
     *
     * @param request 复核请求
     * @return 更新后的申请详情
     */
    @PostMapping("/allocations/review")
    public AllocationResponse review(@Valid @RequestBody ReviewRequest request) {
        Allocation allocation = berthService.review(request);
        Berth berth = berthRepository.findById(allocation.getBerthId()).orElse(null);
        return AllocationResponse.from(allocation, berth);
    }

    /**
     * 查询申请详情。
     *
     * @param requestId 幂等键
     * @return 申请详情
     */
    @GetMapping("/allocations/{requestId}")
    public AllocationResponse get(@PathVariable String requestId) {
        Allocation allocation = berthService.getByRequestId(requestId);
        Berth berth = berthRepository.findById(allocation.getBerthId()).orElse(null);
        return AllocationResponse.from(allocation, berth);
    }

    /**
     * 查询泊位时间轴。
     *
     * @param berthCode 泊位编码
     * @return 时间轴
     */
    @GetMapping("/{berthCode}/timeline")
    public BerthTimelineResponse timeline(@PathVariable String berthCode) {
        return berthService.getTimeline(berthCode);
    }
}
