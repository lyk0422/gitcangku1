package com.example.starter.consent;

import java.time.Instant;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.consent.dto.CrossQueryRequest;
import com.example.starter.consent.dto.CrossQueryResponse;
import com.example.starter.consent.dto.SnapshotSummary;

import jakarta.validation.Valid;

/**
 * 跨域查询 API：生成冻结的合规快照，并提供快照明细与历史查询。查询类端点只读。
 */
@Validated
@RestController
@RequestMapping("/api/v1/queries")
public class QueryController {

    private final QueryService queryService;

    public QueryController(QueryService queryService) {
        this.queryService = queryService;
    }

    @PostMapping
    public CrossQueryResponse query(@Valid @RequestBody CrossQueryRequest request) {
        return queryService.query(request);
    }

    @GetMapping("/{snapshotId}")
    public CrossQueryResponse snapshotDetail(@PathVariable String snapshotId) {
        return queryService.snapshotDetail(snapshotId);
    }

    /**
     * 快照历史：from/to 均为 UTC ISO-8601 时间，区间左闭右开 [from, to)；缺省覆盖全部。
     */
    @GetMapping
    public List<SnapshotSummary> snapshotHistory(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return queryService.snapshotHistory(
                from == null ? Instant.EPOCH : from,
                to == null ? Instant.parse("9999-12-31T23:59:59Z") : to);
    }
}
