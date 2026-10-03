package com.example.starter.timber.web;

import com.example.starter.timber.TimberService;
import com.example.starter.timber.web.TimberDtos.RegisterSourceRequest;
import com.example.starter.timber.web.TimberDtos.SignRequest;
import com.example.starter.timber.web.TimberDtos.SignResponse;
import com.example.starter.timber.web.TimberDtos.SourceResponse;
import com.example.starter.timber.web.TimberDtos.SplitRequest;
import com.example.starter.timber.web.TimberDtos.SplitResponse;
import com.example.starter.timber.web.TimberDtos.TraceResponse;
import com.example.starter.timber.web.TimberDtos.TransferRequest;
import com.example.starter.timber.web.TimberDtos.TransferResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 监管链REST入口：来源登记、拆分、移交、签收与追溯查询。
 */
@RestController
@RequestMapping("/api")
public class TimberController {

    private final TimberService service;

    public TimberController(TimberService service) {
        this.service = service;
    }

    @PostMapping("/sources")
    public SourceResponse registerSource(@Valid @RequestBody RegisterSourceRequest request) {
        return service.registerSource(request);
    }

    @PostMapping("/batches/{batchNo}/splits")
    public SplitResponse split(@PathVariable String batchNo, @Valid @RequestBody SplitRequest request) {
        return service.split(batchNo, request);
    }

    @PostMapping("/batches/{batchNo}/transfers")
    public TransferResponse createTransfer(@PathVariable String batchNo,
                                           @Valid @RequestBody TransferRequest request) {
        return service.createTransfer(batchNo, request);
    }

    @PostMapping("/transfers/{transferId}/sign")
    public SignResponse sign(@PathVariable long transferId, @Valid @RequestBody SignRequest request) {
        return service.sign(transferId, request);
    }

    @GetMapping("/batches/{batchNo}/trace")
    public TraceResponse trace(@PathVariable String batchNo) {
        return service.trace(batchNo);
    }
}