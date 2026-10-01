package com.example.starter.calibration.service;

import org.springframework.stereotype.Service;

import com.example.starter.calibration.api.dto.BatchReleaseResponse;
import com.example.starter.calibration.api.dto.ReleaseBatchRequest;

/**
 * 批次放行服务：校验未裁决离群与证书有效性后原子放行，并生成不可改写的放行快照。
 */
@Service
public class BatchReleaseService {

    /**
     * 放行批次。存在未裁决离群记录或未排除读数证书已撤销时返回 422；
     * 成功后写入放行快照，后续测量修订不得改写。
     */
    public BatchReleaseResponse release(String batchKey, ReleaseBatchRequest request, String actor) {
        throw new UnsupportedOperationException();
    }
}