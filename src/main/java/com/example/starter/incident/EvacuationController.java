package com.example.starter.incident;

import com.example.starter.incident.dto.Requests.ExemptionGrantRequest;
import com.example.starter.incident.dto.Requests.ZoneRegisterRequest;
import com.example.starter.incident.dto.Responses.ExemptionListView;
import com.example.starter.incident.dto.Responses.ExemptionView;
import com.example.starter.incident.dto.Responses.TaskBlockStatusListView;
import com.example.starter.incident.dto.Responses.ZoneListView;
import com.example.starter.incident.dto.Responses.ZoneView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 事件疏散区域、撤离豁免与任务阻断查询 REST API。写操作要求 X-Actor-Id 请求头。
 */
@RestController
@RequestMapping("/api/incidents")
public class EvacuationController {

    private final EvacuationService evacuation;

    public EvacuationController(EvacuationService evacuation) {
        this.evacuation = evacuation;
    }

    /**
     * 登记疏散区域（仅当前指挥人；OPEN 事件；窗口 UTC 左闭右开；同等级窗口网格不可重叠）。
     */
    @PostMapping("/{incidentKey}/zones")
    public ZoneView registerZone(@PathVariable String incidentKey,
                                 @RequestHeader("X-Actor-Id") String actor,
                                 @RequestBody ZoneRegisterRequest req) {
        return evacuation.registerZone(incidentKey, actor, req);
    }

    /**
     * 显式结束裁决：推动到期区域结束并恢复阻断任务（幂等；commandKey 由请求参数提供）。
     */
    @PostMapping("/{incidentKey}/zones/end")
    public ZoneListView endZones(@PathVariable String incidentKey,
                                 @RequestParam("commandKey") String commandKey) {
        return evacuation.endZones(incidentKey, commandKey);
    }

    /**
     * 查询事件全部疏散区域（effective 按查询时刻窗口计算）。
     */
    @GetMapping("/{incidentKey}/zones")
    public ZoneListView listZones(@PathVariable String incidentKey) {
        return evacuation.listZones(incidentKey);
    }

    /**
     * 授予某区域当前版本的撤离豁免（仅当前指挥人；任务网格须在区域内）。
     */
    @PostMapping("/{incidentKey}/zones/{zoneKey}/exemptions")
    public ExemptionView grantExemption(@PathVariable String incidentKey,
                                        @PathVariable String zoneKey,
                                        @RequestHeader("X-Actor-Id") String actor,
                                        @RequestBody ExemptionGrantRequest req) {
        return evacuation.grantExemption(incidentKey, zoneKey, actor, req);
    }

    /**
     * 查询事件全部撤离豁免（含区域版本）。
     */
    @GetMapping("/{incidentKey}/exemptions")
    public ExemptionListView listExemptions(@PathVariable String incidentKey) {
        return evacuation.listExemptions(incidentKey);
    }

    /**
     * 查询任务阻断与豁免情况（逐任务返回命中缺豁免区域及已持豁免）。
     */
    @GetMapping("/{incidentKey}/task-block-status")
    public TaskBlockStatusListView taskBlockStatus(@PathVariable String incidentKey) {
        return evacuation.taskBlockStatus(incidentKey);
    }
}
