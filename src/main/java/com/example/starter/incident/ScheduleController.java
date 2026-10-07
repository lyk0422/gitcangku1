package com.example.starter.incident;

import com.example.starter.incident.dto.Requests.ScheduleProposeRequest;
import com.example.starter.incident.dto.Responses.ScheduleHistoryView;
import com.example.starter.incident.dto.Responses.ScheduleView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 整案排程提案 REST API。写操作要求 X-Actor-Id 请求头标识非空操作者，
 * 且须为当前指挥人；提案不实际执行任务，不替换原阻塞事件规则。
 */
@RestController
@RequestMapping("/api/incidents/{incidentKey}/schedules")
public class ScheduleController {

    private final ScheduleService service;

    public ScheduleController(ScheduleService service) {
        this.service = service;
    }

    /**
     * 提交整案排程提案：全部任务及约束同时可行才提交（不可行 422 不留部分排程）；
     * 携带 requestId 幂等，替换须带 expectedScheduleVersion。
     */
    @PostMapping
    public ScheduleView propose(@PathVariable String incidentKey,
                                @RequestHeader("X-Actor-Id") String actor,
                                @RequestBody ScheduleProposeRequest req) {
        return service.propose(incidentKey, actor, req);
    }

    /**
     * 查询事件排程：最新版本提案及全部历史版本（只读，不隐式写入）。
     */
    @GetMapping
    public ScheduleHistoryView list(@PathVariable String incidentKey) {
        return service.listSchedules(incidentKey);
    }
}
