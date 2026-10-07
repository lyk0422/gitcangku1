package com.example.starter.consent;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.consent.dto.ErasureExecuteRequest;
import com.example.starter.consent.dto.ErasureExecuteResponse;
import com.example.starter.consent.dto.ErasureProgressResponse;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 旧代载荷清除 API：分批执行物理清除与查看无载荷进度，仅本地角色 ERASER 可用。
 */
@Validated
@RestController
@RequestMapping("/api/v1/erasure-tasks")
public class ErasureController {

    private final ErasureService erasureService;

    public ErasureController(ErasureService erasureService) {
        this.erasureService = erasureService;
    }

    @PostMapping("/executions")
    public ErasureExecuteResponse execute(@Valid @RequestBody ErasureExecuteRequest request) {
        return erasureService.execute(request);
    }

    @GetMapping("/progress")
    public ErasureProgressResponse progress(@RequestParam @NotBlank String subjectKey,
                                            @RequestParam @NotNull Purpose purpose,
                                            @RequestParam @Min(1) int epoch,
                                            @RequestParam(required = false) String role) {
        return erasureService.progress(subjectKey, purpose, epoch, role);
    }
}
