package com.example.starter.calibration.service;

import org.springframework.stereotype.Service;

import com.example.starter.calibration.api.dto.ReadingResponse;
import com.example.starter.calibration.api.dto.SubmitReadingRequest;

/**
 * 批次读数服务：提交读数、匹配证书、离群检测与批次统计重算。
 */
@Service
public class ReadingService {

    /**
     * 提交读数。计算值超出目标值±允许偏差（含端点为界）时生成 PENDING 离群记录；
     * 已放行批次须先创建后续修订版本。
     */
    public ReadingResponse submit(String batchKey, SubmitReadingRequest request) {
        throw new UnsupportedOperationException();
    }
}