package com.example.starter.consent;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.consent.dto.DeriveRequest;
import com.example.starter.consent.dto.NumericRecordResponse;
import com.example.starter.consent.dto.NumericWriteRequest;
import com.example.starter.consent.dto.NumericWriteResponse;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/**
 * 研究数值/派生记录 API：数值写入、求和派生与重新鉴权读取。用途固定为 RESEARCH 研究。
 */
@Validated
@RestController
@RequestMapping("/api/v1")
public class NumericRecordController {

    private final NumericRecordService numericRecordService;

    public NumericRecordController(NumericRecordService numericRecordService) {
        this.numericRecordService = numericRecordService;
    }

    @PostMapping("/numeric-records")
    public NumericWriteResponse writeNumeric(@Valid @RequestBody NumericWriteRequest request) {
        return numericRecordService.writeNumeric(request);
    }

    @PostMapping("/derived-records")
    public NumericWriteResponse derive(@Valid @RequestBody DeriveRequest request) {
        return numericRecordService.derive(request);
    }

    @GetMapping("/numeric-records")
    public NumericRecordResponse read(@RequestParam @NotBlank String subjectKey,
                                      @RequestParam @NotBlank String recordKey) {
        return numericRecordService.read(subjectKey, recordKey);
    }
}
