package com.example.starter.playout.api;

import com.example.starter.playout.PlayoutService;
import com.example.starter.playout.api.Dtos.AssetResponse;
import com.example.starter.playout.api.Dtos.ChannelResponse;
import com.example.starter.playout.api.Dtos.CreateAssetRequest;
import com.example.starter.playout.api.Dtos.CreateChannelRequest;
import com.example.starter.playout.api.Dtos.CreateEmergencyOverrideRequest;
import com.example.starter.playout.api.Dtos.CreateGrantRequest;
import com.example.starter.playout.api.Dtos.DraftResponse;
import com.example.starter.playout.api.Dtos.EmergencyOverrideResponse;
import com.example.starter.playout.api.Dtos.GrantResponse;
import com.example.starter.playout.api.Dtos.BatchPublishRequest;
import com.example.starter.playout.api.Dtos.BatchPublishResponse;
import com.example.starter.playout.api.Dtos.CancelEmergencyOverrideRequest;
import com.example.starter.playout.api.Dtos.DeleteSponsorWindowRequest;
import com.example.starter.playout.api.Dtos.PlayoutDecisionResponse;
import com.example.starter.playout.api.Dtos.PublicationDetailResponse;
import com.example.starter.playout.api.Dtos.RegisterSponsorWindowRequest;
import com.example.starter.playout.api.Dtos.ReviseSponsorWindowRequest;
import com.example.starter.playout.api.Dtos.RevokePublicationRequest;
import com.example.starter.playout.api.Dtos.RevokePublicationResponse;
import com.example.starter.playout.api.Dtos.SponsorWindowResponse;
import com.example.starter.playout.api.Dtos.PublishRequest;
import com.example.starter.playout.api.Dtos.PublishResponse;
import com.example.starter.playout.api.Dtos.ReplaceDraftRequest;
import com.example.starter.playout.api.Dtos.RevokeGrantRequest;
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
import java.util.List;
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

    // ---------- 赞助排他窗 ----------

    /** 登记赞助排他窗（幂等）。 */
    @PostMapping("/sponsor-windows")
    public SponsorWindowResponse registerSponsorWindow(
            @Valid @RequestBody RegisterSponsorWindowRequest request) {
        return service.registerSponsorWindow(request);
    }

    /** 修订赞助排他窗：整体替换区间与频道集合，版本 +1（幂等）。 */
    @PutMapping("/sponsor-windows/{windowKey}")
    public SponsorWindowResponse reviseSponsorWindow(
            @PathVariable @NotBlank String windowKey,
            @Valid @RequestBody ReviseSponsorWindowRequest request) {
        return service.reviseSponsorWindow(windowKey, request);
    }

    /** 删除赞助排他窗（幂等）；不影响已发布快照中的窗口命中记录。 */
    @PostMapping("/sponsor-windows/{windowKey}/delete")
    public SponsorWindowResponse deleteSponsorWindow(
            @PathVariable @NotBlank String windowKey,
            @Valid @RequestBody DeleteSponsorWindowRequest request) {
        return service.deleteSponsorWindow(windowKey, request.requestId());
    }

    /** 查询赞助排他窗当前版本详情。 */
    @GetMapping("/sponsor-windows/{windowKey}")
    public SponsorWindowResponse sponsorWindow(@PathVariable @NotBlank String windowKey) {
        return service.getSponsorWindow(windowKey);
    }

    /** 按赞助商与运营日查询 ACTIVE 排他窗（诊断查询，不修改状态）；channelId 可选过滤。 */
    @GetMapping("/sponsor-windows")
    public List<SponsorWindowResponse> sponsorWindows(
            @RequestParam @NotBlank String sponsorKey,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDay,
            @RequestParam(required = false) String channelId) {
        return service.listSponsorWindows(sponsorKey, businessDay, channelId);
    }

    // ---------- 批量发布 / 发布撤销 / 发布明细 ----------

    /** 跨频道批量发布：完整最终编排校验后一个事务提交，任一失败整体回滚（幂等）。 */
    @PostMapping("/publications/publish-batch")
    public BatchPublishResponse publishBatch(@Valid @RequestBody BatchPublishRequest request) {
        return service.publishBatch(request);
    }

    /** 撤销尚未开始的发布（幂等）；已开始的播出只能按既有紧急流程处理。 */
    @PostMapping("/publications/{publicationId}/revoke")
    public RevokePublicationResponse revokePublication(
            @PathVariable long publicationId,
            @Valid @RequestBody RevokePublicationRequest request) {
        return service.revokePublication(publicationId, request.requestId());
    }

    /** 查询发布快照明细（含赞助快照与窗口命中历史，只读）。 */
    @GetMapping("/publications/{publicationId}")
    public PublicationDetailResponse publication(@PathVariable long publicationId) {
        return service.getPublication(publicationId);
    }

    private static LocalDate parseBusinessDay(String businessDay) {
        try {
            return LocalDate.parse(businessDay);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("业务日格式应为 yyyy-MM-dd: " + businessDay);
        }
    }
}
