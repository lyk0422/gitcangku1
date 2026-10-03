package com.example.starter.web;

import com.example.starter.service.DischargeService;
import com.example.starter.service.view.BatchDetailView;
import com.example.starter.service.view.CellView;
import com.example.starter.service.view.HandoverView;
import com.example.starter.service.view.ReadingView;
import com.example.starter.web.dto.CreateBatchRequest;
import com.example.starter.web.dto.EnrollCellRequest;
import com.example.starter.web.dto.HandoverRequest;
import com.example.starter.web.dto.IsolateCellRequest;
import com.example.starter.web.dto.RecordReadingRequest;
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
 * 电芯放电交接 REST 入口。
 */
@RestController
@RequestMapping("/api")
public class DischargeController {

    private final DischargeService service;

    public DischargeController(DischargeService service) {
        this.service = service;
    }

    /**
     * 新建批次。
     */
    @PostMapping("/batches")
    @ResponseStatus(HttpStatus.CREATED)
    public BatchDetailView createBatch(@Valid @RequestBody CreateBatchRequest request) {
        return service.createBatch(request.batchNo());
    }

    /**
     * 电芯入批，记录初始电压。
     */
    @PostMapping("/batches/{batchNo}/cells")
    @ResponseStatus(HttpStatus.CREATED)
    public CellView enrollCell(@PathVariable String batchNo,
                               @Valid @RequestBody EnrollCellRequest request) {
        return service.enrollCell(batchNo, request.requestId(), request.cellCode(),
                request.initialVoltage());
    }

    /**
     * 记录放电读数。
     */
    @PostMapping("/batches/{batchNo}/readings")
    @ResponseStatus(HttpStatus.CREATED)
    public ReadingView recordReading(@PathVariable String batchNo,
                                     @Valid @RequestBody RecordReadingRequest request) {
        return service.recordReading(batchNo, request.requestId(), request.cellCode(),
                request.voltage(), request.measuredAt());
    }

    /**
     * 作废读数（保留历史）。
     */
    @PostMapping("/readings/{readingId}/void")
    public ReadingView voidReading(@PathVariable long readingId) {
        return service.voidReading(readingId);
    }

    /**
     * 隔离异常电芯。
     */
    @PostMapping("/batches/{batchNo}/isolations")
    public CellView isolateCell(@PathVariable String batchNo,
                                @Valid @RequestBody IsolateCellRequest request) {
        return service.isolateCell(batchNo, request.cellCode(), request.reason());
    }

    /**
     * 批次交接签收。
     */
    @PostMapping("/batches/{batchNo}/handover")
    public HandoverView handover(@PathVariable String batchNo,
                                 @Valid @RequestBody HandoverRequest request) {
        return service.handover(batchNo, request.requestId(), request.fromParty(), request.toParty());
    }

    /**
     * 查询批次明细：读数链、隔离原因与交接快照。
     */
    @GetMapping("/batches/{batchNo}")
    public BatchDetailView getBatch(@PathVariable String batchNo) {
        return service.getBatch(batchNo);
    }
}