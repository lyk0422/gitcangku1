package com.example.starter.playout.api;

import com.example.starter.playout.PlayoutService;
import com.example.starter.playout.api.Dtos.AssetResponse;
import com.example.starter.playout.api.Dtos.BlackoutWindowResponse;
import com.example.starter.playout.api.Dtos.ChannelResponse;
import com.example.starter.playout.api.Dtos.CreateAssetRequest;
import com.example.starter.playout.api.Dtos.CreateBlackoutWindowRequest;
import com.example.starter.playout.api.Dtos.CreateChannelRequest;
import com.example.starter.playout.api.Dtos.CreateEmergencyOverrideRequest;
import com.example.starter.playout.api.Dtos.CreateGrantRequest;
import com.example.starter.playout.api.Dtos.DraftResponse;
import com.example.starter.playout.api.Dtos.EmergencyOverrideResponse;
import com.example.starter.playout.api.Dtos.GrantResponse;
import com.example.starter.playout.api.Dtos.CancelEmergencyOverrideRequest;
import com.example.starter.playout.api.Dtos.PlayoutDecisionResponse;
import com.example.starter.playout.api.Dtos.PublicationSnapshotResponse;
import com.example.starter.playout.api.Dtos.PublishRequest;
import com.example.starter.playout.api.Dtos.PublishResponse;
import com.example.starter.playout.api.Dtos.RegionPlayoutDecisionResponse;
import com.example.starter.playout.api.Dtos.ReplaceDraftRequest;
import com.example.starter.playout.api.Dtos.RevokeGrantRequest;
import com.example.starter.playout.api.Dtos.SpliceDiagnosticsResponse;
import com.example.starter.playout.api.Dtos.WithdrawAssetRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

/**
 * 播出编排 REST API。成功响应统一 200；错误区分 400 参数错误、404 资源不存在、
 * 409 版本/幂等冲突、422 业务规则不满足。
 */
@Validated
@RestController
@RequestMapping("/api")
public class PlayoutController {

    private final PlayoutService service;

    public PlayoutController(PlayoutService service) {
        this.service = service;
    }

    /** 创建素材。 */
    @PostMapping("/assets")
    public AssetResponse createAsset(@Valid @RequestBody CreateAssetRequest request) {
        return service.createAsset(request);
    }

    /** 撤回素材（终态，幂等）；撤回素材不得用于新插播发布。 */
    @PostMapping("/assets/{assetId}/withdraw")
    public AssetResponse withdrawAsset(@PathVariable @NotBlank String assetId,
                                       @Valid @RequestBody WithdrawAssetRequest request) {
        return service.withdrawAsset(assetId, request.requestId());
    }

    /** 创建频道。 */
    @PostMapping("/channels")
    public ChannelResponse createChannel(@Valid @RequestBody CreateChannelRequest request) {
        return service.createChannel(request);
    }

    /** 创建授权。 */
    @PostMapping("/grants")
    public GrantResponse createGrant(@Valid @RequestBody CreateGrantRequest request) {
        return service.createGrant(request);
    }

    /** 撤销授权（幂等）。 */
    @PostMapping("/grants/{grantId}/revoke")
    public GrantResponse revokeGrant(@PathVariable long grantId,
                                     @Valid @RequestBody RevokeGrantRequest request) {
        return service.revokeGrant(grantId, request.requestId());
    }

    /** 整份替换频道某业务日草稿（乐观锁 + 幂等）。 */
    @PutMapping("/channels/{channelId}/drafts/{businessDay}")
    public DraftResponse replaceDraft(@PathVariable @NotBlank String channelId,
                                      @PathVariable String businessDay,
                                      @Valid @RequestBody ReplaceDraftRequest request) {
        return service.replaceDraft(channelId, parseBusinessDay(businessDay), request);
    }

    /** 发布频道某业务日草稿（乐观锁 + 幂等）。 */
    @PostMapping("/channels/{channelId}/drafts/{businessDay}/publish")
    public PublishResponse publish(@PathVariable @NotBlank String channelId,
                                   @PathVariable String businessDay,
                                   @Valid @RequestBody PublishRequest request) {
        return service.publish(channelId, parseBusinessDay(businessDay), request);
    }

    /** 按频道与时刻查询播出决定。 */
    @GetMapping("/channels/{channelId}/playout")
    public PlayoutDecisionResponse playout(@PathVariable @NotBlank String channelId,
                                           @RequestParam
                                           @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                                           OffsetDateTime at) {
        return service.playoutDecision(channelId, at);
    }

    /** 创建限时紧急插播（创建即 ACTIVE，携带 requestId 幂等）。 */
    @PostMapping("/emergency-overrides")
    public EmergencyOverrideResponse createEmergencyOverride(
            @Valid @RequestBody CreateEmergencyOverrideRequest request) {
        return service.createEmergencyOverride(request);
    }

    /** 取消紧急插播（仅 ACTIVE 可取消，携带 requestId 幂等）。 */
    @PostMapping("/emergency-overrides/{overrideKey}/cancel")
    public EmergencyOverrideResponse cancelEmergencyOverride(
            @PathVariable @NotBlank String overrideKey,
            @Valid @RequestBody CancelEmergencyOverrideRequest request) {
        return service.cancelEmergencyOverride(overrideKey, request.requestId());
    }

    /** 查询紧急插播明细，ACTIVE/CANCELLED 均返回，保留取消情况与原授权关联。 */
    @GetMapping("/emergency-overrides/{overrideKey}")
    public EmergencyOverrideResponse emergencyOverride(@PathVariable @NotBlank String overrideKey) {
        return service.getEmergencyOverride(overrideKey);
    }

    /** 创建黑屏窗口（幂等）；插播窗口与黑屏窗口相交时整次发布 422。 */
    @PostMapping("/channels/{channelId}/blackout-windows")
    public BlackoutWindowResponse createBlackoutWindow(
            @PathVariable @NotBlank String channelId,
            @Valid @RequestBody CreateBlackoutWindowRequest request) {
        return service.createBlackoutWindow(channelId, request);
    }

    /** 按频道、区域与时刻查询播放决策；窗口内使用发布快照素材，不按当前配置重新解析。 */
    @GetMapping("/channels/{channelId}/regions/{regionCode}/playout")
    public RegionPlayoutDecisionResponse regionPlayout(
            @PathVariable @NotBlank String channelId,
            @PathVariable @NotBlank String regionCode,
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            OffsetDateTime at) {
        return service.regionPlayoutDecision(channelId, regionCode, at);
    }

    /** 查询发布快照：固化区域、条目、实际素材、授权版本、插播窗口与回退原因，只读。 */
    @GetMapping("/publications/{publicationId}")
    public PublicationSnapshotResponse publicationSnapshot(@PathVariable long publicationId) {
        return service.publicationSnapshot(publicationId);
    }

    /** 授权阻断诊断：列出当前草稿各区域插播被阻断的原因，blocks 为空表示可发布。 */
    @GetMapping("/channels/{channelId}/drafts/{businessDay}/splice-diagnostics")
    public SpliceDiagnosticsResponse spliceDiagnostics(@PathVariable @NotBlank String channelId,
                                                       @PathVariable String businessDay) {
        return service.spliceDiagnostics(channelId, parseBusinessDay(businessDay));
    }

    private static LocalDate parseBusinessDay(String businessDay) {
        try {
            return LocalDate.parse(businessDay);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("业务日格式应为 yyyy-MM-dd: " + businessDay);
        }
    }
}
