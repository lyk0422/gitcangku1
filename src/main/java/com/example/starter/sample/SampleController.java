package com.example.starter.sample;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.common.StoredResponse;
import com.example.starter.query.QueryDtos.SampleContextResponse;
import com.example.starter.query.QueryService;
import com.example.starter.sample.SampleDtos.CollectRequest;

/**
 * 样本入口：采集（幂等）与背景查询。
 */
@RestController
@RequestMapping("/api/samples")
public class SampleController {

    private final SampleService sampleService;
    private final QueryService queryService;

    public SampleController(SampleService sampleService, QueryService queryService) {
        this.sampleService = sampleService;
        this.queryService = queryService;
    }

    @PostMapping("/collect")
    public ResponseEntity<String> collect(@Valid @RequestBody CollectRequest req) {
        StoredResponse resp = sampleService.collect(req);
        return resp.toEntity();
    }

    @GetMapping("/{sampleNo}/context")
    public SampleContextResponse context(@PathVariable String sampleNo) {
        return queryService.sampleContext(sampleNo);
    }
}