package com.example.starter.incident;

import com.example.starter.incident.dto.Requests.BarrierRegisterRequest;
import com.example.starter.incident.dto.Requests.BarrierRevokeRequest;
import com.example.starter.incident.dto.Requests.BarrierUpdateRequest;
import com.example.starter.incident.dto.Requests.RiskModelSetupRequest;
import com.example.starter.incident.dto.Responses.RiskModelView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 合成残余风险模型 REST API（合成数据，不连接真实安全设备）。
 * 写操作均要求 X-Actor-Id 请求头标识操作人，且须为当前指挥人（权限先于幂等重放）。
 */
@RestController
@RequestMapping("/api/incidents")
public class IncidentRiskController {

    private final IncidentRiskService service;

    public IncidentRiskController(IncidentRiskService service) {
        this.service = service;
    }

    /**
     * 首次设置残余风险模型（R 1~1000，L 非负且小于 R，仅可设置一次）。
     */
    @PostMapping("/{incidentKey}/risk-model")
    public RiskModelView setupModel(@PathVariable String incidentKey,
                                    @RequestHeader("X-Actor-Id") String actor,
                                    @RequestBody RiskModelSetupRequest req) {
        return service.setupModel(incidentKey, actor, req);
    }

    /**
     * 查询残余风险模型视图（当前残余精确分数、可遏制判定、遏制冻结快照）。
     */
    @GetMapping("/{incidentKey}/risk-model")
    public RiskModelView getModel(@PathVariable String incidentKey) {
        return service.getModel(incidentKey);
    }

    /**
     * 登记屏障（绑定 OPEN 任务，削减率 p/q，未撤销屏障至多 5 个）。
     */
    @PostMapping("/{incidentKey}/risk-model/barriers")
    public RiskModelView registerBarrier(@PathVariable String incidentKey,
                                         @RequestHeader("X-Actor-Id") String actor,
                                         @RequestBody BarrierRegisterRequest req) {
        return service.registerBarrier(incidentKey, actor, req);
    }

    /**
     * 修改屏障（携带模型 expectedVersion，成功后版本加一；
     * 事件已 CONTAINED 且变更后残余超限则 409）。
     */
    @PostMapping("/{incidentKey}/risk-model/barriers/{barrierKey}")
    public RiskModelView updateBarrier(@PathVariable String incidentKey,
                                       @PathVariable String barrierKey,
                                       @RequestHeader("X-Actor-Id") String actor,
                                       @RequestBody BarrierUpdateRequest req) {
        return service.updateBarrier(incidentKey, barrierKey, actor, req);
    }

    /**
     * 撤销屏障（携带模型 expectedVersion，成功后版本加一；
     * 事件已 CONTAINED 且撤销后残余超限则 409）。
     */
    @PostMapping("/{incidentKey}/risk-model/barriers/{barrierKey}/revoke")
    public RiskModelView revokeBarrier(@PathVariable String incidentKey,
                                       @PathVariable String barrierKey,
                                       @RequestHeader("X-Actor-Id") String actor,
                                       @RequestBody BarrierRevokeRequest req) {
        return service.revokeBarrier(incidentKey, barrierKey, actor, req);
    }
}
