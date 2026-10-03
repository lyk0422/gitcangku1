package com.example.starter.snow;

import com.example.starter.snow.dto.ClosureRequest;
import com.example.starter.snow.dto.ConfirmationRequest;
import com.example.starter.snow.dto.ErrataRequest;
import com.example.starter.snow.dto.ReadingRequest;
import com.example.starter.snow.dto.ReviewCreateRequest;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 雪崩控制区合成雪况与封闭/解除接口；所有数据为合成记录，不构成真实安全预报。
 */
@RestController
@RequestMapping("/api")
public class SnowpackController {

    private final SnowpackService snowpackService;
    private final UnsealService unsealService;
    private final StatusService statusService;

    public SnowpackController(SnowpackService snowpackService, UnsealService unsealService,
                              StatusService statusService) {
        this.snowpackService = snowpackService;
        this.unsealService = unsealService;
        this.statusService = statusService;
    }

    /** 上报某一 UTC 整点的合成雪况读数。 */
    @PostMapping("/regions/{regionId}/readings")
    public Map<String, Object> reportReading(@PathVariable String regionId,
                                             @Valid @RequestBody ReadingRequest req) {
        return snowpackService.reportReading(regionId, req);
    }

    /** 人工下发封闭命令。 */
    @PostMapping("/regions/{regionId}/closures")
    public Map<String, Object> createClosure(@PathVariable String regionId,
                                             @Valid @RequestBody ClosureRequest req) {
        return snowpackService.createClosure(regionId, req);
    }

    /** 发起解除封闭双人审核。 */
    @PostMapping("/regions/{regionId}/unseal-reviews")
    public Map<String, Object> createReview(@PathVariable String regionId,
                                            @Valid @RequestBody ReviewCreateRequest req) {
        return unsealService.createReview(regionId, req);
    }

    /** 审核确认：两名不同审核员先后调用，第二人确认后生成解除版本。 */
    @PostMapping("/unseal-reviews/{reviewId}/confirmations")
    public Map<String, Object> confirm(@PathVariable long reviewId,
                                       @Valid @RequestBody ConfirmationRequest req) {
        return unsealService.confirm(reviewId, req);
    }

    /** 对旧读数追加勘误与复核结论。 */
    @PostMapping("/readings/{readingId}/errata")
    public Map<String, Object> appendErrata(@PathVariable long readingId,
                                            @Valid @RequestBody ErrataRequest req) {
        return snowpackService.appendErrata(readingId, req);
    }

    /** 查询区域状态、连续整点读数、审核人与封闭/解除时间线。 */
    @GetMapping("/regions/{regionId}/status")
    public Map<String, Object> getStatus(@PathVariable String regionId) {
        return statusService.getStatus(regionId);
    }
}