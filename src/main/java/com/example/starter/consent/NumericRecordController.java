package com.example.starter.consent;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.consent.dto.DeriveRequest;
import com.example.starter.consent.dto.NumericRecordAck;
import com.example.starter.consent.dto.NumericRecordResponse;
import com.example.starter.consent.dto.NumericWriteRequest;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/**
 * 研究数值记录 API：整数数值写入、求和派生与重新鉴权读取。
 */
@Validated
@RestController
@RequestMapping("/api/v1/numeric-records")
public class NumericRecordController {

    private final NumericRecordService numericRecordService;

    public NumericRecordController(NumericRecordService numericRecordService) {
        this.numericRecordService = numericRecordService;
    }

    @PostMapping
    public NumericRecordAck writeValue(@Valid @RequestBody NumericWriteRequest request) {
        return numericRecordService.writeValue(request);
    }

    @PostMapping("/derivations")
    public NumericRecordAck derive(@Valid @RequestBody DeriveRequest request) {
        return numericRecordService.derive(request);
    }

    @GetMapping
    public NumericRecordResponse read(@RequestParam @NotBlank String subjectKey,
                                      @RequestParam @NotBlank String recordKey) {
        return numericRecordService.read(subjectKey, recordKey);
    }
}
