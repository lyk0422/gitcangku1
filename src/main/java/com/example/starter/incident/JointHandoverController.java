package com.example.starter.incident;

import java.util.List;

import com.example.starter.incident.dto.Requests.HandoverAcceptRequest;
import com.example.starter.incident.dto.Requests.HandoverFreezeRequest;
import com.example.starter.incident.dto.Responses.HandoverSnapshotView;
import com.example.starter.incident.dto.Responses.HandoverView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 联合指挥交接 REST API。冻结与接受均要求 X-Actor-Id 请求头标识操作人，
 * 携带 commandKey 实现按操作、操作者、结构化参数去重。
 */
@RestController
@RequestMapping("/api/handovers")
public class JointHandoverController {

    private final JointHandoverService service;

    public JointHandoverController(JointHandoverService service) {
        this.service = service;
    }

    /**
     * 预览冻结：当前指挥人提交 2~20 个未解决事件，系统校验恰好覆盖依赖闭包后
     * 冻结每事件/OPEN 任务/未确认升级摘要，返回 handoverVersion 与完整摘要。
     */
    @PostMapping
    public HandoverView freeze(@RequestHeader("X-Actor-Id") String actor,
                               @RequestBody HandoverFreezeRequest req) {
        return service.freeze(actor, req);
    }

    /**
     * 接受联合交接：仅指定接收人，回传完整摘要与 expectedHandoverVersion；
     * 成功后同事务切换全部事件指挥人并保存不可变闭包快照。
     */
    @PostMapping("/{handoverKey}/accept")
    public HandoverView accept(@PathVariable String handoverKey,
                               @RequestHeader("X-Actor-Id") String actor,
                               @RequestBody HandoverAcceptRequest req) {
        // handoverKey 已包含在 summary 定位的交接单内，路径参数仅用于路由可读性。
        HandoverView view = service.accept(actor, req);
        if (!view.handoverKey().equals(handoverKey)) {
            throw ApiException.badRequest("路径 handoverKey 与交接单不一致");
        }
        return view;
    }

    /** 查询交接单当前视图（含闭包与冻结摘要），只读不写。 */
    @GetMapping("/{handoverKey}")
    public HandoverView get(@PathVariable String handoverKey) {
        return service.get(handoverKey);
    }

    /** 查询接受成功后保存的不可变闭包快照。 */
    @GetMapping("/{handoverKey}/snapshot")
    public HandoverSnapshotView snapshot(@PathVariable String handoverKey) {
        return service.snapshot(handoverKey);
    }

    /** 查询涉及某事件的全部联合交接历史（含 PENDING 与 ACCEPTED），只读不写。 */
    @GetMapping("/by-incident/{incidentKey}")
    public List<HandoverView> listByIncident(@PathVariable String incidentKey) {
        return service.listByIncident(incidentKey);
    }
}
