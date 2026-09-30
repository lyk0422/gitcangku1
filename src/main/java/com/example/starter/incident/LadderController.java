package com.example.starter.incident;

import com.example.starter.incident.dto.Requests.LadderConfigRequest;
import com.example.starter.incident.dto.Requests.LadderDemoteRequest;
import com.example.starter.incident.dto.Requests.LadderScoreBatchRequest;
import com.example.starter.incident.dto.Responses.LadderConfigView;
import com.example.starter.incident.dto.Responses.LadderHistoryView;
import com.example.starter.incident.dto.Responses.LadderScoreBatchView;
import com.example.starter.incident.dto.Responses.LadderStateView;
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
 * 事件升级阶梯 REST API：阈值配置版本化管理、批量分数更新、降级确认与只读查询。
 * 写操作均要求 X-Actor-Id 请求头标识操作人，并携带 commandKey 幂等键。
 */
@RestController
@RequestMapping("/api/incidents")
public class LadderController {

    private final LadderService service;

    public LadderController(LadderService service) {
        this.service = service;
    }

    /**
     * 创建阈值配置新版本（阈值严格递增，冷却 1~10080 分钟）。
     */
    @PostMapping("/ladder-configs")
    public ResponseEntity<LadderConfigView> createConfig(@RequestHeader("X-Actor-Id") String actor,
                                                         @RequestBody LadderConfigRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createConfig(actor, req));
    }

    /**
     * 查询当前（最新版本）阈值配置（只读）。
     */
    @GetMapping("/ladder-configs/current")
    public LadderConfigView currentConfig() {
        return service.currentConfig();
    }

    /**
     * 批量更新风险分数：先校验全部事件最终等级，任一失败整批回滚。
     */
    @PostMapping("/ladder/scores")
    public LadderScoreBatchView updateScores(@RequestHeader("X-Actor-Id") String actor,
                                             @RequestBody LadderScoreBatchRequest req) {
        return service.updateScores(actor, req);
    }

    /**
     * 查询事件阶梯当前状态与冷却诊断（只读，不修改状态）。
     */
    @GetMapping("/{incidentKey}/ladder")
    public LadderStateView getLadder(@PathVariable String incidentKey) {
        return service.getLadder(incidentKey);
    }

    /**
     * 查询事件阶梯等级变更历史（只读，按发生顺序）。
     */
    @GetMapping("/{incidentKey}/ladder/history")
    public LadderHistoryView getHistory(@PathVariable String incidentKey) {
        return service.getHistory(incidentKey);
    }

    /**
     * 降级一级：须由不同指挥员确认且冷却已过；不允许跳级。
     */
    @PostMapping("/{incidentKey}/ladder/demote")
    public LadderStateView demote(@PathVariable String incidentKey,
                                  @RequestHeader("X-Actor-Id") String actor,
                                  @RequestBody LadderDemoteRequest req) {
        return service.demote(incidentKey, actor, req);
    }
}
