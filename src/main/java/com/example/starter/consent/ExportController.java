package com.example.starter.consent;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.consent.dto.ExportListResponse;
import com.example.starter.consent.dto.ExportRequest;
import com.example.starter.consent.dto.ExportResponse;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/**
 * 导出快照 API：生成不可变快照、按 exportKey 读取明细、按主体列出快照。
 */
@Validated
@RestController
@RequestMapping("/api/v1/exports")
public class ExportController {

    private final ExportService exportService;

    public ExportController(ExportService exportService) {
        this.exportService = exportService;
    }

    @PostMapping
    public ExportResponse create(@Valid @RequestBody ExportRequest request) {
        return exportService.export(request);
    }

    @GetMapping("/{exportKey}")
    public ExportResponse detail(@PathVariable String exportKey) {
        return exportService.detail(exportKey);
    }

    @GetMapping
    public ExportListResponse listBySubject(@RequestParam @NotBlank String subjectKey) {
        return exportService.listBySubject(subjectKey);
    }
}
