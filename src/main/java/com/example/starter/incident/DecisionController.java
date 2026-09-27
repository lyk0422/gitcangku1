package com.example.starter.incident;

import com.example.starter.incident.dto.Requests.ProposalCreateRequest;
import com.example.starter.incident.dto.Requests.TaskReviseRequest;
import com.example.starter.incident.dto.Requests.VoteRequest;
import com.example.starter.incident.dto.Requests.WithdrawRequest;
import com.example.starter.incident.dto.Responses.ProposalDetailView;
import com.example.starter.incident.dto.Responses.ProposalListView;
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
 * 事件决策提案 REST API：提案登记、投票、撤回、任务修订及明细/列表查询。
 * 写操作均要求 X-Actor-Id 请求头；登记与修订仅当前指挥人，投票/撤回为角色集合成员。
 */
@RestController
@RequestMapping("/api/incidents")
public class DecisionController {

    private final DecisionService service;

    public DecisionController(DecisionService service) {
        this.service = service;
    }

    /**
     * 登记决策提案（仅当前指挥人）：UTC 到期时刻、所需角色集合与法定人数。
     */
    @PostMapping("/{incidentKey}/proposals")
    public ResponseEntity<ProposalView> createProposal(@PathVariable String incidentKey,
                                                       @RequestHeader("X-Actor-Id") String actor,
                                                       @RequestBody ProposalCreateRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                service.createProposal(incidentKey, actor, req));
    }

    /**
     * 按事件查询提案列表（按登记顺序，只读）。
     */
    @GetMapping("/{incidentKey}/proposals")
    public ProposalListView listProposals(@PathVariable String incidentKey) {
        return service.listProposals(incidentKey);
    }

    /**
     * 查询提案明细（含完整投票历史，只读）。
     */
    @GetMapping("/{incidentKey}/proposals/{proposalKey}")
    public ProposalDetailView getProposal(@PathVariable String incidentKey,
                                          @PathVariable String proposalKey) {
        return service.getProposal(incidentKey, proposalKey);
    }

    /**
     * 投票（SUPPORT/OPPOSE，投票人须在角色集合内，同一角色至多一票有效票）。
     */
    @PostMapping("/{incidentKey}/proposals/{proposalKey}/votes")
    public ProposalDetailView vote(@PathVariable String incidentKey,
                                   @PathVariable String proposalKey,
                                   @RequestHeader("X-Actor-Id") String actor,
                                   @RequestBody VoteRequest req) {
        return service.vote(incidentKey, proposalKey, actor, req);
    }

    /**
     * 撤回自己的有效票。
     */
    @PostMapping("/{incidentKey}/proposals/{proposalKey}/withdraw")
    public ProposalDetailView withdraw(@PathVariable String incidentKey,
                                       @PathVariable String proposalKey,
                                       @RequestHeader("X-Actor-Id") String actor,
                                       @RequestBody WithdrawRequest req) {
        return service.withdraw(incidentKey, proposalKey, actor, req);
    }

    /**
     * 任务修订（仅当前指挥人）：版本 +1，旧版本有效票转 STALE。
     */
    @PostMapping("/{incidentKey}/tasks/{taskKey}/revise")
    public TaskView reviseTask(@PathVariable String incidentKey, @PathVariable String taskKey,
                               @RequestHeader("X-Actor-Id") String actor,
                               @RequestBody TaskReviseRequest req) {
        return service.reviseTask(incidentKey, taskKey, actor, req);
    }
}
