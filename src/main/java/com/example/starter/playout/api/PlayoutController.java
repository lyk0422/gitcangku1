package com.example.starter.playout.api;

import com.example.starter.playout.LiveOverrideService;
import com.example.starter.playout.PlayoutService;
import com.example.starter.playout.api.Dtos.AssetResponse;
import com.example.starter.playout.api.Dtos.ChannelResponse;
import com.example.starter.playout.api.Dtos.CreateAssetRequest;
import com.example.starter.playout.api.Dtos.CreateChannelRequest;
import com.example.starter.playout.api.Dtos.CreateEmergencyOverrideRequest;
import com.example.starter.playout.api.Dtos.CreateGrantRequest;
import com.example.starter.playout.api.Dtos.CreateLiveOverrideRequest;
import com.example.starter.playout.api.Dtos.DraftResponse;
import com.example.starter.playout.api.Dtos.EmergencyOverrideResponse;
import com.example.starter.playout.api.Dtos.GrantResponse;
import com.example.starter.playout.api.Dtos.CancelEmergencyOverrideRequest;
import com.example.starter.playout.api.Dtos.LiveOverrideDiagnostics;
import com.example.starter.playout.api.Dtos.LiveOverrideResponse;
import com.example.starter.playout.api.Dtos.LiveOverrideSummary;
import com.example.starter.playout.api.Dtos.PlayoutDecisionResponse;
import com.example.starter.playout.api.Dtos.PublishRequest;
import com.example.starter.playout.api.Dtos.PublishResponse;
import com.example.starter.playout.api.Dtos.ReplaceDraftRequest;
import com.example.starter.playout.api.Dtos.RevokeGrantRequest;
import com.example.starter.playout.api.Dtos.RevokeLiveOverrideRequest;
import com.example.starter.playout.api.Dtos.SettleLiveOverrideRequest;
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
import java.util.List;

/**
 * 播出编排 REST API。成功响应统一 200；错误区分 400 参数错误、404 资源不存在、
 * 409 版本/幂等冲突、422 业务规则不满足。
 */
@Validated
@RestController
@RequestMapping("/api")
public class PlayoutController {

    private final PlayoutService service;
    private final LiveOverrideService liveOverrideService;

    public PlayoutController(PlayoutService service, LiveOverrideService liveOverrideService) {
        this.service = service;
        this.liveOverrideService = liveOverrideService;
    }

    /** 创建素材。 */
    @PostMapping("/assets")
    public AssetResponse createAsset(@Valid @RequestBody CreateAssetRequest request) {
        return service.createAsset(request);
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

    /** 创建直播覆盖令（跨频道全量校验、单事务提交，携带 requestId 幂等）。 */
    @PostMapping("/live-overrides")
    public LiveOverrideResponse createLiveOverride(
            @Valid @RequestBody CreateLiveOverrideRequest request) {
        return liveOverrideService.create(request);
    }

    /** 撤销直播覆盖令（仅尚未开始的覆盖可撤销，携带 requestId 幂等）。 */
    @PostMapping("/live-overrides/{overrideKey}/revoke")
    public LiveOverrideResponse revokeLiveOverride(
            @PathVariable @NotBlank String overrideKey,
            @Valid @RequestBody RevokeLiveOverrideRequest request) {
        return liveOverrideService.revoke(overrideKey, request);
    }

    /** 结算直播覆盖令（仅覆盖结束后可结算，写入 MISSED_BY_LIVE，携带 requestId 幂等）。 */
    @PostMapping("/live-overrides/{overrideKey}/settle")
    public LiveOverrideResponse settleLiveOverride(
            @PathVariable @NotBlank String overrideKey,
            @Valid @RequestBody SettleLiveOverrideRequest request) {
        return liveOverrideService.settle(overrideKey, request);
    }

    /** 查询直播覆盖令明细，含频道维度授权快照与受影响素材恢复结果；只读。 */
    @GetMapping("/live-overrides/{overrideKey}")
    public LiveOverrideResponse liveOverride(@PathVariable @NotBlank String overrideKey) {
        return liveOverrideService.getLiveOverride(overrideKey);
    }

    /** 查询直播覆盖令诊断：数量与时长实际值；只读。 */
    @GetMapping("/live-overrides/{overrideKey}/diagnostics")
    public LiveOverrideDiagnostics liveOverrideDiagnostics(
            @PathVariable @NotBlank String overrideKey) {
        return liveOverrideService.getDiagnostics(overrideKey);
    }

    /** 查询频道直播覆盖令历史，按开始时间、覆盖键稳定排序；只读。 */
    @GetMapping("/channels/{channelId}/live-overrides")
    public List<LiveOverrideSummary> liveOverrideHistory(
            @PathVariable @NotBlank String channelId) {
        return liveOverrideService.listChannelHistory(channelId);
    }

    private static LocalDate parseBusinessDay(String businessDay) {
        try {
            return LocalDate.parse(businessDay);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("业务日格式应为 yyyy-MM-dd: " + businessDay);
        }
    }
}
