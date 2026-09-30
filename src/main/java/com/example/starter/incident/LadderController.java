package com.example.starter.incident;

import java.util.List;

import com.example.starter.incident.dto.Requests.LadderDemoteRequest;
import com.example.starter.incident.dto.Requests.LadderVersionCreateRequest;
import com.example.starter.incident.dto.Requests.ScoreBatchRequest;
import com.example.starter.incident.dto.Responses.LadderVersionView;
import com.example.starter.incident.dto.Responses.LadderView;
import com.example.starter.incident.dto.Responses.ScoreBatchView;
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
 * 事件升级阶梯 REST API：阈值版本管理、批量风险分数更新与人工降级。
 * 写操作均要求 X-Actor-Id 标识操作人并携带 commandKey 幂等键。
 */
@RestController
@RequestMapping("/api")
public class LadderController {

    private final LadderService service;

    public LadderController(LadderService service) {
        this.service = service;
    }

    /**
     * 创建不可变阈值版本：阈值严格递增、冷却范围合法，否则 422；versionKey 重复 409。
     */
    @PostMapping("/ladder/versions")
    public ResponseEntity<LadderVersionView> createVersion(@RequestHeader("X-Actor-Id") String actor,
                                                           @RequestBody LadderVersionCreateRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createVersion(actor, req));
    }

    /**
     * 查询全部阈值版本（只读诊断，排序稳定，不修改状态）。
     */
    @GetMapping("/ladder/versions")
    public List<LadderVersionView> listVersions() {
        return service.listVersions();
    }

    /**
     * 批量风险分数更新：先校验所有事件最终等级，任一失败整批回滚。
     */
    @PostMapping("/ladder/scores")
    public ScoreBatchView submitScores(@RequestHeader("X-Actor-Id") String actor,
                                       @RequestBody ScoreBatchRequest req) {
        return service.submitScores(actor, req);
    }

    /**
     * 查询事件升级阶梯明细（当前等级、冷却状态、等级历史与不可变分数证据），只读。
     */
    @GetMapping("/incidents/{incidentKey}/ladder")
    public LadderView getLadder(@PathVariable String incidentKey) {
        return service.getLadder(incidentKey);
    }

    /**
     * 人工降级：须由不同于当前指挥人的指挥员确认、冷却已满足且逐级下降。
     */
    @PostMapping("/incidents/{incidentKey}/ladder/demote")
    public LadderView demote(@PathVariable String incidentKey,
                             @RequestHeader("X-Actor-Id") String actor,
                             @RequestBody LadderDemoteRequest req) {
        return service.demote(incidentKey, actor, req);
    }
}
