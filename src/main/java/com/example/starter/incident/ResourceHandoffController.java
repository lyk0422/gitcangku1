package com.example.starter.incident;

import com.example.starter.incident.dto.Requests.DelegateRegisterRequest;
import com.example.starter.incident.dto.Requests.HandoffCreateRequest;
import com.example.starter.incident.dto.Requests.HandoffSettleRequest;
import com.example.starter.incident.dto.Requests.ResourceAcquireRequest;
import com.example.starter.incident.dto.Responses.CloseBlockersView;
import com.example.starter.incident.dto.Responses.DelegateView;
import com.example.starter.incident.dto.Responses.HandoffSettleView;
import com.example.starter.incident.dto.Responses.HandoffView;
import com.example.starter.incident.dto.Responses.IncidentDelegatesView;
import com.example.starter.incident.dto.Responses.IncidentHandoffsView;
import com.example.starter.incident.dto.Responses.IncidentResourcesView;
import com.example.starter.incident.dto.Responses.IncidentSettlementsView;
import com.example.starter.incident.dto.Responses.ResourceResponsibilityView;
import com.example.starter.incident.dto.Responses.ResourceView;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 跨事件互助资源交接 REST API。写操作均要求 X-Actor-Id 请求头标识操作人。
 */
@RestController
@RequestMapping("/api")
public class ResourceHandoffController {

    private final ResourceHandoffService service;

    public ResourceHandoffController(ResourceHandoffService service) {
        this.service = service;
    }

    /**
     * 登记资源（仅当前指挥人；resourceKey 全局唯一）。
     */
    @PostMapping("/incidents/{incidentKey}/resources")
    public ResponseEntity<ResourceView> acquireResource(@PathVariable String incidentKey,
                                                        @RequestHeader("X-Actor-Id") String actor,
                                                        @RequestBody ResourceAcquireRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.acquireResource(incidentKey, actor, req));
    }

    /**
     * 查询事件登记持有的全部资源（只读）。
     */
    @GetMapping("/incidents/{incidentKey}/resources")
    public IncidentResourcesView listResources(@PathVariable String incidentKey) {
        return service.listResources(incidentKey);
    }

    /**
     * 登记代理人（仅当前指挥人；代理人具交接接收权限）。
     */
    @PostMapping("/incidents/{incidentKey}/delegates")
    public ResponseEntity<DelegateView> registerDelegate(@PathVariable String incidentKey,
                                                         @RequestHeader("X-Actor-Id") String actor,
                                                         @RequestBody DelegateRegisterRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.registerDelegate(incidentKey, actor, req));
    }

    /**
     * 查询事件全部代理人（只读）。
     */
    @GetMapping("/incidents/{incidentKey}/delegates")
    public IncidentDelegatesView listDelegates(@PathVariable String incidentKey) {
        return service.listDelegates(incidentKey);
    }

    /**
     * 创建互助交接（来源事件当前指挥人；可批量多资源，任一冲突 422 整体回滚）。
     */
    @PostMapping("/incidents/{incidentKey}/handoffs")
    public ResponseEntity<HandoffView> createHandoff(@PathVariable String incidentKey,
                                                     @RequestHeader("X-Actor-Id") String actor,
                                                     @RequestBody HandoffCreateRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.createHandoff(incidentKey, actor, req));
    }

    /**
     * 查询事件相关的全部交接（含资源项与结算，只读）。
     */
    @GetMapping("/incidents/{incidentKey}/handoffs")
    public IncidentHandoffsView listHandoffs(@PathVariable String incidentKey) {
        return service.listHandoffs(incidentKey);
    }

    /**
     * 租约到期结算：评估本事件相关的进行中交接，已到期者触发结束并结算归还。
     */
    @PostMapping("/incidents/{incidentKey}/handoffs/settle")
    public HandoffSettleView settleExpired(@PathVariable String incidentKey,
                                           @RequestBody HandoffSettleRequest req) {
        return service.settleExpired(incidentKey, req);
    }

    /**
     * 查询事件相关的全部不可变交接结算（只读）。
     */
    @GetMapping("/incidents/{incidentKey}/settlements")
    public IncidentSettlementsView listSettlements(@PathVariable String incidentKey) {
        return service.listSettlements(incidentKey);
    }

    /**
     * 查询事件关闭阻断原因（借出未归还资源、未达终态任务，只读）。
     */
    @GetMapping("/incidents/{incidentKey}/close-blockers")
    public CloseBlockersView closeBlockers(@PathVariable String incidentKey) {
        return service.closeBlockers(incidentKey);
    }

    /**
     * 查询资源当前责任方（持有或借出中的目标事件，只读）。
     */
    @GetMapping("/resources/{resourceKey}/responsibility")
    public ResourceResponsibilityView resourceResponsibility(@PathVariable String resourceKey) {
        return service.resourceResponsibility(resourceKey);
    }
}
