package com.example.starter.incident;

import com.example.starter.incident.dto.Requests.ProposalCreateRequest;
import com.example.starter.incident.dto.Requests.TaskReviseRequest;
import com.example.starter.incident.dto.Requests.VoteRequest;
import com.example.starter.incident.dto.Requests.VoteWithdrawRequest;
import com.example.starter.incident.dto.Responses.DecisionListView;
import com.example.starter.incident.dto.Responses.ProposalDiagnosticsView;
import com.example.starter.incident.dto.Responses.ProposalView;
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
 * 事件处置提案（决策到期）REST API。写操作均要求 X-Actor-Id 请求头标识操作人，
 * 且仅事件当前指挥人可登记提案、修订任务；投票人以 X-Actor-Id + 角色标识。
 */
@RestController
@RequestMapping("/api/incidents")
public class DecisionController {

    private final DecisionService decisions;

    public DecisionController(DecisionService decisions) {
        this.decisions = decisions;
    }

    /**
     * 登记处置提案：固化 UTC 到期时刻、所需角色集合、法定人数与任务当前版本。
     */
    @PostMapping("/{incidentKey}/decisions")
    public ResponseEntity<ProposalView> createProposal(@PathVariable String incidentKey,
                                                       @RequestHeader("X-Actor-Id") String actor,
                                                       @RequestBody ProposalCreateRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                decisions.createProposal(incidentKey, actor, req));
    }

    /** 查询事件全部提案（按登记顺序），只读不改状态。 */
    @GetMapping("/{incidentKey}/decisions")
    public DecisionListView listProposals(@PathVariable String incidentKey) {
        return decisions.listProposals(incidentKey);
    }

    /** 查询单提案明细（含全部投票历史与计票），只读不改状态。 */
    @GetMapping("/{incidentKey}/decisions/{proposalKey}")
    public ProposalView getProposal(@PathVariable String incidentKey,
                                    @PathVariable String proposalKey) {
        return decisions.getProposal(incidentKey, proposalKey);
    }

    /** 查询提案诊断（剩余时长、票数实际值/要求值/差额、缺失角色），只读不改状态。 */
    @GetMapping("/{incidentKey}/decisions/{proposalKey}/diagnostics")
    public ProposalDiagnosticsView diagnostics(@PathVariable String incidentKey,
                                               @PathVariable String proposalKey) {
        return decisions.diagnostics(incidentKey, proposalKey);
    }

    /**
     * 投票：到期当刻及之后返回 410 且提案转 EXPIRED；到期前达法定人数原子写入任务变更。
     */
    @PostMapping("/{incidentKey}/decisions/{proposalKey}/votes")
    public ProposalView vote(@PathVariable String incidentKey,
                             @PathVariable String proposalKey,
                             @RequestHeader("X-Actor-Id") String actor,
                             @RequestBody VoteRequest req) {
        return decisions.vote(incidentKey, proposalKey, actor, req);
    }

    /** 撤回本人当前版本有效票（历史保留为 WITHDRAWN）。 */
    @PostMapping("/{incidentKey}/decisions/{proposalKey}/withdraw")
    public ProposalView withdraw(@PathVariable String incidentKey,
                                 @PathVariable String proposalKey,
                                 @RequestHeader("X-Actor-Id") String actor,
                                 @RequestBody VoteWithdrawRequest req) {
        return decisions.withdraw(incidentKey, proposalKey, actor, req);
    }

    /**
     * 任务修订：版本号 +1，引用该任务的 OPEN 提案旧版本票全部转 STALE；
     * 已到达到期时刻的 OPEN 提案按提交顺序先转 EXPIRED。
     */
    @PostMapping("/{incidentKey}/tasks/{taskKey}/revise")
    public TaskView reviseTask(@PathVariable String incidentKey,
                               @PathVariable String taskKey,
                               @RequestHeader("X-Actor-Id") String actor,
                               @RequestBody TaskReviseRequest req) {
        return decisions.reviseTask(incidentKey, taskKey, actor, req);
    }
}
