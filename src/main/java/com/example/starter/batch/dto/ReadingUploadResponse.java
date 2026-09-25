package com.example.starter.batch.dto;

import com.example.starter.batch.SegmentStatus;

/**
 * 读数上传结果：读数落库后回传段状态与批次温控门禁。
 * 越界或间隔超 30 分钟时段转 EXCURSION、批次 temperatureHold=true，但读数本身仍原样保留。
 */
public record ReadingUploadResponse(
        String segmentKey,
        String batchKey,
        ReadingResponse reading,
        SegmentStatus segmentStatus,
        boolean temperatureHold
) {
}
