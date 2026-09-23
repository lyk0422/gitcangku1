package com.example.starter.incident;

import com.example.starter.incident.dto.Requests.ProposalCreateRequest;
import com.example.starter.incident.dto.Requests.ProposalVoteRequest;
import com.example.starter.incident.dto.Responses.GraphEvidenceView;
import com.example.starter.incident.dto.Responses.ProposalView;
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
 * 跨事件依赖图变更提案 REST API。写操作均要求 X-Actor-Id 请求头标识操作人。
 */
@RestController
@RequestMapping("/api/dependency-graph")
public class ProposalController {

    private final ProposalService service;

    public ProposalController(ProposalService service) {
        this.service = service;
    }

    /** 创建依赖图变更提案（冻结名册）。 */
    @PostMapping("/proposals")
    public ResponseEntity<ProposalView> create(@RequestHeader("X-Actor-Id") String actor,
                                               @RequestBody ProposalCreateRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(actor, req));
    }

    /** 名册人员投票（YES/NO）。 */
    @PostMapping("/proposals/{proposalKey}/votes")
    public ProposalView vote(@PathVariable String proposalKey,
                             @RequestHeader("X-Actor-Id") String actor,
                             @RequestBody ProposalVoteRequest req) {
        return service.vote(proposalKey, actor, req);
    }

    /** 查询提案证据（名册、票决、前后边集快照，只读）。 */
    @GetMapping("/proposals/{proposalKey}")
    public ProposalView get(@PathVariable String proposalKey) {
        return service.get(proposalKey);
    }

    /** 按图版本还原证据（版本号从 1 开始，当前版本及生效提案）。 */
    @GetMapping("/versions/{graphVersion}")
    public GraphEvidenceView evidence(@PathVariable long graphVersion) {
        return service.evidenceAtVersion(graphVersion);
    }
}
