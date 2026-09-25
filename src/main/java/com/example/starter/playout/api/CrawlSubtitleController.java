package com.example.starter.playout.api;

import com.example.starter.playout.CrawlSubtitleService;
import com.example.starter.playout.api.Dtos.ApproveSubtitleTextRequest;
import com.example.starter.playout.api.Dtos.BlackoutResponse;
import com.example.starter.playout.api.Dtos.CreateBlackoutRequest;
import com.example.starter.playout.api.Dtos.CreateEmergencySubtitleRequest;
import com.example.starter.playout.api.Dtos.CreateSubtitleTextRequest;
import com.example.starter.playout.api.Dtos.EmergencySubtitleResponse;
import com.example.starter.playout.api.Dtos.PlaybackReceiptRequest;
import com.example.starter.playout.api.Dtos.PlaybackReceiptResponse;
import com.example.starter.playout.api.Dtos.PublicationSnapshotResponse;
import com.example.starter.playout.api.Dtos.PublishBlockResponse;
import com.example.starter.playout.api.Dtos.RegionSubtitleDecisionResponse;
import com.example.starter.playout.api.Dtos.SubtitleTextResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

/**
 * 紧急字幕（crawl）REST API：黑屏、文本审核、字幕、区域决策、发布快照、阻断原因与播放回执。
 */
@Validated
@RestController
@RequestMapping("/api")
public class CrawlSubtitleController {

    private final CrawlSubtitleService service;

    public CrawlSubtitleController(CrawlSubtitleService service) {
        this.service = service;
    }

    /** 创建黑屏窗口（幂等）。 */
    @PostMapping("/blackouts")
    public BlackoutResponse createBlackout(@Valid @RequestBody CreateBlackoutRequest request) {
        return service.createBlackout(request);
    }

    /** 创建字幕文本版本（幂等）。 */
    @PostMapping("/subtitle-texts")
    public SubtitleTextResponse createSubtitleText(
            @Valid @RequestBody CreateSubtitleTextRequest request) {
        return service.createSubtitleText(request);
    }

    /** 审核字幕文本版本（幂等）。 */
    @PostMapping("/subtitle-texts/{textKey}/versions/{version}/approve")
    public SubtitleTextResponse approveSubtitleText(@PathVariable @NotBlank String textKey,
                                                    @PathVariable @NotNull @Positive int version,
                                                    @Valid @RequestBody
                                                    ApproveSubtitleTextRequest request) {
        return service.approveSubtitleText(textKey, version, request);
    }

    /** 查询字幕文本版本。 */
    @GetMapping("/subtitle-texts/{textKey}/versions/{version}")
    public SubtitleTextResponse subtitleText(@PathVariable @NotBlank String textKey,
                                             @PathVariable @NotNull @Positive int version) {
        return service.getSubtitleText(textKey, version);
    }

    /** 创建紧急字幕（幂等）。 */
    @PostMapping("/emergency-subtitles")
    public EmergencySubtitleResponse createSubtitle(
            @Valid @RequestBody CreateEmergencySubtitleRequest request) {
        return service.createSubtitle(request);
    }

    /** 撤销紧急字幕（幂等）。 */
    @PostMapping("/emergency-subtitles/{subtitleKey}/revoke")
    public EmergencySubtitleResponse revokeSubtitle(@PathVariable @NotBlank String subtitleKey,
                                                    @Valid @RequestBody
                                                    Dtos.RevokeSubtitleRequest request) {
        return service.revokeSubtitle(subtitleKey, request.requestId());
    }

    /** 查询紧急字幕明细。 */
    @GetMapping("/emergency-subtitles/{subtitleKey}")
    public EmergencySubtitleResponse subtitle(@PathVariable @NotBlank String subtitleKey) {
        return service.getSubtitle(subtitleKey);
    }

    /** 查询某频道某区域某时刻的字幕决策。 */
    @GetMapping("/channels/{channelId}/subtitle-decision")
    public RegionSubtitleDecisionResponse subtitleDecision(
            @PathVariable @NotBlank String channelId,
            @RequestParam @NotBlank String region,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime at) {
        return service.regionDecision(channelId, region, at);
    }

    /** 查询某业务日最新发布快照（节目素材 + 固化字幕）。 */
    @GetMapping("/channels/{channelId}/snapshots/{businessDay}/latest")
    public PublicationSnapshotResponse latestSnapshot(@PathVariable @NotBlank String channelId,
                                                      @PathVariable String businessDay) {
        return service.latestSnapshot(channelId, parseBusinessDay(businessDay));
    }

    /** 按发布 ID 查询快照。 */
    @GetMapping("/snapshots/{publicationId}")
    public PublicationSnapshotResponse snapshot(@PathVariable long publicationId) {
        return service.snapshot(publicationId);
    }

    /** 提交播放回执（crawlKey 幂等）。 */
    @PostMapping("/playback-receipts")
    public PlaybackReceiptResponse receipt(@Valid @RequestBody PlaybackReceiptRequest request) {
        return service.confirmReceipt(request);
    }

    /** 查询最近一次发布阻断原因。 */
    @GetMapping("/channels/{channelId}/drafts/{businessDay}/publish-block")
    public PublishBlockResponse publishBlock(@PathVariable @NotBlank String channelId,
                                             @PathVariable String businessDay) {
        return service.latestBlock(channelId, parseBusinessDay(businessDay));
    }

    private static LocalDate parseBusinessDay(String businessDay) {
        try {
            return LocalDate.parse(businessDay);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("业务日格式应为 yyyy-MM-dd: " + businessDay);
        }
    }
}
