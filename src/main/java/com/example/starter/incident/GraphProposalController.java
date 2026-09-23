package com.example.starter.incident;

import java.util.List;

import com.example.starter.incident.dto.Requests.GraphActivateRequest;
import com.example.starter.incident.dto.Requests.GraphProposalCreateRequest;
import com.example.starter.incident.dto.Requests.GraphVoteRequest;
import com.example.starter.incident.dto.Responses.GraphView;
import com.example.starter.incident.dto.Responses.ProposalView;
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
 * 跨事件依赖图与变更提案 REST API。写操作均要求 X-Actor-Id 请求头标识操作人；
 * 提案创建人为提案人，投票/激活操作人须为提案名册成员。
 */
@RestController
@RequestMapping("/api/graph")
public class GraphProposalController {

    private final GraphProposalService service;

    public GraphProposalController(GraphProposalService service) {
        this.service = service;
    }

    /**
     * 查询当前依赖图：版本号与完整边集（只读，稳定排序）。
     */
    @GetMapping
    public GraphView getGraph() {
        return service.getGraph();
    }

    /**
     * 创建依赖图变更提案：1~50 条增删边（结构化去重、换序等价），
     * 冻结受影响事件当时指挥官与安全审核员为不可变名册。
     */
    @PostMapping("/proposals")
    public ResponseEntity<ProposalView> create(@RequestHeader("X-Actor-Id") String actor,
                                               @RequestBody GraphProposalCreateRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createProposal(actor, req));
    }

    /**
     * 查询提案详情（含名册、票决与激活前后快照，只读）。
     */
    @GetMapping("/proposals/{proposalKey}")
    public ProposalView get(@PathVariable String proposalKey) {
        return service.getProposal(proposalKey);
    }

    /**
     * 按 graphVersion 还原提案证据：返回在该版本激活的提案及前后边集快照（只读，稳定排序）。
     */
    @GetMapping("/proposals")
    public List<ProposalView> listByGraphVersion(@RequestParam long graphVersion) {
        return service.listByGraphVersion(graphVersion);
    }

    /**
     * 名册成员投票：仅首票有效；任一反对即整案 REJECTED，全部席位赞成后 APPROVED。
     */
    @PostMapping("/proposals/{proposalKey}/votes")
    public ProposalView vote(@PathVariable String proposalKey,
                             @RequestHeader("X-Actor-Id") String actor,
                             @RequestBody GraphVoteRequest req) {
        return service.vote(proposalKey, actor, req);
    }

    /**
     * 激活 APPROVED 提案：单事务内重读完整依赖图并校验版本与后态，
     * 成功只生成一个新 graphVersion 并保存前后边集快照。
     */
    @PostMapping("/proposals/{proposalKey}/activate")
    public ProposalView activate(@PathVariable String proposalKey,
                                 @RequestHeader("X-Actor-Id") String actor,
                                 @RequestBody GraphActivateRequest req) {
        return service.activate(proposalKey, actor, req);
    }
}
