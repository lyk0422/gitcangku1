package com.example.starter.incident.ladder;

import java.util.List;

import com.example.starter.incident.ladder.dto.LadderRequests.BatchScoreRequest;
import com.example.starter.incident.ladder.dto.LadderRequests.DemoteRequest;
import com.example.starter.incident.ladder.dto.LadderRequests.PublishVersionRequest;
import com.example.starter.incident.ladder.dto.LadderResponses.BatchScoreResponse;
import com.example.starter.incident.ladder.dto.LadderResponses.DemoteResponse;
import com.example.starter.incident.ladder.dto.LadderResponses.LadderDetailView;
import com.example.starter.incident.ladder.dto.LadderResponses.LadderDiagnosticsView;
import com.example.starter.incident.ladder.dto.LadderResponses.LadderHistoryView;
import com.example.starter.incident.ladder.dto.LadderResponses.ThresholdVersionView;
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
 * 升级阶梯 REST API。写操作均要求 X-Actor-Id 请求头标识操作人，并携带 commandKey 幂等键。
 * 所有 GET 接口只读，不修改任何状态。
 */
@RestController
@RequestMapping("/api/ladder")
public class LadderController {

    private final LadderService service;

    public LadderController(LadderService service) {
        this.service = service;
    }

    /**
     * 发布不可变阈值版本并切换为当前生效版本；阈值非严格递增或冷却越界返回 422，版本键冲突 409。
     */
    @PostMapping("/versions")
    public ResponseEntity<ThresholdVersionView> publishVersion(
            @RequestHeader("X-Actor-Id") String actor,
            @RequestBody PublishVersionRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.publishVersion(actor, req));
    }

    /**
     * 查询全部已发布阈值版本（按发布顺序）。只读。
     */
    @GetMapping("/versions")
    public List<ThresholdVersionView> listVersions() {
        return service.listVersions();
    }

    /**
     * 批量风险分数更新：先校验全部事件最终等级，任一失败整批回滚。
     */
    @PostMapping("/scores/batch")
    public BatchScoreResponse batchUpdateScores(@RequestHeader("X-Actor-Id") String actor,
                                                @RequestBody BatchScoreRequest req) {
        return service.batchUpdateScores(actor, req);
    }

    /**
     * 降级确认：不同指挥员、超过冷却、逐级下降，锚定 expectedLevel。
     */
    @PostMapping("/incidents/{incidentKey}/demote")
    public DemoteResponse demote(@PathVariable String incidentKey,
                                 @RequestHeader("X-Actor-Id") String actor,
                                 @RequestBody DemoteRequest req) {
        return service.demote(incidentKey, actor, req);
    }

    /**
     * 查询事件阶梯明细（实际等级/要求等级/差额、冷却实际/要求/剩余值）。只读。
     */
    @GetMapping("/incidents/{incidentKey}")
    public LadderDetailView detail(@PathVariable String incidentKey) {
        return service.detail(incidentKey);
    }

    /**
     * 查询事件完整等级历史（按落库顺序）。只读。
     */
    @GetMapping("/incidents/{incidentKey}/history")
    public LadderHistoryView history(@PathVariable String incidentKey) {
        return service.history(incidentKey);
    }

    /**
     * 全量诊断：全部已登记事件的等级/分数/冷却状态，按要求等级降序、事件键升序稳定排序。只读。
     */
    @GetMapping("/diagnostics")
    public LadderDiagnosticsView diagnostics() {
        return service.diagnostics();
    }
}
