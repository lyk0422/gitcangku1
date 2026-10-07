package com.example.starter.consent;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.consent.dto.ErasureExecuteRequest;
import com.example.starter.consent.dto.ErasureTaskResponse;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * 旧代载荷清除 API：分批执行物理清除与查看无载荷进度。
 * 仅本地角色 ERASER（请求头 X-Role: ERASER）可调用，其他角色一律 403。
 */
@Validated
@RestController
@RequestMapping("/api/v1/erasure")
public class ErasureController {

    static final String ROLE_ERASER = "ERASER";
    static final String CODE_ROLE_FORBIDDEN = "ROLE_FORBIDDEN";

    private final ErasureService erasureService;

    public ErasureController(ErasureService erasureService) {
        this.erasureService = erasureService;
    }

    @PostMapping("/executions")
    public ErasureTaskResponse execute(@RequestHeader(value = "X-Role", required = false) String role,
                                       @Valid @RequestBody ErasureExecuteRequest request) {
        requireEraser(role);
        return erasureService.execute(request);
    }

    @GetMapping("/tasks")
    public ErasureTaskResponse progress(@RequestHeader(value = "X-Role", required = false) String role,
                                        @RequestParam @NotBlank String subjectKey,
                                        @RequestParam Purpose purpose,
                                        @RequestParam @Min(1) int epoch) {
        requireEraser(role);
        return erasureService.progress(subjectKey, purpose, epoch);
    }

    private void requireEraser(String role) {
        if (!ROLE_ERASER.equals(role)) {
            throw ApiException.forbidden(CODE_ROLE_FORBIDDEN, "仅 ERASER 角色可执行或查看清除进度");
        }
    }
}
