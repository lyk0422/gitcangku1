package com.example.starter.firmware.error;

import com.example.starter.firmware.api.Rejection;

import java.util.List;

/**
 * 批量启动校验失败：携带每台设备的拒绝原因，整体 422，不创建任何任务。
 */
public class BatchStartRejectedException extends RuntimeException {

    private final List<Rejection> rejections;

    public BatchStartRejectedException(List<Rejection> rejections) {
        super("批量启动校验失败，未创建任何任务");
        this.rejections = List.copyOf(rejections);
    }

    public List<Rejection> rejections() {
        return rejections;
    }
}
