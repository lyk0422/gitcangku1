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
import jakarta.validation.constraints.NotNull;

/**
 * 旧代载荷清除 API：仅本地角色 ERASER（请求头 X-Actor-Role）可执行清除批次与查看无载荷进度。
 */
@Validated
@RestController
@RequestMapping("/api/v1")
public class ErasureController {

    private final ErasureService erasureService;

    public ErasureController(ErasureService erasureService) {
        this.erasureService = erasureService;
    }

    @PostMapping("/erasure-executions")
    public ErasureTaskResponse execute(
            @RequestHeader(value = "X-Actor-Role", required = false) String role,
            @Valid @RequestBody ErasureExecuteRequest request) {
        return erasureService.execute(role, request);
    }

    @GetMapping("/erasure-tasks")
    public ErasureTaskResponse progress(
            @RequestHeader(value = "X-Actor-Role", required = false) String role,
            @RequestParam @NotBlank String subjectKey,
            @RequestParam @NotNull Purpose purpose,
            @RequestParam @NotNull @Min(1) Integer epoch) {
        return erasureService.progress(role, subjectKey, purpose, epoch);
    }
}
