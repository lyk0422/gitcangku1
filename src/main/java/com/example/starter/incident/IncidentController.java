package com.example.starter.incident;

import java.util.List;
import java.util.Map;

import com.example.starter.incident.dto.Requests.ActionRequest;
import com.example.starter.incident.dto.Requests.CleanupRequest;
import com.example.starter.incident.dto.Requests.DependencyRequest;
import com.example.starter.incident.dto.Requests.EscalateRequest;
import com.example.starter.incident.dto.Requests.CancelRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.StatusRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TransferAcceptRequest;
import com.example.starter.incident.dto.Requests.TransferRequest;
import com.example.starter.incident.dto.Responses.ActionView;
import com.example.starter.incident.dto.Responses.CleanupView;
import com.example.starter.incident.dto.Responses.DependencyView;
import com.example.starter.incident.dto.Responses.DrillBatchView;
import com.example.starter.incident.dto.Responses.HistoryView;
import com.example.starter.incident.dto.Responses.IncidentView;
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
 *
 * <p>域路由：默认所有路由作用于真实域 REAL；操作演练沙盘事件时对同一组路由加
 * {@code drill=true} 查询参数。上报时请求体携带 drillKey（及 batchKey）即落入演练域。
 * 列表/统计默认只返回真实域，须显式 {@code includeDrill=true} 才包含演练域，
 * 且演练结果一律带 {@code "domain":"DRILL"} 标注。</p>
 */
@RestController
@RequestMapping("/api")
public class IncidentController {

    private final IncidentService service;

    public IncidentController(IncidentService service) {
        this.service = service;
    }

    private static Domain domain(boolean drill) {
        return drill ? Domain.DRILL : Domain.REAL;
    }

    /**
     * 上报事件：初始状态 REPORTED。请求体携带 drillKey 时为演练事件（须同时给 batchKey）。
     */
    @PostMapping("/incidents")
    public ResponseEntity<IncidentView> report(@RequestBody ReportRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.report(req));
    }

    /**
     * 事件清单：默认仅真实域；includeDrill=true 时附带演练域，结果均带 domain 标注。
     */
    @GetMapping("/incidents")
    public List<IncidentView> list(@RequestParam(defaultValue = "false") boolean includeDrill) {
        return service.list(includeDrill);
    }

    /**
     * 两域独立统计：默认仅真实域；includeDrill=true 时附带演练域。
     */
    @GetMapping("/stats")
    public Map<String, Object> stats(@RequestParam(defaultValue = "false") boolean includeDrill) {
        return service.stats(includeDrill);
    }

    /**
     * 查询事件当前状态。drill=true 查询演练域。
     */
    @GetMapping("/incidents/{incidentKey}")
    public IncidentView get(@PathVariable String incidentKey,
                            @RequestParam(defaultValue = "false") boolean drill) {
        return service.get(domain(drill), incidentKey);
    }

    /**
     * 查询事件完整历史（状态流转、处置记录、交接、升级、依赖边）。
     */
    @GetMapping("/incidents/{incidentKey}/history")
    public HistoryView history(@PathVariable String incidentKey,
                               @RequestParam(defaultValue = "false") boolean drill) {
        return service.history(domain(drill), incidentKey);
    }

    /**
     * 首次接管：REPORTED → COMMANDING。
     */
    @PostMapping("/incidents/{incidentKey}/takeover")
    public IncidentView takeover(@PathVariable String incidentKey,
                                 @RequestParam(defaultValue = "false") boolean drill,
                                 @RequestHeader("X-Actor-Id") String actor,
                                 @RequestBody TakeoverRequest req) {
        return service.takeover(domain(drill), incidentKey, actor, req);
    }

    /**
     * 发起指挥交接（当前指挥人指定目标人）。
     */
    @PostMapping("/incidents/{incidentKey}/transfers")
    public TransferView initiateTransfer(@PathVariable String incidentKey,
                                         @RequestParam(defaultValue = "false") boolean drill,
                                         @RequestHeader("X-Actor-Id") String actor,
                                         @RequestBody TransferRequest req) {
        return service.initiateTransfer(domain(drill), incidentKey, actor, req);
    }

    /**
     * 接受指挥交接（目标人接受后原子切换指挥权）。
     */
    @PostMapping("/incidents/{incidentKey}/transfers/accept")
    public IncidentView acceptTransfer(@PathVariable String incidentKey,
                                       @RequestParam(defaultValue = "false") boolean drill,
                                       @RequestHeader("X-Actor-Id") String actor,
                                       @RequestBody TransferAcceptRequest req) {
        return service.acceptTransfer(domain(drill), incidentKey, actor, req);
    }

    /**
     * 追加处置记录（仅当前指挥人）。
     */
    @PostMapping("/incidents/{incidentKey}/actions")
    public ActionView addAction(@PathVariable String incidentKey,
                                @RequestParam(defaultValue = "false") boolean drill,
                                @RequestHeader("X-Actor-Id") String actor,
                                @RequestBody ActionRequest req) {
        return service.addAction(domain(drill), incidentKey, actor, req);
    }

    /**
     * 状态变更（仅当前指挥人，逐级前进）。
     */
    @PostMapping("/incidents/{incidentKey}/status")
    public IncidentView changeStatus(@PathVariable String incidentKey,
                                     @RequestParam(defaultValue = "false") boolean drill,
                                     @RequestHeader("X-Actor-Id") String actor,
                                     @RequestBody StatusRequest req) {
        return service.changeStatus(domain(drill), incidentKey, actor, req);
    }

    /**
     * 升级事件严重等级（仅当前指挥人，只能升到更高等级）。
     * 演练域升级（drill=true）不触发真实域任何通知或副作用。
     */
    @PostMapping("/incidents/{incidentKey}/escalate")
    public IncidentView escalate(@PathVariable String incidentKey,
                                 @RequestParam(defaultValue = "false") boolean drill,
                                 @RequestHeader("X-Actor-Id") String actor,
                                 @RequestBody EscalateRequest req) {
        return service.escalate(domain(drill), incidentKey, actor, req);
    }

    /**
     * 建立处置任务依赖（阻塞）边；阻塞事件必须与当前事件同域，跨域引用返回 422。
     */
    @PostMapping("/incidents/{incidentKey}/dependencies")
    public DependencyView addDependency(@PathVariable String incidentKey,
                                        @RequestParam(defaultValue = "false") boolean drill,
                                        @RequestHeader("X-Actor-Id") String actor,
                                        @RequestBody DependencyRequest req) {
        return service.addDependency(domain(drill), incidentKey, actor, req);
    }

    /**
     * 取消演练事件（仅演练域、仅 REPORTED、仅上报人），进入 CANCELLED 终态。
     */
    @PostMapping("/drill/incidents/{incidentKey}/cancel")
    public IncidentView cancelDrill(@PathVariable String incidentKey,
                                    @RequestHeader("X-Actor-Id") String actor,
                                    @RequestBody CancelRequest req) {
        return service.cancelDrill(incidentKey, actor, req);
    }

    /**
     * 演练批次批量清理：单事务校验全部终态后原子删除；未终结整批 422 并列事件。
     */
    @PostMapping("/drill/cleanups")
    public CleanupView cleanupDrillBatch(@RequestBody CleanupRequest req) {
        return service.cleanupDrillBatch(req);
    }

    /**
     * 演练批次清单与清理历史（含已清理墓碑）。
     */
    @GetMapping("/drill/batches")
    public List<DrillBatchView> listDrillBatches() {
        return service.listDrillBatches();
    }

    /**
     * 按批次查询演练事件清单与该批次清理信息。
     */
    @GetMapping("/drill/batches/{batchKey}")
    public DrillBatchView getDrillBatch(@PathVariable String batchKey) {
        return service.getDrillBatch(batchKey);
    }
}
