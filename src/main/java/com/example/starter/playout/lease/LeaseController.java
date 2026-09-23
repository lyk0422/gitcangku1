package com.example.starter.playout.lease;

import com.example.starter.playout.api.Dtos.LeaseResponse;
import com.example.starter.playout.api.Dtos.PublicationReferenceResponse;
import com.example.starter.playout.api.Dtos.PullLeaseRequest;
import com.example.starter.playout.api.Dtos.RenewLeaseRequest;
import com.example.starter.playout.api.Dtos.SegmentAckRequest;
import com.example.starter.playout.api.Dtos.SegmentAckResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

import com.example.starter.playout.api.ApiException;

/**
 * 播出端版本租约 REST API：租约拉取/续租、分段确认与只读查询。
 * 成功响应统一 200；错误区分 400 参数错误、404 资源不存在、409 状态/幂等冲突、422 业务规则不满足。
 */
@Validated
@RestController
@RequestMapping("/api")
public class LeaseController {

    private final LeaseService service;

    public LeaseController(LeaseService service) {
        this.service = service;
    }

    /** 拉取（或取回未过期的）版本租约。 */
    @PostMapping("/edge-leases")
    public LeaseResponse pullLease(@Valid @RequestBody PullLeaseRequest request) {
        return service.pullLease(request);
    }

    /** 续租：仅 ACTIVE 且未过期，推进 leaseEpoch 但保持发布版本。 */
    @PostMapping("/edge-leases/{leaseId}/renew")
    public LeaseResponse renewLease(@PathVariable long leaseId,
                                    @Valid @RequestBody RenewLeaseRequest request) {
        return service.renewLease(leaseId, request.requestId());
    }

    /** 提交分段确认。 */
    @PostMapping("/edge-leases/{leaseId}/acks")
    public SegmentAckResponse ackSegment(@PathVariable long leaseId,
                                         @Valid @RequestBody SegmentAckRequest request) {
        if (request.leaseId() == null || request.leaseId() != leaseId) {
            throw ApiException.badRequest("路径 leaseId 与请求体不一致: " + leaseId);
        }
        return service.ackSegment(request);
    }

    /** 查询租约明细（只读）。 */
    @GetMapping("/edge-leases/{leaseId}")
    public LeaseResponse getLease(@PathVariable long leaseId) {
        return service.getLease(leaseId);
    }

    /** 查询租约的分段确认列表（只读）。 */
    @GetMapping("/edge-leases/{leaseId}/acks")
    public List<SegmentAckResponse> listAcks(@PathVariable long leaseId) {
        return service.listAcks(leaseId);
    }

    /** 查询频道某业务日各发布版本的租约引用与可清理标记（只读）。 */
    @GetMapping("/channels/{channelId}/drafts/{businessDay}/publication-references")
    public List<PublicationReferenceResponse> publicationReferences(
            @PathVariable @NotBlank String channelId,
            @PathVariable String businessDay) {
        return service.publicationReferences(channelId, parseBusinessDay(businessDay));
    }

    private static LocalDate parseBusinessDay(String businessDay) {
        try {
            return LocalDate.parse(businessDay);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("业务日格式应为 yyyy-MM-dd: " + businessDay);
        }
    }
}
