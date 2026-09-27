package com.example.starter.incident;

import com.example.starter.incident.dto.Requests.ActionRequest;
import com.example.starter.incident.dto.Requests.EscalationAckRequest;
import com.example.starter.incident.dto.Requests.EscalationCheckRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.SealCorrectionRequest;
import com.example.starter.incident.dto.Requests.SealLogRequest;
import com.example.starter.incident.dto.Requests.StatusRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Requests.TransferAcceptRequest;
import com.example.starter.incident.dto.Requests.TransferRequest;
import com.example.starter.incident.dto.Responses.ActionView;
import com.example.starter.incident.dto.Responses.EscalationHistoryView;
import com.example.starter.incident.dto.Responses.EscalationView;
import com.example.starter.incident.dto.Responses.HistoryView;
import com.example.starter.incident.dto.Responses.IncidentTasksView;
import com.example.starter.incident.dto.Responses.IncidentView;
import com.example.starter.incident.dto.Responses.SealCorrectionView;
import com.example.starter.incident.dto.Responses.SealDetailView;
import com.example.starter.incident.dto.Responses.SealListView;
import com.example.starter.incident.dto.Responses.SealVerifyView;
import com.example.starter.incident.dto.Responses.SealView;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * 事件指挥 REST API。写操作（除上报外）均要求 X-Actor-Id 请求头标识操作人。
 */
@RestController
@RequestMapping("/api/incidents")
public class IncidentController {

    private final IncidentService service;
    private final LogSealService sealService;

    public IncidentController(IncidentService service, LogSealService sealService) {
        this.service = service;
        this.sealService = sealService;
    }

    /**
     * 上报事件：初始状态 REPORTED。
     */
    @PostMapping
    public ResponseEntity<IncidentView> report(@RequestBody ReportRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.report(req));
    }

    /**
     * 查询事件当前状态。
     */
    @GetMapping("/{incidentKey}")
    public IncidentView get(@PathVariable String incidentKey) {
        return service.get(incidentKey);
    }

    /**
     * 查询事件完整历史（状态流转、处置记录、交接记录、升级记录）。
     */
    @GetMapping("/{incidentKey}/history")
    public HistoryView history(@PathVariable String incidentKey) {
        return service.history(incidentKey);
    }

    /**
     * 查询遏制期限、当前升级及完整升级历史（只读，不隐式写入）。
     */
    @GetMapping("/{incidentKey}/escalations")
    public EscalationHistoryView escalations(@PathVariable String incidentKey) {
        return service.escalationHistory(incidentKey);
    }

    /**
     * 单事件遏制逾期检查：以服务端注入 Clock 的当前时刻评估，逾期且仍 COMMANDING
     * 时追加一条 OPEN 升级记录；携带 commandKey，同键重放首次结果。
     */
    @PostMapping("/{incidentKey}/escalations/check")
    public EscalationHistoryView checkEscalation(@PathVariable String incidentKey,
                                                 @RequestBody EscalationCheckRequest req) {
        return service.checkEscalation(incidentKey, req);
    }

    /**
     * 确认 OPEN 升级记录（仅当前指挥人，提交非空处置说明）。
     */
    @PostMapping("/{incidentKey}/escalations/acknowledge")
    public EscalationView acknowledgeEscalation(@PathVariable String incidentKey,
                                                @RequestHeader("X-Actor-Id") String actor,
                                                @RequestBody EscalationAckRequest req) {
        return service.acknowledgeEscalation(incidentKey, actor, req);
    }

    /**
     * 首次接管：REPORTED → COMMANDING。
     */
    @PostMapping("/{incidentKey}/takeover")
    public IncidentView takeover(@PathVariable String incidentKey,
                                 @RequestHeader("X-Actor-Id") String actor,
                                 @RequestBody TakeoverRequest req) {
        return service.takeover(incidentKey, actor, req);
    }

    /**
     * 发起指挥交接（当前指挥人指定目标人）。
     */
    @PostMapping("/{incidentKey}/transfers")
    public TransferView initiateTransfer(@PathVariable String incidentKey,
                                         @RequestHeader("X-Actor-Id") String actor,
                                         @RequestBody TransferRequest req) {
        return service.initiateTransfer(incidentKey, actor, req);
    }

    /**
     * 接受指挥交接（目标人接受后原子切换指挥权）。
     */
    @PostMapping("/{incidentKey}/transfers/accept")
    public IncidentView acceptTransfer(@PathVariable String incidentKey,
                                       @RequestHeader("X-Actor-Id") String actor,
                                       @RequestBody TransferAcceptRequest req) {
        return service.acceptTransfer(incidentKey, actor, req);
    }

    /**
     * 追加处置记录（仅当前指挥人）。
     */
    @PostMapping("/{incidentKey}/actions")
    public ActionView addAction(@PathVariable String incidentKey,
                                @RequestHeader("X-Actor-Id") String actor,
                                @RequestBody ActionRequest req) {
        return service.addAction(incidentKey, actor, req);
    }

    /**
     * 状态变更（仅当前指挥人，逐级前进）。
     */
    @PostMapping("/{incidentKey}/status")
    public IncidentView changeStatus(@PathVariable String incidentKey,
                                     @RequestHeader("X-Actor-Id") String actor,
                                     @RequestBody StatusRequest req) {
        return service.changeStatus(incidentKey, actor, req);
    }

    /**
     * 创建处置任务（仅当前指挥人；阻塞事件 0~5 个，拒绝环依赖）。
     */
    @PostMapping("/{incidentKey}/tasks")
    public TaskView createTask(@PathVariable String incidentKey,
                               @RequestHeader("X-Actor-Id") String actor,
                               @RequestBody TaskCreateRequest req) {
        return service.createTask(incidentKey, actor, req);
    }

    /**
     * 按事件分组查询任务（含阻塞状态，只读）。
     */
    @GetMapping("/{incidentKey}/tasks")
    public IncidentTasksView listTasks(@PathVariable String incidentKey) {
        return service.listTasks(incidentKey);
    }

    /**
     * 查询单任务明细（含阻塞状态，只读）。
     */
    @GetMapping("/{incidentKey}/tasks/{taskKey}")
    public TaskView getTask(@PathVariable String incidentKey, @PathVariable String taskKey) {
        return service.getTask(incidentKey, taskKey);
    }

    /**
     * 完成任务（仅当前指挥人；全部阻塞解除后才可完成）。
     */
    @PostMapping("/{incidentKey}/tasks/{taskKey}/complete")
    public TaskView completeTask(@PathVariable String incidentKey, @PathVariable String taskKey,
                                 @RequestHeader("X-Actor-Id") String actor,
                                 @RequestBody TaskActionRequest req) {
        return service.completeTask(incidentKey, taskKey, actor, req);
    }

    /**
     * 取消任务（仅当前指挥人；仅 OPEN 可取消）。
     */
    @PostMapping("/{incidentKey}/tasks/{taskKey}/cancel")
    public TaskView cancelTask(@PathVariable String incidentKey, @PathVariable String taskKey,
                               @RequestHeader("X-Actor-Id") String actor,
                               @RequestBody TaskActionRequest req) {
        return service.cancelTask(incidentKey, taskKey, actor, req);
    }

    /**
     * 封存指挥日志：对未关闭事件按截至 UTC 时刻（左闭右开）固化指令、交接、
     * 任务状态与依赖边，生成事件内版本递增的不可变日志包。
     */
    @PostMapping("/{incidentKey}/log-seals")
    public SealView sealLog(@PathVariable String incidentKey,
                            @RequestHeader("X-Actor-Id") String actor,
                            @RequestBody SealLogRequest req) {
        return sealService.seal(incidentKey, actor, req);
    }

    /**
     * 查询事件全部封存包（按版本升序，不含明细），只读。
     */
    @GetMapping("/{incidentKey}/log-seals")
    public SealListView listSeals(@PathVariable String incidentKey) {
        return sealService.listSeals(incidentKey);
    }

    /**
     * 查询单个封存包详情：全部明细（按发生时刻、同刻标识排序）与更正附录，只读。
     */
    @GetMapping("/{incidentKey}/log-seals/{version}")
    public SealDetailView getSeal(@PathVariable String incidentKey, @PathVariable int version) {
        return sealService.getSeal(incidentKey, version);
    }

    /**
     * 封存包验真诊断：按当前明细重算摘要并与封存时摘要比对，只读。
     */
    @GetMapping("/{incidentKey}/log-seals/{version}/verify")
    public SealVerifyView verifySeal(@PathVariable String incidentKey, @PathVariable int version) {
        return sealService.verifySeal(incidentKey, version);
    }

    /**
     * 追加封存更正附录（已关闭事件仍允许）；不修改原包，仅追加引用原版本与原记录的更正。
     */
    @PostMapping("/{incidentKey}/log-seals/{version}/corrections")
    public SealCorrectionView addCorrection(
            @PathVariable String incidentKey, @PathVariable int version,
            @RequestHeader("X-Actor-Id") String actor,
            @RequestBody SealCorrectionRequest req) {
        return sealService.appendCorrection(incidentKey, version, actor, req);
    }
}
