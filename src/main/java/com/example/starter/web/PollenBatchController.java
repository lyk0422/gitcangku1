package com.example.starter.web;

import com.example.starter.service.PollinationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 花粉批次登记与停用接口。
 */
@RestController
@RequestMapping("/api/pollen-batches")
public class PollenBatchController {

    private final PollinationService service;

    public PollenBatchController(PollinationService service) {
        this.service = service;
    }

    /** 登记花粉批次（ACTIVE）。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PollenBatchView create(@Valid @RequestBody CreatePollenBatchRequest request) {
        return service.createPollenBatch(request.batchNo());
    }

    /** 停用花粉批次：之后禁止用于新授粉操作。 */
    @PostMapping("/{batchNo}/disabling")
    public PollenBatchView disable(@PathVariable String batchNo) {
        return service.disablePollenBatch(batchNo);
    }
}