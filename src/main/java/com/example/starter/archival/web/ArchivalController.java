package com.example.starter.archival.web;

import com.example.starter.archival.service.ArchivalService;
import com.example.starter.archival.web.dto.BatchResponse;
import com.example.starter.archival.web.dto.CreateBatchRequest;
import com.example.starter.archival.web.dto.EnrollRequest;
import com.example.starter.archival.web.dto.EnrollResult;
import com.example.starter.archival.web.dto.PageHistoryResponse;
import com.example.starter.archival.web.dto.PageResponse;
import com.example.starter.archival.web.dto.ReadingRequest;
import com.example.starter.archival.web.dto.ReadingResponse;
import com.example.starter.archival.web.dto.RegisterPageRequest;
import com.example.starter.archival.web.dto.RejectRequest;
import com.example.starter.archival.web.dto.RejectResult;
import com.example.starter.archival.web.dto.ReleaseRequest;
import com.example.starter.archival.web.dto.ReleaseResult;
import com.example.starter.archival.web.dto.SealRequest;
import com.example.starter.archival.web.dto.SealResult;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 档案脱酸流程接口。
 */
@RestController
@RequestMapping("/api/archival")
public class ArchivalController {

    private final ArchivalService archivalService;

    public ArchivalController(ArchivalService archivalService) {
        this.archivalService = archivalService;
    }

    /** 登记档案页。 */
    @PostMapping("/pages")
    @ResponseStatus(HttpStatus.CREATED)
    public PageResponse registerPage(@Valid @RequestBody RegisterPageRequest request) {
        return archivalService.registerPage(request.accessionNo());
    }

    /** 建脱酸批次。 */
    @PostMapping("/batches")
    @ResponseStatus(HttpStatus.CREATED)
    public BatchResponse createBatch(@Valid @RequestBody CreateBatchRequest request) {
        return archivalService.createBatch(request.batchNo());
    }

    /** 页面入组（requestId 幂等）。 */
    @PostMapping("/enrollments")
    @ResponseStatus(HttpStatus.OK)
    public EnrollResult enroll(@Valid @RequestBody EnrollRequest request) {
        return archivalService.enroll(request);
    }

    /** 追加处理读数（新轮次，不覆盖旧轮次）。 */
    @PostMapping("/readings")
    @ResponseStatus(HttpStatus.CREATED)
    public ReadingResponse recordReading(@Valid @RequestBody ReadingRequest request) {
        return archivalService.recordReading(request);
    }

    /** 质检退回（理由只追加）。 */
    @PostMapping("/rejections")
    public RejectResult reject(@Valid @RequestBody RejectRequest request) {
        return archivalService.reject(request);
    }

    /** 从未封存批次归还档案页并释放占用。 */
    @PostMapping("/releases")
    public ReleaseResult release(@Valid @RequestBody ReleaseRequest request) {
        return archivalService.release(request);
    }

    /** 整批封存（requestId 幂等）；失败时 200 返回 sealed=false 与全部问题页。 */
    @PostMapping("/seals")
    public SealResult seal(@Valid @RequestBody SealRequest request) {
        return archivalService.seal(request);
    }

    /** 查询批次状态。 */
    @GetMapping("/batches/{batchNo}")
    public BatchResponse getBatch(@PathVariable String batchNo) {
        return archivalService.getBatch(batchNo);
    }

    /** 查询档案页处理轮次、批次状态、pH 前后值和药剂来源。 */
    @GetMapping("/pages/{accessionNo}/history")
    public PageHistoryResponse getHistory(@PathVariable String accessionNo) {
        return archivalService.getHistory(accessionNo);
    }
}
