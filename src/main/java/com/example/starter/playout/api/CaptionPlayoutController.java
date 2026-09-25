package com.example.starter.playout.api;

import com.example.starter.playout.CaptionPlayoutService;
import com.example.starter.playout.api.Dtos.BlackoutResponse;
import com.example.starter.playout.api.Dtos.CaptionDecisionResponse;
import com.example.starter.playout.api.Dtos.CaptionPublishRequest;
import com.example.starter.playout.api.Dtos.CaptionPublishResponse;
import com.example.starter.playout.api.Dtos.CaptionTextResponse;
import com.example.starter.playout.api.Dtos.ConfirmPlayoutRequest;
import com.example.starter.playout.api.Dtos.CreateBlackoutRequest;
import com.example.starter.playout.api.Dtos.CreateCaptionTextRequest;
import com.example.starter.playout.api.Dtos.CreateEmergencyCaptionRequest;
import com.example.starter.playout.api.Dtos.EmergencyCaptionResponse;
import com.example.starter.playout.api.Dtos.PlayoutReceiptResponse;
import com.example.starter.playout.api.Dtos.ReviewCaptionTextRequest;
import com.example.starter.playout.api.Dtos.RevokeCaptionRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
 * 紧急字幕与节目回执 REST API。成功响应统一 200；错误区分 400 参数错误、404 资源不存在、
 * 409 版本/幂等/窗口冲突、422 业务规则不满足（发布阻断在 blocking 中稳定列出区域与窗口）。
 */
@Validated
@RestController
@RequestMapping("/api")
public class CaptionPlayoutController {

    private final CaptionPlayoutService service;

    public CaptionPlayoutController(CaptionPlayoutService service) {
        this.service = service;
    }

    /** 创建字幕文本版本（内容冻结，PENDING），crawlKey 幂等。 */
    @PostMapping("/caption-texts")
    public CaptionTextResponse createCaptionText(@Valid @RequestBody CreateCaptionTextRequest request) {
        return service.createCaptionText(request);
    }

    /** 审核字幕文本版本（PENDING 唯一一次流转），crawlKey 幂等。 */
    @PostMapping("/caption-texts/{versionId}/review")
    public CaptionTextResponse reviewCaptionText(@PathVariable @NotBlank String versionId,
                                                 @Valid @RequestBody ReviewCaptionTextRequest request) {
        return service.reviewCaptionText(versionId, request);
    }

    /** 查询字幕文本版本与审核状态。 */
    @GetMapping("/caption-texts/{versionId}")
    public CaptionTextResponse getCaptionText(@PathVariable @NotBlank String versionId) {
        return service.getCaptionText(versionId);
    }

    /** 创建紧急字幕（整数优先级、UTC 窗口、规范化区域集合），crawlKey 幂等。 */
    @PostMapping("/emergency-captions")
    public EmergencyCaptionResponse createEmergencyCaption(
            @Valid @RequestBody CreateEmergencyCaptionRequest request) {
        return service.createEmergencyCaption(request);
    }

    /** 撤销紧急字幕（终态，不改写已发布快照），crawlKey 幂等。 */
    @PostMapping("/emergency-captions/{captionKey}/revoke")
    public EmergencyCaptionResponse revokeCaption(@PathVariable @NotBlank String captionKey,
                                                  @Valid @RequestBody RevokeCaptionRequest request) {
        return service.revokeCaption(captionKey, request);
    }

    /** 查询紧急字幕明细，ACTIVE / REVOKED 均返回。 */
    @GetMapping("/emergency-captions/{captionKey}")
    public EmergencyCaptionResponse getEmergencyCaption(@PathVariable @NotBlank String captionKey) {
        return service.getEmergencyCaption(captionKey);
    }

    /** 创建黑屏窗口（UTC 左闭右开），crawlKey 幂等。 */
    @PostMapping("/blackout-windows")
    public BlackoutResponse createBlackout(@Valid @RequestBody CreateBlackoutRequest request) {
        return service.createBlackout(request);
    }

    /** 查询黑屏窗口明细。 */
    @GetMapping("/blackout-windows/{blackoutKey}")
    public BlackoutResponse getBlackout(@PathVariable @NotBlank String blackoutKey) {
        return service.getBlackout(blackoutKey);
    }

    /** 字幕感知发布：逐区域逐时间片解析最高优先级字幕并固化快照；阻断时 422 稳定列出原因。 */
    @PostMapping("/channels/{channelId}/drafts/{businessDay}/caption-publish")
    public CaptionPublishResponse captionPublish(@PathVariable @NotBlank String channelId,
                                                 @PathVariable String businessDay,
                                                 @Valid @RequestBody CaptionPublishRequest request) {
        return service.publishWithCaptions(channelId, parseBusinessDay(businessDay), request);
    }

    /** 查询某区域某时刻的实时字幕决策（不改写任何状态）。 */
    @GetMapping("/channels/{channelId}/caption-decision")
    public CaptionDecisionResponse captionDecision(
            @PathVariable @NotBlank String channelId,
            @RequestParam @NotBlank String region,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime at) {
        return service.captionDecision(channelId, region, at);
    }

    /** 查询指定发布快照（节目素材与固化字幕决策）。 */
    @GetMapping("/publications/{publicationId}")
    public CaptionPublishResponse getPublication(@PathVariable long publicationId) {
        return service.getPublication(publicationId);
    }

    /** 查询频道某业务日最新发布快照。 */
    @GetMapping("/channels/{channelId}/drafts/{businessDay}/latest-publication")
    public CaptionPublishResponse getLatestPublication(@PathVariable @NotBlank String channelId,
                                                       @PathVariable String businessDay) {
        return service.getLatestPublication(channelId, parseBusinessDay(businessDay));
    }

    /** 按发布快照确认播放并生成回执，crawlKey 幂等；字幕结束端点恰好时不再覆盖。 */
    @PostMapping("/playout-receipts")
    public PlayoutReceiptResponse confirmPlayout(@Valid @RequestBody ConfirmPlayoutRequest request) {
        return service.confirmPlayout(request);
    }

    private static LocalDate parseBusinessDay(String businessDay) {
        try {
            return LocalDate.parse(businessDay);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("业务日格式应为 yyyy-MM-dd: " + businessDay);
        }
    }
}
