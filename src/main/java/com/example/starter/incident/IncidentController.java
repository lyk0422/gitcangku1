package com.example.starter.incident;

import java.util.List;

import com.example.starter.incident.dto.Requests.ActionRequest;
import com.example.starter.incident.dto.Requests.CleanupRequest;
import com.example.starter.incident.dto.Requests.EscalateRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.StatusRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskCompleteRequest;
import com.example.starter.incident.dto.Requests.TaskRequest;
import com.example.starter.incident.dto.Requests.TransferAcceptRequest;
import com.example.starter.incident.dto.Requests.TransferRequest;
import com.example.starter.incident.dto.Requests.CancelRequest;
import com.example.starter.incident.dto.Responses.ActionView;
import com.example.starter.incident.dto.Responses.CleanupHistoryItem;
import com.example.starter.incident.dto.Responses.CleanupView;
import com.example.starter.incident.dto.Responses.EscalationView;
import com.example.starter.incident.dto.Responses.HistoryView;
import com.example.starter.incident.dto.Responses.IncidentView;
import com.example.starter.incident.dto.Responses.TaskView;
import com.example.starter.incident.dto.Responses.TransferView;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 事件指挥 REST API。写操作（除上报外）均要求 X-Actor-Id 请求头标识操作人。
 * 域选择：默认 REAL 真实域；对事件键的读写显式携带 includeDrill=true 方进入 DRILL 演练域，
 * 所有返回结果均带 domain 字段标注所属域。
 */
@RestController
@RequestMapping("/api/incidents")
public class IncidentController {

    private final IncidentService service;
    private final DrillCleanupService cleanupService;

    public IncidentController(IncidentService service, DrillCleanupService cleanupService) {
        this.service = service;
        this.cleanupService = cleanupService;
    }

    private static Domain domain(boolean includeDrill) {
        return includeDrill ? Domain.DRILL : Domain.REAL;
    }

    /**
     * 上报事件：初始状态 REPORTED；请求体携带 drillKey 时为演练沙盘事件。
     */
    @PostMapping
    public ResponseEntity<IncidentView> report(@RequestBody ReportRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.report(req));
    }

    /**
     * 列出事件：默认仅真实域；includeDrill=true 时列演练域。
     */
    @GetMapping
    public List<IncidentView> list(@RequestParam(name = "includeDrill", defaultValue = "false")
                                   boolean includeDrill) {
        return service.list(domain(includeDrill));
    }

    /**
     * 查询事件当前状态（默认真实域，includeDrill=true 查演练域）。
     */
    @GetMapping("/{incidentKey}")
    public IncidentView get(@PathVariable String incidentKey,
                            @RequestParam(name = "includeDrill", defaultValue = "false")
                            boolean includeDrill) {
        return service.get(domain(includeDrill), incidentKey);
    }

    /**
     * 查询事件完整历史（状态流转、处置记录、交接记录、升级、任务）。
     */
    @GetMapping("/{incidentKey}/history")
    public HistoryView history(@PathVariable String incidentKey,
                               @RequestParam(name = "includeDrill", defaultValue = "false")
                               boolean includeDrill) {
        return service.history(domain(includeDrill), incidentKey);
    }

    /**
     * 首次接管：REPORTED → COMMANDING。
     */
    @PostMapping("/{incidentKey}/takeover")
    public IncidentView takeover(@PathVariable String incidentKey,
                                 @RequestHeader("X-Actor-Id") String actor,
                                 @RequestParam(name = "includeDrill", defaultValue = "false")
                                 boolean includeDrill,
                                 @RequestBody TakeoverRequest req) {
        return service.takeover(domain(includeDrill), incidentKey, actor, req);
    }

    /**
     * 发起指挥交接（当前指挥人指定目标人）。
     */
    @PostMapping("/{incidentKey}/transfers")
    public TransferView initiateTransfer(@PathVariable String incidentKey,
                                         @RequestHeader("X-Actor-Id") String actor,
                                         @RequestParam(name = "includeDrill", defaultValue = "false")
                                         boolean includeDrill,
                                         @RequestBody TransferRequest req) {
        return service.initiateTransfer(domain(includeDrill), incidentKey, actor, req);
    }

    /**
     * 接受指挥交接（目标人接受后原子切换指挥权）。
     */
    @PostMapping("/{incidentKey}/transfers/accept")
    public IncidentView acceptTransfer(@PathVariable String incidentKey,
                                       @RequestHeader("X-Actor-Id") String actor,
                                       @RequestParam(name = "includeDrill", defaultValue = "false")
                                       boolean includeDrill,
                                       @RequestBody TransferAcceptRequest req) {
        return service.acceptTransfer(domain(includeDrill), incidentKey, actor, req);
    }

    /**
     * 追加处置记录（仅当前指挥人）。
     */
    @PostMapping("/{incidentKey}/actions")
    public ActionView addAction(@PathVariable String incidentKey,
                                @RequestHeader("X-Actor-Id") String actor,
                                @RequestParam(name = "includeDrill", defaultValue = "false")
                                boolean includeDrill,
                                @RequestBody ActionRequest req) {
        return service.addAction(domain(includeDrill), incidentKey, actor, req);
    }

    /**
     * 状态变更（仅当前指挥人，逐级前进）。
     */
    @PostMapping("/{incidentKey}/status")
    public IncidentView changeStatus(@PathVariable String incidentKey,
                                     @RequestHeader("X-Actor-Id") String actor,
                                     @RequestParam(name = "includeDrill", defaultValue = "false")
                                     boolean includeDrill,
                                     @RequestBody StatusRequest req) {
        return service.changeStatus(domain(includeDrill), incidentKey, actor, req);
    }

    /**
     * 取消事件（任一非终态进入 CANCELLED 终态）。
     */
    @PostMapping("/{incidentKey}/cancel")
    public IncidentView cancel(@PathVariable String incidentKey,
                               @RequestHeader("X-Actor-Id") String actor,
                               @RequestParam(name = "includeDrill", defaultValue = "false")
                               boolean includeDrill,
                               @RequestBody CancelRequest req) {
        return service.cancel(domain(includeDrill), incidentKey, actor, req);
    }

    /**
     * 升级事件（仅当前指挥人）；演练域升级不触发真实通知。
     */
    @PostMapping("/{incidentKey}/escalations")
    public EscalationView escalate(@PathVariable String incidentKey,
                                   @RequestHeader("X-Actor-Id") String actor,
                                   @RequestParam(name = "includeDrill", defaultValue = "false")
                                   boolean includeDrill,
                                   @RequestBody EscalateRequest req) {
        return service.escalate(domain(includeDrill), incidentKey, actor, req);
    }

    /**
     * 创建处置任务（仅当前指挥人）；blockerIncidentKeys 必须与事件同域。
     */
    @PostMapping("/{incidentKey}/tasks")
    public TaskView createTask(@PathVariable String incidentKey,
                               @RequestHeader("X-Actor-Id") String actor,
                               @RequestParam(name = "includeDrill", defaultValue = "false")
                               boolean includeDrill,
                               @RequestBody TaskRequest req) {
        return service.createTask(domain(includeDrill), incidentKey, actor, req);
    }

    /**
     * 完成处置任务（仅当前指挥人）。
     */
    @PostMapping("/{incidentKey}/tasks/{taskKey}/complete")
    public TaskView completeTask(@PathVariable String incidentKey,
                                 @PathVariable String taskKey,
                                 @RequestHeader("X-Actor-Id") String actor,
                                 @RequestParam(name = "includeDrill", defaultValue = "false")
                                 boolean includeDrill,
                                 @RequestBody TaskCompleteRequest req) {
        return service.completeTask(domain(includeDrill), incidentKey, actor, taskKey, req);
    }

    /**
     * 演练批次事件清单。
     */
    @GetMapping("/drill-batches/{batchKey}/incidents")
    public List<IncidentView> batchIncidents(@PathVariable String batchKey) {
        return cleanupService.listBatchIncidents(batchKey);
    }

    /**
     * 提交演练批次批量清理（原子删除终态批次）。
     */
    @PostMapping("/drill-cleanups")
    public ResponseEntity<CleanupView> cleanup(
            @RequestHeader(value = "X-Actor-Id", required = false) String actor,
            @RequestBody CleanupRequest req) {
        return ResponseEntity.status(HttpStatus.OK).body(cleanupService.cleanup(actor, req));
    }

    /**
     * 清理历史查询；可按批次过滤。
     */
    @GetMapping("/drill-cleanups")
    public List<CleanupHistoryItem> cleanupHistory(
            @RequestParam(name = "batchKey", required = false) String batchKey) {
        return cleanupService.listHistory(batchKey);
    }
}
