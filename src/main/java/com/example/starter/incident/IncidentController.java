package com.example.starter.incident;

import com.example.starter.incident.dto.Requests.ActionRequest;
import com.example.starter.incident.dto.Requests.DecisionCreateRequest;
import com.example.starter.incident.dto.Requests.DecisionExpireCheckRequest;
import com.example.starter.incident.dto.Requests.EscalationAckRequest;
import com.example.starter.incident.dto.Requests.EscalationCheckRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.StatusRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Requests.TaskReviseRequest;
import com.example.starter.incident.dto.Requests.TransferAcceptRequest;
import com.example.starter.incident.dto.Requests.TransferRequest;
import com.example.starter.incident.dto.Requests.VoteRequest;
import com.example.starter.incident.dto.Requests.VoteWithdrawRequest;
import com.example.starter.incident.dto.Responses.ActionView;
import com.example.starter.incident.dto.Responses.DecisionDiagnosticsView;
import com.example.starter.incident.dto.Responses.DecisionHistoryView;
import com.example.starter.incident.dto.Responses.DecisionListView;
import com.example.starter.incident.dto.Responses.DecisionView;
import com.example.starter.incident.dto.Responses.EscalationHistoryView;
import com.example.starter.incident.dto.Responses.EscalationView;
import com.example.starter.incident.dto.Responses.HistoryView;
import com.example.starter.incident.dto.Responses.IncidentTasksView;
import com.example.starter.incident.dto.Responses.IncidentView;
import com.example.starter.incident.dto.Responses.TaskView;
import com.example.starter.incident.dto.Responses.TransferView;
import com.example.starter.incident.dto.Responses.VoteView;
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
    private final DecisionService decisionService;

    public IncidentController(IncidentService service, DecisionService decisionService) {
        this.service = service;
        this.decisionService = decisionService;
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
     * 任务修订（仅当前指挥人；仅 OPEN 可修订；版本加 1 并使提案旧票转 STALE）。
     */
    @PostMapping("/{incidentKey}/tasks/{taskKey}/revise")
    public TaskView reviseTask(@PathVariable String incidentKey, @PathVariable String taskKey,
                               @RequestHeader("X-Actor-Id") String actor,
                               @RequestBody TaskReviseRequest req) {
        return service.reviseTask(incidentKey, taskKey, actor, req);
    }

    /**
     * 登记决策提案（仅当前指挥人；登记 UTC 到期时刻、所需角色集合与法定人数）。
     */
    @PostMapping("/{incidentKey}/decisions")
    public ResponseEntity<DecisionView> registerDecision(@PathVariable String incidentKey,
                                                         @RequestHeader("X-Actor-Id") String actor,
                                                         @RequestBody DecisionCreateRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(decisionService.register(incidentKey, actor, req));
    }

    /**
     * 按事件查询全部决策提案（按登记顺序，只读）。
     */
    @GetMapping("/{incidentKey}/decisions")
    public DecisionListView listDecisions(@PathVariable String incidentKey) {
        return decisionService.list(incidentKey);
    }

    /**
     * 查询决策提案明细（含当前计票统计，只读）。
     */
    @GetMapping("/{incidentKey}/decisions/{proposalKey}")
    public DecisionView getDecision(@PathVariable String incidentKey,
                                    @PathVariable String proposalKey) {
        return decisionService.get(incidentKey, proposalKey);
    }

    /**
     * 查询提案完整历史（全部投票证据，含 WITHDRAWN/STALE，只读）。
     */
    @GetMapping("/{incidentKey}/decisions/{proposalKey}/history")
    public DecisionHistoryView decisionHistory(@PathVariable String incidentKey,
                                               @PathVariable String proposalKey) {
        return decisionService.history(incidentKey, proposalKey);
    }

    /**
     * 查询提案诊断（各状态票数与法定人数差额，只读）。
     */
    @GetMapping("/{incidentKey}/decisions/{proposalKey}/diagnostics")
    public DecisionDiagnosticsView decisionDiagnostics(@PathVariable String incidentKey,
                                                       @PathVariable String proposalKey) {
        return decisionService.diagnostics(incidentKey, proposalKey);
    }

    /**
     * 投票（角色须属于所需角色集合；到期后投票返回 410 且提案转 EXPIRED）。
     */
    @PostMapping("/{incidentKey}/decisions/{proposalKey}/votes")
    public VoteView vote(@PathVariable String incidentKey, @PathVariable String proposalKey,
                         @RequestHeader("X-Actor-Id") String actor,
                         @RequestBody VoteRequest req) {
        return decisionService.vote(incidentKey, proposalKey, actor, req);
    }

    /**
     * 撤回投票（仅本角色当前任务版本下的 CAST 票）。
     */
    @PostMapping("/{incidentKey}/decisions/{proposalKey}/votes/withdraw")
    public VoteView withdrawVote(@PathVariable String incidentKey, @PathVariable String proposalKey,
                                 @RequestHeader("X-Actor-Id") String actor,
                                 @RequestBody VoteWithdrawRequest req) {
        return decisionService.withdraw(incidentKey, proposalKey, actor, req);
    }

    /**
     * 到期判定：以服务端注入 Clock 的当前时刻评估，到期则提案转 EXPIRED；
     * 携带 commandKey，同键重放首次结果。
     */
    @PostMapping("/{incidentKey}/decisions/{proposalKey}/expire-check")
    public DecisionView expireCheck(@PathVariable String incidentKey,
                                    @PathVariable String proposalKey,
                                    @RequestBody DecisionExpireCheckRequest req) {
        return decisionService.expireCheck(incidentKey, proposalKey, req);
    }
}
