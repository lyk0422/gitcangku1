package com.example.starter.custody;

import com.example.starter.custody.CustodyDtos.BatchView;
import com.example.starter.custody.CustodyDtos.SignoffRequest;
import com.example.starter.custody.CustodyDtos.SplitRequest;
import com.example.starter.custody.CustodyDtos.SplitResponse;
import com.example.starter.custody.CustodyDtos.SourceRequest;
import com.example.starter.custody.CustodyDtos.TraceResponse;
import com.example.starter.custody.CustodyDtos.TransferRequest;
import com.example.starter.custody.CustodyDtos.TransferView;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 木材监管链接口:来源登记、拆分、移交、签收、追溯。
 */
@RestController
@RequestMapping("/api")
public class CustodyController {

    private final CustodyService service;

    public CustodyController(CustodyService service) {
        this.service = service;
    }

    /** 登记来源批次。 */
    @PostMapping("/sources")
    @ResponseStatus(HttpStatus.CREATED)
    public BatchView registerSource(@Valid @RequestBody SourceRequest request) {
        return service.registerSource(request);
    }

    /** 拆分批次,一次提交全部子批次。 */
    @PostMapping("/batches/{batchCode}/splits")
    @ResponseStatus(HttpStatus.CREATED)
    public SplitResponse split(@PathVariable String batchCode,
                               @Valid @RequestBody SplitRequest request) {
        return service.split(batchCode, request);
    }

    /** 发起移交申请。 */
    @PostMapping("/batches/{batchCode}/transfers")
    @ResponseStatus(HttpStatus.CREATED)
    public TransferView createTransfer(@PathVariable String batchCode,
                                       @Valid @RequestBody TransferRequest request) {
        return service.createTransfer(batchCode, request);
    }

    /** 签收或拒绝移交。 */
    @PostMapping("/transfers/{transferId}/signoff")
    public TransferView signoff(@PathVariable long transferId,
                                @Valid @RequestBody SignoffRequest request) {
        return service.signoff(transferId, request);
    }

    /** 按批次查询完整祖先链、重量与当前持有人。 */
    @GetMapping("/batches/{batchCode}/trace")
    public TraceResponse trace(@PathVariable String batchCode) {
        return service.trace(batchCode);
    }
}