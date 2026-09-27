package com.example.starter.evidence;

import com.example.starter.evidence.dto.PackageFailureView;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 披露包异常映射：{@link PackageReplayException} 比通用 {@link com.example.starter.error.ApiException}
 * 处理更具体，优先返回携带逐件诊断的结构化 422 响应体。
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DisclosurePackageExceptionHandler {

    @ExceptionHandler(PackageReplayException.class)
    public ResponseEntity<PackageFailureView> handleReplayFailure(PackageReplayException ex) {
        return ResponseEntity.unprocessableEntity().body(ex.failureView());
    }
}
