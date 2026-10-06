package com.example.starter.incident;

import com.example.starter.incident.dto.Requests.BarrierRegisterRequest;
import com.example.starter.incident.dto.Requests.BarrierRevokeRequest;
import com.example.starter.incident.dto.Requests.BarrierUpdateRequest;
import com.example.starter.incident.dto.Requests.RiskModelInitRequest;
import com.example.starter.incident.dto.Responses.RiskModelView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 残余风险模型 REST API（合成模型，不连接真实安全设备）。
 * 写操作均要求 X-Actor-Id 请求头标识操作人且须为当前指挥人；权限校验先于 requestId 重放。
 */
@RestController
@RequestMapping("/api/incidents")
public class RiskModelController {

    private final RiskModelService service;

    public RiskModelController(RiskModelService service) {
        this.service = service;
    }

    /**
     * 初始化风险模型（仅当前指挥人、仅首次）：设置初始风险 R 与允许残余上限 L。
     */
    @PutMapping("/{incidentKey}/risk-model")
    public RiskModelView initModel(@PathVariable String incidentKey,
                                   @RequestHeader("X-Actor-Id") String actor,
                                   @RequestBody RiskModelInitRequest req) {
        return service.initModel(incidentKey, actor, req);
    }

    /**
     * 查询风险模型共享视图（当前残余精确分数、屏障状态、遏制冻结依据）。
     */
    @GetMapping("/{incidentKey}/risk-model")
    public RiskModelView getModel(@PathVariable String incidentKey) {
        return service.getView(incidentKey);
    }

    /**
     * 登记屏障（仅当前指挥人）：绑定 OPEN 任务与削减率 p/q，至多 5 个未撤销屏障。
     */
    @PostMapping("/{incidentKey}/risk-model/barriers")
    public RiskModelView registerBarrier(@PathVariable String incidentKey,
                                         @RequestHeader("X-Actor-Id") String actor,
                                         @RequestBody BarrierRegisterRequest req) {
        return service.registerBarrier(incidentKey, actor, req);
    }

    /**
     * 修改屏障削减率（仅当前指挥人，携带期望版本）。
     */
    @PostMapping("/{incidentKey}/risk-model/barriers/{barrierKey}")
    public RiskModelView updateBarrier(@PathVariable String incidentKey,
                                       @PathVariable String barrierKey,
                                       @RequestHeader("X-Actor-Id") String actor,
                                       @RequestBody BarrierUpdateRequest req) {
        return service.updateBarrier(incidentKey, barrierKey, actor, req);
    }

    /**
     * 撤销屏障（仅当前指挥人，携带期望版本）。
     */
    @PostMapping("/{incidentKey}/risk-model/barriers/{barrierKey}/revoke")
    public RiskModelView revokeBarrier(@PathVariable String incidentKey,
                                       @PathVariable String barrierKey,
                                       @RequestHeader("X-Actor-Id") String actor,
                                       @RequestBody BarrierRevokeRequest req) {
        return service.revokeBarrier(incidentKey, barrierKey, actor, req);
    }
}
