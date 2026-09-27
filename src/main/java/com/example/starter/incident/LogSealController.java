package com.example.starter.incident;

import java.util.List;

import com.example.starter.incident.dto.Requests.CorrectionRequest;
import com.example.starter.incident.dto.Requests.SealRequest;
import com.example.starter.incident.dto.Responses.CorrectionView;
import com.example.starter.incident.dto.Responses.SealDiagnosticsView;
import com.example.starter.incident.dto.Responses.SealListView;
import com.example.starter.incident.dto.Responses.SealView;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 事件指挥日志封存 REST API。
 * 写操作要求 X-Actor-Id：封存须为当前指挥人；更正附录在事件关闭后仍允许追加。
 * 时间均为 UTC，封存区间左闭右开，版本事件内从 1 递增且不可变。
 */
@RestController
@RequestMapping("/api/incidents/{incidentKey}/log-seals")
public class LogSealController {

    private final LogSealService service;

    public LogSealController(LogSealService service) {
        this.service = service;
    }

    /**
     * 新增封存包：对未关闭事件按截至时刻固化记录。
     * 409 同刻写入冲突/版本并发冲突；422 截至时刻越界或包内依赖图违规。
     */
    @PostMapping
    public ResponseEntity<SealView> seal(@PathVariable String incidentKey,
                                         @RequestHeader("X-Actor-Id") String actor,
                                         @RequestBody SealRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.seal(incidentKey, actor, req));
    }

    /**
     * 查询事件封存包清单（元数据、最新版本号）。只读。
     */
    @GetMapping
    public SealListView listSeals(@PathVariable String incidentKey) {
        return service.listSeals(incidentKey);
    }

    /**
     * 查询单包明细：按 seqNo 升序的条目（含记录版本与依赖边）及该包更正附录。只读。
     */
    @GetMapping("/{version}")
    public SealView getSeal(@PathVariable String incidentKey, @PathVariable int version) {
        return service.getSeal(incidentKey, version);
    }

    /**
     * 包诊断：重算摘要并核对数量、排序、依赖边解析情况。只读。
     */
    @GetMapping("/{version}/diagnostics")
    public SealDiagnosticsView diagnostics(@PathVariable String incidentKey,
                                           @PathVariable int version) {
        return service.diagnostics(incidentKey, version);
    }

    /**
     * 查询事件全部更正附录（按追加顺序）。只读。
     */
    @GetMapping("/corrections")
    public List<CorrectionView> listCorrections(@PathVariable String incidentKey) {
        return service.listCorrections(incidentKey);
    }

    /**
     * 追加更正附录：引用原包版本与原记录标识，保留原值、新值、理由与操作人；
     * 原包不更新。已关闭事件允许追加。
     */
    @PostMapping("/corrections")
    public ResponseEntity<CorrectionView> appendCorrection(@PathVariable String incidentKey,
                                                           @RequestHeader("X-Actor-Id") String actor,
                                                           @RequestBody CorrectionRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.appendCorrection(incidentKey, actor, req));
    }
}
