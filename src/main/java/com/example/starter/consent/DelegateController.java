package com.example.starter.consent;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.consent.dto.BatchQueryRequest;
import com.example.starter.consent.dto.BatchQueryResponse;
import com.example.starter.consent.dto.DelegateBlockResponse;
import com.example.starter.consent.dto.DelegateCreateRequest;
import com.example.starter.consent.dto.DelegateRenewRequest;
import com.example.starter.consent.dto.DelegateRenewResponse;
import com.example.starter.consent.dto.DelegateResponse;
import com.example.starter.consent.dto.DelegateRevokeRequest;

import jakarta.validation.Valid;

/**
 * 授权代理 API：委托创建、批量续签、撤销、代理批量查询、快照、委托历史与批次阻断查询。
 */
@Validated
@RestController
@RequestMapping("/api/v1/delegations")
public class DelegateController {

    private final DelegateService delegateService;

    public DelegateController(DelegateService delegateService) {
        this.delegateService = delegateService;
    }

    /** 创建用途范围委托；用途为空或代理为本人返回 422，delegateKey 同键重放返回原委托。 */
    @PostMapping
    public DelegateResponse create(@Valid @RequestBody DelegateCreateRequest request) {
        return delegateService.create(request);
    }

    /** 批量续签：任一旧委托版本冲突整批 409 且不生效，逐条返回冲突原因。 */
    @PostMapping("/renewals")
    public DelegateRenewResponse renew(@Valid @RequestBody DelegateRenewRequest request) {
        return delegateService.renew(request);
    }

    /** 撤销委托：只影响后续查询，已生成快照不受影响。 */
    @PostMapping("/revocations")
    public DelegateResponse revoke(@Valid @RequestBody DelegateRevokeRequest request) {
        return delegateService.revoke(request);
    }

    /** 代理创建批量查询：任一主体缺失/过期/已撤销委托则整批 403 且不返回数据。 */
    @PostMapping("/queries")
    @ResponseStatus(HttpStatus.OK)
    public BatchQueryResponse batchQuery(@Valid @RequestBody BatchQueryRequest request) {
        return delegateService.batchQuery(request);
    }

    /** 查询批量查询快照（固化的授权代次、委托指纹与版本及当时记录）。 */
    @GetMapping("/queries/{queryId}")
    public BatchQueryResponse getSnapshot(@PathVariable String queryId) {
        return delegateService.getQuerySnapshot(queryId);
    }

    /** 查询委托历史：可按主体与代理过滤，包含已撤销与历史版本。 */
    @GetMapping
    public List<DelegateResponse> history(@RequestParam(required = false) String subjectKey,
                                          @RequestParam(required = false) String delegateId) {
        return delegateService.history(subjectKey, delegateId);
    }

    /** 查询批次阻断审计：可按代理过滤。 */
    @GetMapping("/blocks")
    public List<DelegateBlockResponse> blocks(@RequestParam(required = false) String delegateId) {
        return delegateService.blocks(delegateId);
    }
}
