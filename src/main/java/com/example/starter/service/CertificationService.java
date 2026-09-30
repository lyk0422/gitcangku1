package com.example.starter.service;

import com.example.starter.web.dto.Requests;
import com.example.starter.web.dto.Responses;

/**
 * 种子发芽率证书业务服务：取样检测登记/复检追加/作废、证书签发、批次放行与检测链查询。
 * 签发与放行按 requestId 幂等；所有多步写操作在事务内完成，失败不留部分数据。
 */
public interface CertificationService {

    /**
     * 建立合成种子批次。
     */
    Responses.BatchResponse createBatch(Requests.CreateBatchRequest request);

    /**
     * 登记一次检测；复检以同样方式追加新记录。
     */
    Responses.TestResponse registerTest(String batchNo, Requests.RegisterTestRequest request);

    /**
     * 作废一条取样检测记录；若未放行批次的当前证书因此不再满足两次有效检测，证书转为失效。
     */
    Responses.TestResponse voidTest(String batchNo, String testNo);

    /**
     * 签发证书：最近两次有效检测发芽率均达到 85% 才可签发，证书固化两次检测编号、比例与签发时刻。
     * 同 requestId 同参重放返回首次响应，异参返回 409。
     */
    Responses.CertificateResponse issueCertificate(String batchNo, Requests.IssueCertificateRequest request);

    /**
     * 批次放行：只允许凭当前有效证书放行；无证书/证书失效返回 409。
     * 同 requestId 同参重放返回首次响应，异参返回 409。
     */
    Responses.ReleaseResponse release(String batchNo, Requests.ReleaseRequest request);

    /**
     * 按批次查询检测链、当前证书（含依赖检测编号）与放行状态。
     */
    Responses.BatchChainResponse getChain(String batchNo);
}
