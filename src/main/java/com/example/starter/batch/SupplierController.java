package com.example.starter.batch;

import com.example.starter.batch.dto.SetThresholdRequest;
import com.example.starter.batch.dto.SupplierScoreHistoryResponse;
import com.example.starter.batch.dto.SupplierScoreResponse;
import com.example.starter.batch.dto.ThresholdResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 供应商评分卡与准入门槛接口。
 */
@RestController
@RequestMapping("/api/suppliers")
public class SupplierController {

    private final SupplierService service;

    public SupplierController(SupplierService service) {
        this.service = service;
    }

    /**
     * 设置供应商准入门槛（-100～100），立即生效于后续批次创建；requestId 幂等。
     */
    @PutMapping("/{supplierId}/threshold")
    public ResponseEntity<String> setThreshold(@PathVariable String supplierId,
                                               @Valid @RequestBody SetThresholdRequest request) {
        StoredResponse response = service.setThreshold(supplierId, request);
        return ResponseEntity.status(response.status())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(response.body());
    }

    /**
     * 供应商当前门槛配置；未设置时 threshold 为 null。
     */
    @GetMapping("/{supplierId}/threshold")
    public ThresholdResponse threshold(@PathVariable String supplierId) {
        return service.thresholdConfig(supplierId);
    }

    /**
     * 供应商当前滑动评分明细：评分、窗口大小、窗口批次清单（按批次标识排序）及各自贡献。
     */
    @GetMapping("/{supplierId}/score")
    public SupplierScoreResponse score(@PathVariable String supplierId) {
        return service.scoreDetail(supplierId);
    }

    /**
     * 供应商历史评分轨迹：按批次创建时刻升序的滑动评分快照。
     */
    @GetMapping("/{supplierId}/score-history")
    public SupplierScoreHistoryResponse scoreHistory(@PathVariable String supplierId) {
        return service.scoreHistory(supplierId);
    }
}
