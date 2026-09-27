package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.PackageFailureView;
import org.springframework.http.HttpStatus;

/**
 * 披露包整包校验失败异常：HTTP 422，携带逐件可区分诊断。
 * 抛出后整个建包/增删事务回滚，不写入任何包版本或命令日志（失败不占键、不留半成品）。
 */
public class PackageReplayException extends ApiException {

    private final transient PackageFailureView failureView;

    public PackageReplayException(PackageFailureView failureView) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, failureView.message());
        this.failureView = failureView;
    }

    /**
     * 结构化失败响应体（逐件诊断）。
     */
    public PackageFailureView failureView() {
        return failureView;
    }
}
