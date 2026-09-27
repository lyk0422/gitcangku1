package com.example.starter.calibration.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.calibration.api.dto.BatchHistoryResponse;
import com.example.starter.calibration.api.dto.BatchReleaseResponse;
import com.example.starter.calibration.api.dto.BatchResponse;
import com.example.starter.calibration.api.dto.CreateBatchRequest;
import com.example.starter.calibration.api.dto.OutlierResponse;
import com.example.starter.calibration.api.dto.ReviseBatchRequest;
import com.example.starter.calibration.api.dto.SubmitReadingRequest;
import com.example.starter.calibration.service.BatchReleaseService;
import com.example.starter.calibration.service.BatchService;
import com.example.starter.calibration.service.IdempotentResponse;
import com.example.starter.calibration.service.ReadingService;

/**
 * 测量批次接口：创建、读数提交（幂等）、测量修订、放行门禁、明细与历史诊断查询。
 */
@RestController
@RequestMapping("/api/batches")
public class BatchController {

    private final BatchService batches;
    private final ReadingService readings;
    private final BatchReleaseService releases;

    public BatchController(BatchService batches, ReadingService readings, BatchReleaseService releases) {
        this.batches = batches;
        this.readings = readings;
        this.releases = releases;
    }

    /**
     * 创建批次：201；参数非法 400；批次键重复 409。
     */
    @PostMapping
    public ResponseEntity<BatchResponse> create(@RequestBody CreateBatchRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(batches.create(request));
    }

    /**
     * 批次明细：当前修订配置、最新统计与待裁决离群数；不存在 404。
     */
    @GetMapping("/{batchKey}")
    public BatchResponse detail(@PathVariable String batchKey) {
        return batches.detail(batchKey);
    }

    /**
     * 提交读数：201；同 requestId 同参重放首次结果，异参 409；
     * 批次已放行 409；无匹配有效证书 422；任一离群读数自动生成 OUTLIER 记录并阻断放行。
     */
    @PostMapping("/{batchKey}/readings")
    public ResponseEntity<String> submitReading(@PathVariable String batchKey,
                                                @RequestBody SubmitReadingRequest request) {
        IdempotentResponse response = readings.submit(batchKey, request);
        return ResponseEntity.status(response.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.body());
    }

    /**
     * 测量修订：201（新修订版本）；参数非法 400；不存在 404。
     * 已放行批次的放行快照保持不变，新增离群事实进入后续修订版本。
     */
    @PostMapping("/{batchKey}/revisions")
    public ResponseEntity<BatchResponse> revise(@PathVariable String batchKey,
                                                @RequestBody ReviseBatchRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(batches.revise(batchKey, request));
    }

    /**
     * 放行当前修订版本：200；存在未裁决离群值 422 并列出明细；
     * 证书已撤销 422；当前修订已放行 409。放行人通过 X-Actor-Id 请求头提供。
     */
    @PostMapping("/{batchKey}/release")
    public BatchReleaseResponse release(@PathVariable String batchKey,
                                        @RequestHeader("X-Actor-Id") String actor) {
        return releases.release(batchKey, actor);
    }

    /**
     * 当前修订版本的离群记录列表；不存在 404。只读。
     */
    @GetMapping("/{batchKey}/outliers")
    public List<OutlierResponse> outliers(@PathVariable String batchKey) {
        return batches.outliersOf(batchKey);
    }

    /**
     * 历史与诊断：全部修订版本的离群记录、统计重算历史与放行快照；不存在 404。只读。
     */
    @GetMapping("/{batchKey}/history")
    public BatchHistoryResponse history(@PathVariable String batchKey) {
        return batches.history(batchKey);
    }
}
