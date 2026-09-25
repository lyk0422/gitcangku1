package com.example.starter.incident;

import java.util.List;

import com.example.starter.incident.dto.Requests.DelegateRegisterRequest;
import com.example.starter.incident.dto.Requests.HandoffCreateRequest;
import com.example.starter.incident.dto.Requests.HandoffSettleRequest;
import com.example.starter.incident.dto.Requests.ResourceRegisterRequest;
import com.example.starter.incident.dto.Requests.TaskAssignResourceRequest;
import com.example.starter.incident.dto.Requests.TaskStartRequest;
import com.example.starter.incident.dto.Responses.CloseBlockerView;
import com.example.starter.incident.dto.Responses.DelegateView;
import com.example.starter.incident.dto.Responses.HandoffBatchView;
import com.example.starter.incident.dto.Responses.HandoffSettlementView;
import com.example.starter.incident.dto.Responses.HandoffView;
import com.example.starter.incident.dto.Responses.ResourceResponsibilityView;
import com.example.starter.incident.dto.Responses.ResourceView;
import com.example.starter.incident.dto.Responses.TaskView;
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
 * 跨事件互助资源交接 REST API。
 * 写操作均要求 X-Actor-Id 请求头标识操作者（来源指挥人/目标指挥人或其登记代理人）。
 */
@RestController
@RequestMapping("/api")
public class MutualAidController {

    private final IncidentService service;

    public MutualAidController(IncidentService service) {
        this.service = service;
    }

    /** 登记可互助资源（来源事件当前指挥人）。 */
    @PostMapping("/incidents/{incidentKey}/resources")
    public ResponseEntity<ResourceView> registerResource(@PathVariable String incidentKey,
                                                         @RequestHeader("X-Actor-Id") String actor,
                                                         @RequestBody ResourceRegisterRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                service.registerResource(incidentKey, actor, req));
    }

    /** 查询事件登记的互助资源。 */
    @GetMapping("/incidents/{incidentKey}/resources")
    public List<ResourceView> listResources(@PathVariable String incidentKey) {
        return service.listResources(incidentKey);
    }

    /** 登记目标事件接收代理人（当前指挥人授权）。 */
    @PostMapping("/incidents/{incidentKey}/receiving-delegates")
    public ResponseEntity<DelegateView> registerDelegate(@PathVariable String incidentKey,
                                                         @RequestHeader("X-Actor-Id") String actor,
                                                         @RequestBody DelegateRegisterRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                service.registerDelegate(incidentKey, actor, req));
    }

    /** 批量互助交接：来源事件把空闲资源借给目标 OPEN 事件。 */
    @PostMapping("/incidents/{sourceIncidentKey}/handoffs")
    public ResponseEntity<HandoffBatchView> createHandoffs(
            @PathVariable String sourceIncidentKey,
            @RequestHeader("X-Actor-Id") String actor,
            @RequestBody HandoffCreateRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                service.createHandoffs(sourceIncidentKey, actor, req));
    }

    /** 租约到期结算检查（按注入 Clock 当前时刻评估，不做定时扫描）。 */
    @PostMapping("/incidents/{incidentKey}/handoffs/settle-expired")
    public List<HandoffSettlementView> settleExpired(@PathVariable String incidentKey,
                                                     @RequestBody HandoffSettleRequest req) {
        return service.settleExpiredLeases(incidentKey, req);
    }

    /** 查询事件关闭阻断原因（只读，不结算）。 */
    @GetMapping("/incidents/{incidentKey}/close-blockers")
    public CloseBlockerView closeBlockers(@PathVariable String incidentKey) {
        return service.getCloseBlockers(incidentKey);
    }

    /** 开始处置任务（OPEN → STARTED）。 */
    @PostMapping("/incidents/{incidentKey}/tasks/{taskKey}/start")
    public TaskView startTask(@PathVariable String incidentKey, @PathVariable String taskKey,
                              @RequestHeader("X-Actor-Id") String actor,
                              @RequestBody TaskStartRequest req) {
        return service.startTask(incidentKey, taskKey, actor, req);
    }

    /** 将通过交接借入的资源分配给目标事件任务。 */
    @PostMapping("/incidents/{incidentKey}/tasks/{taskKey}/assign-resource")
    public TaskView assignResource(@PathVariable String incidentKey, @PathVariable String taskKey,
                                   @RequestHeader("X-Actor-Id") String actor,
                                   @RequestBody TaskAssignResourceRequest req) {
        return service.assignResourceToTask(incidentKey, taskKey, actor, req);
    }

    /** 查询资源当前责任方。 */
    @GetMapping("/resources/{resourceKey}/responsibility")
    public ResourceResponsibilityView responsibility(@PathVariable String resourceKey) {
        return service.getResourceResponsibility(resourceKey);
    }

    /** 查询资源交接历史。 */
    @GetMapping("/resources/{resourceKey}/handoffs")
    public List<HandoffView> handoffs(@PathVariable String resourceKey) {
        return service.getResourceHandoffs(resourceKey);
    }

    /** 查询资源不可变交接结算记录。 */
    @GetMapping("/resources/{resourceKey}/settlements")
    public List<HandoffSettlementView> settlements(@PathVariable String resourceKey) {
        return service.getResourceSettlements(resourceKey);
    }
}
