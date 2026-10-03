package com.example.starter.web;

import com.example.starter.service.QueryService;
import java.time.Instant;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 查询入口：站点时隙时间线。
 */
@RestController
@RequestMapping("/api/stations")
public class QueryController {

    private final QueryService queryService;

    public QueryController(QueryService queryService) {
        this.queryService = queryService;
    }

    /** 查询站点在指定 UTC 范围内的时隙时间线。 */
    @GetMapping("/{stationCode}/timeline")
    public List<Dtos.AllocationResponse> timeline(
            @PathVariable String stationCode,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return queryService.timeline(stationCode, from, to).stream()
                .map(Dtos.AllocationResponse::from)
                .toList();
    }
}