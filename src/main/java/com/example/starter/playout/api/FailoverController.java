package com.example.starter.playout.api;

import com.example.starter.playout.api.FailoverDtos.ActivateFailoverRequest;
import com.example.starter.playout.api.FailoverDtos.ConfigureLinksRequest;
import com.example.starter.playout.api.FailoverDtos.FailoverOrderResponse;
import com.example.starter.playout.api.FailoverDtos.FailoverPreviewResponse;
import com.example.starter.playout.api.FailoverDtos.FailoverStateResponse;
import com.example.starter.playout.api.FailoverDtos.LinkResponse;
import com.example.starter.playout.api.FailoverDtos.LinkStateRequest;
import com.example.starter.playout.api.FailoverDtos.ReceiptRequest;
import com.example.starter.playout.api.FailoverDtos.ReceiptResponse;
import com.example.starter.playout.failover.FailoverService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 主备播出链路租约切换与游标回执仲裁 REST API。
 * 成功响应统一 200；错误区分 400 参数错误、404 资源不存在、409 冲突、422 业务规则不满足。
 */
@RestController
@RequestMapping("/api/failover")
public class FailoverController {

    private final FailoverService service;

    public FailoverController(FailoverService service) {
        this.service = service;
    }

    /** 配置频道主备链路并初始化主链路 ACTIVE 租约（generation=1）。 */
    @PostMapping("/channels/{channelId}/links")
    public List<LinkResponse> configureLinks(
            @PathVariable @NotBlank String channelId,
            @Valid @RequestBody ConfigureLinksRequest request) {
        return service.configureLinks(channelId, request);
    }

    /** 链路自报健康/缓存版本/已同步插播签名（运维与测试入口）。 */
    @PutMapping("/channels/{channelId}/links/{linkId}/state")
    public List<LinkResponse> reportLinkState(
            @PathVariable @NotBlank String channelId,
            @PathVariable @NotBlank String linkId,
            @Valid @RequestBody LinkStateRequest request) {
        return service.reportLinkState(channelId, linkId, request);
    }

    /** 预览安全切点（只读，不写数据）。 */
    @PostMapping("/failovers/preview")
    public FailoverPreviewResponse preview(@Valid @RequestBody ActivateFailoverRequest request) {
        return service.preview(request);
    }

    /** 激活主备切换单（携带 requestId 幂等；failoverKey 唯一；失败不占键）。 */
    @PostMapping("/failovers")
    public FailoverOrderResponse activate(@Valid @RequestBody ActivateFailoverRequest request) {
        return service.activate(request);
    }

    /** 提交链路播出回执。 */
    @PostMapping("/receipts")
    public ReceiptResponse submitReceipt(@Valid @RequestBody ReceiptRequest request) {
        return service.submitReceipt(request);
    }

    /** 查询频道租约世代、切点、迟到回执与编排证据（只读）。 */
    @GetMapping("/channels/{channelId}/state")
    public FailoverStateResponse getState(@PathVariable @NotBlank String channelId) {
        return service.getState(channelId);
    }
}
