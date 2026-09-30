package com.example.starter.web;

import com.example.starter.service.CertificationService;
import com.example.starter.web.dto.Requests;
import com.example.starter.web.dto.Responses;
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
 * 种子批次发芽率证书业务接口：批次、检测、作废、签发、放行、检测链查询。
 */
@RestController
@RequestMapping("/api/batches")
public class CertificationController {

    private final CertificationService service;

    public CertificationController(CertificationService service) {
        this.service = service;
    }

    /** 建立合成种子批次。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Responses.BatchResponse createBatch(@Valid @RequestBody Requests.CreateBatchRequest request) {
        return service.createBatch(request);
    }

    /** 登记检测（复检追加同样调用）。 */
    @PostMapping("/{batchNo}/tests")
    @ResponseStatus(HttpStatus.CREATED)
    public Responses.TestResponse registerTest(@PathVariable String batchNo,
                                               @Valid @RequestBody Requests.RegisterTestRequest request) {
        return service.registerTest(batchNo, request);
    }

    /** 作废取样检测记录。 */
    @PostMapping("/{batchNo}/tests/{testNo}/void")
    public Responses.TestResponse voidTest(@PathVariable String batchNo, @PathVariable String testNo) {
        return service.voidTest(batchNo, testNo);
    }

    /** 签发发芽率证书。 */
    @PostMapping("/{batchNo}/certificates")
    @ResponseStatus(HttpStatus.CREATED)
    public Responses.CertificateResponse issueCertificate(
            @PathVariable String batchNo,
            @Valid @RequestBody Requests.IssueCertificateRequest request) {
        return service.issueCertificate(batchNo, request);
    }

    /** 凭有效证书放行批次。 */
    @PostMapping("/{batchNo}/release")
    public Responses.ReleaseResponse release(@PathVariable String batchNo,
                                             @Valid @RequestBody Requests.ReleaseRequest request) {
        return service.release(batchNo, request);
    }

    /** 查询批次检测链、当前证书与放行状态。 */
    @GetMapping("/{batchNo}/chain")
    public Responses.BatchChainResponse getChain(@PathVariable String batchNo) {
        return service.getChain(batchNo);
    }
}
