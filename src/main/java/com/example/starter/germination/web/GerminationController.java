package com.example.starter.germination.web;

import com.example.starter.germination.service.GerminationService;
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
 * 发芽率证书业务接口。
 */
@RestController
@RequestMapping("/api/germination")
public class GerminationController {

    private final GerminationService service;

    public GerminationController(GerminationService service) {
        this.service = service;
    }

    /** 登记检测（取样/复检追加）。 */
    @PostMapping("/tests")
    @ResponseStatus(HttpStatus.CREATED)
    public TestView registerTest(@Valid @RequestBody RegisterTestRequest request) {
        return service.registerTest(request);
    }

    /** 作废取样检测记录。 */
    @PostMapping("/tests/void")
    public TestView voidTest(@Valid @RequestBody VoidTestRequest request) {
        return service.voidTest(request);
    }

    /** 签发证书。 */
    @PostMapping("/certificates")
    @ResponseStatus(HttpStatus.CREATED)
    public CertificateView issueCertificate(@Valid @RequestBody IssueCertificateRequest request) {
        return service.issueCertificate(request);
    }

    /** 批次放行。 */
    @PostMapping("/releases")
    @ResponseStatus(HttpStatus.CREATED)
    public ReleaseView releaseBatch(@Valid @RequestBody ReleaseBatchRequest request) {
        return service.releaseBatch(request);
    }

    /** 按批次查询检测链、当前证书与放行状态。 */
    @GetMapping("/batches/{batchNo}")
    public BatchChainView getBatchChain(@PathVariable String batchNo) {
        return service.getBatchChain(batchNo);
    }
}
