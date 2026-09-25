package com.example.starter.incident;

import com.example.starter.incident.dto.Requests.DispatchRequest;
import com.example.starter.incident.dto.Requests.ExemptionGrantRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.ZoneRegisterRequest;
import com.example.starter.incident.dto.Requests.ZoneReviseRequest;
import com.example.starter.incident.dto.Responses.DispatchView;
import com.example.starter.incident.dto.Responses.ExemptionView;
import com.example.starter.incident.dto.Responses.IncidentExemptionsView;
import com.example.starter.incident.dto.Responses.IncidentZoneBlocksView;
import com.example.starter.incident.dto.Responses.IncidentZonesView;
import com.example.starter.incident.dto.Responses.TaskView;
import com.example.starter.incident.dto.Responses.ZoneView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 事件疏散区域与高危任务进入门禁 REST API。
 * 写操作均要求 X-Actor-Id 请求头标识操作人；区域/豁免操作要求事件未终结，
 * 任务开始/派工/撤离登记要求操作人为当前指挥人。
 */
@RestController
@RequestMapping("/api/incidents")
public class EvacuationController {

    private final EvacuationService service;

    public EvacuationController(EvacuationService service) {
        this.service = service;
    }

    /**
     * 登记疏散区域：网格规范化排序，UTC 左闭右开窗口，同事件同等级窗口网格不可重叠。
     */
    @PostMapping("/{incidentKey}/zones")
    public ZoneView registerZone(@PathVariable String incidentKey,
                                 @RequestHeader("X-Actor-Id") String actor,
                                 @RequestBody ZoneRegisterRequest req) {
        return service.registerZone(incidentKey, actor, req);
    }

    /**
     * 修订疏散区域：产生新版本，旧版本豁免失效。
     */
    @PostMapping("/{incidentKey}/zones/{zoneKey}/revisions")
    public ZoneView reviseZone(@PathVariable String incidentKey, @PathVariable String zoneKey,
                               @RequestHeader("X-Actor-Id") String actor,
                               @RequestBody ZoneReviseRequest req) {
        return service.reviseZone(incidentKey, zoneKey, actor, req);
    }

    /**
     * 查询事件疏散区域（含全部谱系版本）。
     */
    @GetMapping("/{incidentKey}/zones")
    public IncidentZonesView listZones(@PathVariable String incidentKey) {
        return service.listZones(incidentKey);
    }

    /**
     * 签发撤离豁免（针对任务与区域当前版本，允许任务创建前预授权）。
     */
    @PostMapping("/{incidentKey}/exemptions")
    public ExemptionView grantExemption(@PathVariable String incidentKey,
                                        @RequestHeader("X-Actor-Id") String actor,
                                        @RequestBody ExemptionGrantRequest req) {
        return service.grantExemption(incidentKey, actor, req);
    }

    /**
     * 查询事件豁免及版本有效性。
     */
    @GetMapping("/{incidentKey}/exemptions")
    public IncidentExemptionsView listExemptions(@PathVariable String incidentKey) {
        return service.listExemptions(incidentKey);
    }

    /**
     * 查询事件任务阻断快照（含已解除历史）。
     */
    @GetMapping("/{incidentKey}/zone-blocks")
    public IncidentZoneBlocksView listBlocks(@PathVariable String incidentKey) {
        return service.listBlocks(incidentKey);
    }

    /**
     * 开始任务（仅当前指挥人；高危任务命中有效疏散区域须持有该区域版本豁免）。
     */
    @PostMapping("/{incidentKey}/tasks/{taskKey}/start")
    public TaskView startTask(@PathVariable String incidentKey, @PathVariable String taskKey,
                              @RequestHeader("X-Actor-Id") String actor,
                              @RequestBody TaskActionRequest req) {
        return service.startTask(incidentKey, taskKey, actor, req);
    }

    /**
     * 批量派工：先校验全部任务最终位置、资源依赖与豁免，任一缺失 422 并整体回滚。
     */
    @PostMapping("/{incidentKey}/tasks/dispatch")
    public DispatchView dispatch(@PathVariable String incidentKey,
                                 @RequestHeader("X-Actor-Id") String actor,
                                 @RequestBody DispatchRequest req) {
        return service.dispatch(incidentKey, actor, req);
    }

    /**
     * 撤离登记（仅进行中的命中任务；登记后转 EVACUATED 终态，不可完成）。
     */
    @PostMapping("/{incidentKey}/tasks/{taskKey}/evacuate")
    public TaskView evacuate(@PathVariable String incidentKey, @PathVariable String taskKey,
                             @RequestHeader("X-Actor-Id") String actor,
                             @RequestBody TaskActionRequest req) {
        return service.evacuate(incidentKey, taskKey, actor, req);
    }
}
