package com.example.starter.playout.api;

import com.example.starter.playout.api.Dtos.SimulcastRejection;

import java.util.List;

/**
 * 联播创建整组校验失败异常：携带逐频道原因，由全局异常处理器转换为 422 响应，
 * 不写入任何锁定记录。
 */
public class SimulcastValidationException extends RuntimeException {

    private final List<SimulcastRejection> rejections;

    public SimulcastValidationException(List<SimulcastRejection> rejections) {
        super("联播校验未通过，涉及 " + rejections.size() + " 个频道项");
        this.rejections = List.copyOf(rejections);
    }

    public List<SimulcastRejection> rejections() {
        return rejections;
    }
}
