package com.example.starter.firmware.error;

import com.example.starter.firmware.api.ModelCompatSummary;
import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * 发布启动预检失败（422）：全部候选设备均不兼容，携带按硬件型号汇总。
 */
public class PrecheckException extends RuntimeException {

    private final String code;
    private final transient List<ModelCompatSummary> models;

    public PrecheckException(String code, String message, List<ModelCompatSummary> models) {
        super(message);
        this.code = code;
        this.models = models;
    }

    public HttpStatus status() {
        return HttpStatus.UNPROCESSABLE_ENTITY;
    }

    public String code() {
        return code;
    }

    public List<ModelCompatSummary> models() {
        return models;
    }
}
