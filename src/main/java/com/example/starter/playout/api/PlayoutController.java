package com.example.starter.playout.api;

import com.example.starter.playout.LeaseService;
import com.example.starter.playout.PlayoutService;
import com.example.starter.playout.api.Dtos.AckSegmentRequest;
import com.example.starter.playout.api.Dtos.AckSegmentResponse;
import com.example.starter.playout.api.Dtos.AckView;
import com.example.starter.playout.api.Dtos.AssetResponse;
import com.example.starter.playout.api.Dtos.ChannelResponse;
import com.example.starter.playout.api.Dtos.CreateAssetRequest;
import com.example.starter.playout.api.Dtos.CreateChannelRequest;
import com.example.starter.playout.api.Dtos.CreateEmergencyOverrideRequest;
import com.example.starter.playout.api.Dtos.CreateGrantRequest;
import com.example.starter.playout.api.Dtos.DraftResponse;
import com.example.starter.playout.api.Dtos.EmergencyOverrideResponse;
import com.example.starter.playout.api.Dtos.GrantResponse;
import com.example.starter.playout.api.Dtos.CancelEmergencyOverrideRequest;
import com.example.starter.playout.api.Dtos.LeaseResponse;
import com.example.starter.playout.api.Dtos.PlayoutDecisionResponse;
import com.example.starter.playout.api.Dtos.PublicationReferencesResponse;
import com.example.starter.playout.api.Dtos.PullLeaseRequest;
import com.example.starter.playout.api.Dtos.PublishRequest;
import com.example.starter.playout.api.Dtos.PublishResponse;
import com.example.starter.playout.api.Dtos.RenewLeaseRequest;
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
    private final LeaseService leaseService;

    public PlayoutController(PlayoutService service, LeaseService leaseService) {
        this.service = service;
        this.leaseService = leaseService;
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

    /** 边缘端拉取版本租约：绑定当时最新发布版本及一致快照；未过期重复拉取返回同一租约。 */
    @PostMapping("/edge/pulls")
    public LeaseResponse pullLease(@Valid @RequestBody PullLeaseRequest request) {
        return leaseService.pullLease(request);
    }

    /** 续租：仅 ACTIVE 且未过期可续，推进 leaseEpoch、保持发布版本。 */
    @PostMapping("/edge/leases/{leaseId}/renew")
    public LeaseResponse renewLease(@PathVariable long leaseId,
                                    @Valid @RequestBody RenewLeaseRequest request) {
        return leaseService.renewLease(leaseId, request);
    }

    /** 分段确认：按快照顺序确认下一个未确认分段，ackKey 幂等。 */
    @PostMapping("/edge/leases/{leaseId}/acks")
    public AckSegmentResponse ackSegment(@PathVariable long leaseId,
                                         @Valid @RequestBody AckSegmentRequest request) {
        return leaseService.ackSegment(leaseId, request);
    }

    /** 租约明细查询（含分段与插播快照、确认进度），只读。 */
    @GetMapping("/edge/leases/{leaseId}")
    public LeaseResponse lease(@PathVariable long leaseId) {
        return leaseService.getLease(leaseId);
    }

    /** 租约确认记录查询，只读。 */
    @GetMapping("/edge/leases/{leaseId}/acks")
    public List<AckView> leaseAcks(@PathVariable long leaseId) {
        return leaseService.getLeaseAcks(leaseId);
    }

    /** 发布版本引用查询：未过期 ACTIVE 租约数与可清理标记，只读。 */
    @GetMapping("/publications/{publicationId}/references")
    public PublicationReferencesResponse publicationReferences(@PathVariable long publicationId) {
        return leaseService.getPublicationReferences(publicationId);
    }

    private static LocalDate parseBusinessDay(String businessDay) {
        try {
            return LocalDate.parse(businessDay);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("业务日格式应为 yyyy-MM-dd: " + businessDay);
        }
    }
}
