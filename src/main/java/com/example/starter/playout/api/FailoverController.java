package com.example.starter.playout.api;

import com.example.starter.playout.FailoverService;
import com.example.starter.playout.api.FailoverDtos.ActivateFailoverRequest;
import com.example.starter.playout.api.FailoverDtos.BootstrapLeaseRequest;
import com.example.starter.playout.api.FailoverDtos.CreateFailoverOrderRequest;
import com.example.starter.playout.api.FailoverDtos.FailoverOrderResponse;
import com.example.starter.playout.api.FailoverDtos.FailoverPreviewRequest;
import com.example.starter.playout.api.FailoverDtos.FailoverPreviewResponse;
import com.example.starter.playout.api.FailoverDtos.LeaseResponse;
import com.example.starter.playout.api.FailoverDtos.LinkHealthRequest;
import com.example.starter.playout.api.FailoverDtos.LinkResponse;
import com.example.starter.playout.api.FailoverDtos.ReceiptRequest;
import com.example.starter.playout.api.FailoverDtos.ReceiptResponse;
import com.example.starter.playout.api.FailoverDtos.RegisterLinkRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 主备播出链路租约切换 REST API。成功响应统一 200；错误区分 400、404、409（版本/幂等/状态冲突）、
 * 422（目标不健康、缺口、插播未同步、落后超限等业务规则不满足）。
 */
@Validated
@RestController
@RequestMapping("/api")
public class FailoverController {

    private final FailoverService service;

    public FailoverController(FailoverService service) {
        this.service = service;
    }

    /** 注册频道主/备链路。 */
    @PutMapping("/channels/{channelId}/links")
    public LinkResponse registerLink(@PathVariable @NotBlank String channelId,
                                     @Valid @RequestBody RegisterLinkRequest request) {
        return service.registerLink(channelId, request);
    }

    /** 查询频道全部链路（主/备）。 */
    @GetMapping("/channels/{channelId}/links")
    public List<LinkResponse> listLinks(@PathVariable @NotBlank String channelId) {
        return service.listLinks(channelId);
    }

    /** 上报链路健康与已缓存编排版本。 */
    @PostMapping("/channels/{channelId}/links/{linkId}/health")
    public LinkResponse reportHealth(@PathVariable @NotBlank String channelId,
                                     @PathVariable @NotBlank String linkId,
                                     @Valid @RequestBody LinkHealthRequest request) {
        return service.reportHealth(channelId, linkId, request.healthy(), request.cachedVersion());
    }

    /** 上报紧急插播已同步到链路。 */
    @PostMapping("/channels/{channelId}/links/{linkId}/overrides/{overrideKey}/synced")
    public void reportOverrideSynced(@PathVariable @NotBlank String channelId,
                                     @PathVariable @NotBlank String linkId,
                                     @PathVariable @NotBlank String overrideKey) {
        service.reportOverrideSynced(channelId, linkId, overrideKey);
    }

    /** 引导频道初始世代（generation=1）ACTIVE 租约。 */
    @PostMapping("/channels/{channelId}/lease/bootstrap")
    public LeaseResponse bootstrapLease(@PathVariable @NotBlank String channelId,
                                        @Valid @RequestBody BootstrapLeaseRequest request) {
        return service.bootstrapLease(channelId, request);
    }

    /** 查询当前 ACTIVE 租约。 */
    @GetMapping("/channels/{channelId}/lease")
    public LeaseResponse activeLease(@PathVariable @NotBlank String channelId) {
        return service.activeLease(channelId);
    }

    /** 链路播放回执（携带 requestId 幂等）；旧世代回执存为 LATE，不推进游标。 */
    @PostMapping("/channels/{channelId}/links/{linkId}/receipts")
    public ReceiptResponse submitReceipt(@PathVariable @NotBlank String channelId,
                                         @PathVariable @NotBlank String linkId,
                                         @Valid @RequestBody ReceiptRequest request) {
        return service.submitReceipt(channelId, linkId, request);
    }

    /** 预览安全切点（只读，不写数据）。 */
    @PostMapping("/failover-orders/preview")
    public FailoverPreviewResponse preview(@Valid @RequestBody FailoverPreviewRequest request) {
        return service.preview(request);
    }

    /** 监控员创建切换单。 */
    @PostMapping("/failover-orders")
    public FailoverOrderResponse createOrder(@Valid @RequestBody CreateFailoverOrderRequest request) {
        return service.createOrder(request);
    }

    /** 激活切换单（单事务重读仲裁，携带 requestId 幂等）。 */
    @PostMapping("/failover-orders/{failoverKey}/activate")
    public FailoverOrderResponse activate(@PathVariable @NotBlank String failoverKey,
                                          @Valid @RequestBody ActivateFailoverRequest request) {
        return service.activate(failoverKey, request);
    }

    /** 查询切换单明细（只读）：世代、切点、迟到回执、冻结插播栈与编排证据。 */
    @GetMapping("/failover-orders/{failoverKey}")
    public FailoverOrderResponse getOrder(@PathVariable @NotBlank String failoverKey) {
        return service.getOrder(failoverKey);
    }
}
