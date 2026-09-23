package com.example.starter.incident;

import com.example.starter.incident.dto.Requests.HandoverAcceptRequest;
import com.example.starter.incident.dto.Requests.HandoverInitiateRequest;
import com.example.starter.incident.dto.Responses.HandoverDetailView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 联合指挥交接 REST API。写操作要求 X-Actor-Id 请求头标识操作人；
 * 查询为只读，不写数据。
 */
@RestController
@RequestMapping("/api/handovers")
public class HandoverController {

    private final HandoverService service;

    public HandoverController(HandoverService service) {
        this.service = service;
    }

    /**
     * 发起联合交接：提交 2~20 个未解决事件键，必须恰好覆盖依赖闭包；
     * 返回冻结当前状态的完整摘要与 handoverVersion。
     */
    @PostMapping
    public HandoverDetailView initiate(@RequestHeader("X-Actor-Id") String actor,
                                       @RequestBody HandoverInitiateRequest req) {
        return service.initiate(actor, req);
    }

    /**
     * 查询联合交接详情：PENDING 为实时预览，ACCEPTED 为不可变快照（只读）。
     */
    @GetMapping("/{handoverKey}")
    public HandoverDetailView get(@PathVariable String handoverKey) {
        return service.getHandover(handoverKey);
    }

    /**
     * 接受联合交接：仅指定接收人，提交完整摘要与 expectedHandoverVersion；
     * 任一事件、任务、依赖或升级变化均 409。
     */
    @PostMapping("/{handoverKey}/accept")
    public HandoverDetailView accept(@PathVariable String handoverKey,
                                     @RequestHeader("X-Actor-Id") String actor,
                                     @RequestBody HandoverAcceptRequest req) {
        return service.accept(handoverKey, actor, req);
    }
}
