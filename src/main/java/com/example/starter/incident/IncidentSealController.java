package com.example.starter.incident;

import com.example.starter.incident.dto.Requests.AppendixRequest;
import com.example.starter.incident.dto.Requests.SealCreateRequest;
import com.example.starter.incident.dto.Responses.AppendixHistoryView;
import com.example.starter.incident.dto.Responses.AppendixView;
import com.example.starter.incident.dto.Responses.SealDetailView;
import com.example.starter.incident.dto.Responses.SealDiagnosticsView;
import com.example.starter.incident.dto.Responses.SealHistoryView;
import com.example.starter.incident.dto.Responses.SealView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 事件指挥日志封存 REST API。写操作要求 X-Actor-Id 请求头标识操作人；
 * 查询接口只读，不改变状态。
 */
@RestController
@RequestMapping("/api/incidents/{incidentKey}/seals")
public class IncidentSealController {

    private final IncidentSealService service;

    public IncidentSealController(IncidentSealService service) {
        this.service = service;
    }

    /**
     * 创建封存包：封存 sealUntil（左闭右开，UTC）前的指令、交接、任务状态与依赖图，
     * 生成事件内递增版本的不可变日志包；已关闭事件不得新增封存包。
     */
    @PostMapping
    public SealView createSeal(@PathVariable String incidentKey,
                               @RequestHeader("X-Actor-Id") String actor,
                               @RequestBody SealCreateRequest req) {
        return service.createSeal(incidentKey, actor, req);
    }

    /**
     * 查询封存包历史（按版本升序，只读）。
     */
    @GetMapping
    public SealHistoryView listSeals(@PathVariable String incidentKey) {
        return service.listSeals(incidentKey);
    }

    /**
     * 查询封存包明细：包头、封存记录、固化依赖边与该包更正附录（只读）。
     */
    @GetMapping("/{version}")
    public SealDetailView getSeal(@PathVariable String incidentKey, @PathVariable int version) {
        return service.getSeal(incidentKey, version);
    }

    /**
     * 追加更正附录：引用原包版本与原记录标识，保留原值、新值、理由与操作人；
     * 不修改原包；已关闭事件允许追加。
     */
    @PostMapping("/appendices")
    public AppendixView appendAppendix(@PathVariable String incidentKey,
                                       @RequestHeader("X-Actor-Id") String actor,
                                       @RequestBody AppendixRequest req) {
        return service.appendAppendix(incidentKey, actor, req);
    }

    /**
     * 查询事件全部更正附录（按追加顺序，只读）。
     */
    @GetMapping("/appendices")
    public AppendixHistoryView listAppendices(@PathVariable String incidentKey) {
        return service.listAppendices(incidentKey);
    }

    /**
     * 封存诊断查询：包数量、最新版本、截至时刻范围、记录/边/附录总数（只读）。
     */
    @GetMapping("/diagnostics")
    public SealDiagnosticsView diagnostics(@PathVariable String incidentKey) {
        return service.diagnostics(incidentKey);
    }
}
