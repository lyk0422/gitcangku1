package com.example.starter.web;

import com.example.starter.idempotency.IdempotentOutcome;
import com.example.starter.service.SampleService;
import com.example.starter.web.dto.Requests.CollectSampleRequest;
import com.example.starter.web.dto.Responses.SampleResponse;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 样本采集与查询入口。
 */
@RestController
@RequestMapping("/api/samples")
public class SampleController {

    private final SampleService sampleService;

    public SampleController(SampleService sampleService) {
        this.sampleService = sampleService;
    }

    /**
     * 采集样本（幂等）：同 requestId 同参重放返回首次响应，异参返回 409。
     */
    @PostMapping
    public ResponseEntity<String> collect(@Valid @RequestBody CollectSampleRequest req) {
        IdempotentOutcome outcome = sampleService.collect(req);
        return ResponseEntity.status(outcome.httpStatus())
                .contentType(MediaType.APPLICATION_JSON).body(outcome.body());
    }

    /**
     * 查询样本：展示层位、坐标格、所在包版本与每次封签状态。
     */
    @GetMapping("/{sampleNo}")
    public SampleResponse get(@PathVariable String sampleNo) {
        return sampleService.getBySampleNo(sampleNo);
    }
}