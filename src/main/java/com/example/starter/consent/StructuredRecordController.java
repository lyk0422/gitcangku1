package com.example.starter.consent;

import java.util.List;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.consent.dto.FieldRestrictionReplaceRequest;
import com.example.starter.consent.dto.FieldRestrictionResponse;
import com.example.starter.consent.dto.StructuredRecordReadResponse;
import com.example.starter.consent.dto.StructuredRecordWriteRequest;
import com.example.starter.consent.dto.StructuredRecordWriteResponse;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/**
 * 结构化记录与字段级限制 API：结构化写入、字段投影读取与限制清单替换。
 */
@Validated
@RestController
@RequestMapping("/api/v1")
public class StructuredRecordController {

    private final StructuredRecordService structuredRecordService;

    public StructuredRecordController(StructuredRecordService structuredRecordService) {
        this.structuredRecordService = structuredRecordService;
    }

    @PostMapping("/structured-records")
    public StructuredRecordWriteResponse write(@Valid @RequestBody StructuredRecordWriteRequest request) {
        return structuredRecordService.write(request);
    }

    @GetMapping("/structured-records")
    public StructuredRecordReadResponse read(@RequestParam @NotBlank String subjectKey,
                                             @RequestParam Purpose purpose,
                                             @RequestParam @NotBlank String recordKey,
                                             @RequestParam(required = false) List<String> fields) {
        return structuredRecordService.read(subjectKey, purpose, recordKey, fields);
    }

    @PostMapping("/field-restrictions")
    public FieldRestrictionResponse replaceRestriction(@Valid @RequestBody FieldRestrictionReplaceRequest request) {
        return structuredRecordService.replaceRestriction(request);
    }
}
