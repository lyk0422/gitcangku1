package com.example.starter.calibration.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.example.starter.calibration.api.dto.BatchDetailResponse;
import com.example.starter.calibration.api.dto.BatchEventResponse;
import com.example.starter.calibration.api.dto.BatchReleaseSnapshotResponse;
import com.example.starter.calibration.api.dto.BatchResponse;
import com.example.starter.calibration.api.dto.CreateBatchRequest;
import com.example.starter.calibration.api.dto.CreateRevisionRequest;
import com.example.starter.calibration.api.dto.OutlierResponse;

/**
 * 测量批次服务：创建、明细、离群明细、事件历史、放行快照与后续测量修订。
 */
@Service
public class BatchService {

    /**
     * 创建批次。batchKey 重复返回 409；允许偏差须非负。
     */
    public BatchResponse create(CreateBatchRequest request) {
        throw new UnsupportedOperationException();
    }

    /**
     * 批次明细：当前状态、统计与全部离群记录（含复核明细）。只读，不改变状态。
     */
    public BatchDetailResponse detail(String batchKey) {
        throw new UnsupportedOperationException();
    }

    /**
     * 单条离群记录明细（含复核明细）。只读，不改变状态。
     */
    public OutlierResponse outlierDetail(String batchKey, long outlierId) {
        throw new UnsupportedOperationException();
    }

    /**
     * 批次事件历史（按提交顺序）。只读，不改变状态。
     */
    public List<BatchEventResponse> events(String batchKey) {
        throw new UnsupportedOperationException();
    }

    /**
     * 批次放行快照列表（按生成顺序）。只读，不改变状态。
     */
    public List<BatchReleaseSnapshotResponse> releases(String batchKey) {
        throw new UnsupportedOperationException();
    }

    /**
     * 创建后续测量修订：仅已放行批次可创建；修订版本递增并重新开放。
     */
    public BatchResponse createRevision(String batchKey, CreateRevisionRequest request) {
        throw new UnsupportedOperationException();
    }
}