package com.example.starter.incident;

import com.example.starter.incident.dto.Requests.SealAppendixRequest;
import com.example.starter.incident.dto.Requests.SealCreateRequest;
import com.example.starter.incident.dto.Responses.SealAppendixView;
import com.example.starter.incident.dto.Responses.SealDiagnosticsView;
import com.example.starter.incident.dto.Responses.SealHistoryView;
import com.example.starter.incident.dto.Responses.SealPackageDetailView;
import com.example.starter.incident.dto.Responses.SealPackageView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 事件指挥日志封存 REST API。封存创建要求 X-Actor-Id 为当前指挥人；
 * 更正附录要求 X-Actor-Id 标识操作人（已关闭事件仍可追加）；查询均为只读。
 */
@RestController
@RequestMapping("/api/incidents/{incidentKey}/seals")
public class SealController {

    private final SealService service;

    public SealController(SealService service) {
        this.service = service;
    }

    /**
     * 创建封存包：按截至 UTC 时刻封存指令、交接、状态流转、任务状态与依赖图，
     * 生成递增版本的不可变日志包。
     */
    @PostMapping
    public SealPackageView seal(@PathVariable String incidentKey,
                                @RequestHeader("X-Actor-Id") String actor,
                                @RequestBody SealCreateRequest req) {
        return service.seal(incidentKey, actor, req);
    }

    /**
     * 查询封存历史：版本递增的包摘要列表与全部更正附录（只读）。
     */
    @GetMapping
    public SealHistoryView history(@PathVariable String incidentKey) {
        return service.sealHistory(incidentKey);
    }

    /**
     * 封存诊断：当前可封存窗口与待封存记录数（只读）。
     */
    @GetMapping("/diagnostics")
    public SealDiagnosticsView diagnostics(@PathVariable String incidentKey) {
        return service.sealDiagnostics(incidentKey);
    }

    /**
     * 查询指定版本封存包明细：条目与依赖边（只读）。
     */
    @GetMapping("/{version}")
    public SealPackageDetailView detail(@PathVariable String incidentKey,
                                        @PathVariable int version) {
        return service.sealDetail(incidentKey, version);
    }

    /**
     * 追加更正附录：引用原包版本与原记录标识，保留原值、新值、理由与操作人；
     * 不修改原包内容。
     */
    @PostMapping("/appendices")
    public SealAppendixView appendAppendix(@PathVariable String incidentKey,
                                           @RequestHeader("X-Actor-Id") String actor,
                                           @RequestBody SealAppendixRequest req) {
        return service.appendAppendix(incidentKey, actor, req);
    }
}
