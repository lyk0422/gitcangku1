package com.example.snowpack.web;

import com.example.snowpack.service.ReadingService;
import com.example.snowpack.service.ReadingService.ClosureResult;
import com.example.snowpack.service.ReadingService.ErrataResult;
import com.example.snowpack.service.ReadingService.ReadingResult;
import com.example.snowpack.service.ReviewService;
import com.example.snowpack.service.ReviewService.ReviewResult;
import com.example.snowpack.service.StatusService;
import com.example.snowpack.service.StatusService.ZoneStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 雪崩控制区巡查接口：雪况上报、封闭命令、解除审核与状态查询。 */
@RestController
@RequestMapping("/api/zones/{zoneId}")
public class ZoneController {

    private final ReadingService readingService;
    private final ReviewService reviewService;
    private final StatusService statusService;

    public ZoneController(ReadingService readingService, ReviewService reviewService,
                          StatusService statusService) {
        this.readingService = readingService;
        this.reviewService = reviewService;
        this.statusService = statusService;
    }

    /** 雪况上报请求。hourUtc 必须为整点 UTC 时刻。 */
    public record PostReadingRequest(
            @NotBlank @Size(max = 64) String requestId,
            @NotNull Instant hourUtc,
            @NotNull @Min(0) Integer snowDepthCm,
            @NotNull @Min(0) Integer newSnowCm) {
    }

    /** 人工封闭命令请求。 */
    public record PostClosureRequest(
            @NotBlank @Size(max = 64) String requestId,
            @NotBlank @Size(max = 256) String reason) {
    }

    /** 勘误追加请求。 */
    public record PostErrataRequest(
            @NotBlank @Size(max = 64) String requestId,
            @NotBlank @Size(max = 512) String note,
            @Size(max = 32) String reviewResult) {
    }

    /** 审核确认请求。 */
    public record ConfirmRequest(
            @NotBlank @Size(max = 64) String requestId,
            @NotBlank @Size(max = 64) String reviewer) {
    }

    @PostMapping("/readings")
    public ReadingResult postReading(@PathVariable String zoneId,
                                     @Valid @RequestBody PostReadingRequest request) {
        return readingService.postReading(zoneId, request.requestId(), request.hourUtc(),
                request.snowDepthCm(), request.newSnowCm());
    }

    @PostMapping("/readings/{readingId}/errata")
    public ErrataResult postErrata(@PathVariable String zoneId, @PathVariable long readingId,
                                   @Valid @RequestBody PostErrataRequest request) {
        return readingService.postErrata(zoneId, readingId, request.requestId(), request.note(),
                request.reviewResult());
    }

    @PostMapping("/closures")
    public ClosureResult postClosure(@PathVariable String zoneId,
                                     @Valid @RequestBody PostClosureRequest request) {
        return readingService.postClosure(zoneId, request.requestId(), request.reason());
    }

    @PostMapping("/release-reviews")
    public ReviewResult confirmRelease(@PathVariable String zoneId,
                                       @Valid @RequestBody ConfirmRequest request) {
        return reviewService.confirm(zoneId, request.requestId(), request.reviewer());
    }

    @GetMapping("/status")
    public ZoneStatus status(@PathVariable String zoneId) {
        return statusService.status(zoneId);
    }
}