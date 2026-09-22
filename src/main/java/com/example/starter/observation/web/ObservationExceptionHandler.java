package com.example.starter.observation.web;

import com.example.starter.observation.dto.ConflictResponse;
import com.example.starter.observation.exception.ConflictException;
import com.example.starter.observation.exception.ObservationGoneException;
import com.example.starter.observation.exception.ObservationNotFoundException;
import com.example.starter.observation.exception.VersionMismatchException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 观测接口异常到 HTTP 状态码的映射：
 * 404 基线/记录/版本不存在，409 合并冲突或版本不匹配，410 墓碑拒绝写。
 */
@RestControllerAdvice
public class ObservationExceptionHandler {

    @ExceptionHandler(ObservationNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(ObservationNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(ObservationGoneException.class)
    public ResponseEntity<Map<String, Object>> handleGone(ObservationGoneException ex) {
        return ResponseEntity.status(HttpStatus.GONE)
                .body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<Object> handleConflict(ConflictException ex) {
        if (ex.getConflictFields().isEmpty()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", ex.getMessage()));
        }
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ConflictResponse(ex.getCurrentVersion(), ex.getConflictFields()));
    }

    @ExceptionHandler(VersionMismatchException.class)
    public ResponseEntity<ConflictResponse> handleVersionMismatch(VersionMismatchException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ConflictResponse(ex.getCurrentVersion(), java.util.List.of()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "请求参数不合法"));
    }
}
