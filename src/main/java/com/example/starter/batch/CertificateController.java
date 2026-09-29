package com.example.starter.batch;

import com.example.starter.batch.dto.BindCertificateRequest;
import com.example.starter.batch.dto.BindCertificatesRequest;
import com.example.starter.batch.dto.BindCertificatesResponse;
import com.example.starter.batch.dto.CertificateBindingResponse;
import com.example.starter.batch.dto.CertificateDiagnosticsResponse;
import com.example.starter.batch.dto.DeclarationResponse;
import com.example.starter.batch.dto.PublishDeclarationRequest;
import com.example.starter.batch.dto.WithdrawDeclarationRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 供应商合格证声明与批次匹配门禁接口。
 */
@RestController
@RequestMapping("/api/certificates")
public class CertificateController {

    private final CertificateService service;

    public CertificateController(CertificateService service) {
        this.service = service;
    }

    /**
     * 发布版本化合格证声明（物料、允许批号前缀、优先级、UTC 左闭右开有效区间）。
     */
    @PostMapping("/declarations")
    public ResponseEntity<String> publish(
            @RequestHeader(name = "X-Actor-Id", required = false) String actorId,
            @Valid @RequestBody PublishDeclarationRequest request) {
        return stored(service.publish(request, actorId));
    }

    /**
     * 撤回声明版本：仅影响后续放行，不改写历史。
     */
    @PostMapping("/declarations/{declarationKey}/withdraw")
    public ResponseEntity<String> withdraw(
            @PathVariable String declarationKey,
            @RequestHeader(name = "X-Actor-Id", required = false) String actorId,
            @Valid @RequestBody WithdrawDeclarationRequest request) {
        return stored(service.withdraw(declarationKey, actorId, request));
    }

    /**
     * 查询单个声明版本明细（含当前状态与允许前缀，按发布顺序）。
     */
    @GetMapping("/declarations/{declarationKey}")
    public DeclarationResponse get(@PathVariable String declarationKey) {
        return service.getDeclaration(declarationKey);
    }

    /**
     * 按物料查询全部声明版本，按版本号升序（稳定排序）。
     */
    @GetMapping("/declarations")
    public List<DeclarationResponse> listByMaterial(@RequestParam String materialCode) {
        return service.listDeclarations(materialCode);
    }

    /**
     * 批量绑定声明到批次：任一失效整次回滚。
     */
    @PostMapping("/bindings")
    public ResponseEntity<String> bindMany(
            @RequestHeader(name = "X-Actor-Id", required = false) String actorId,
            @Valid @RequestBody BindCertificatesRequest request) {
        return stored(service.bindMany(actorId, request));
    }

    /**
     * 查询批次当前绑定明细，按绑定提交顺序；拆分继承条目标注 inheritedFrom。
     */
    @GetMapping("/bindings/{batchKey}")
    public List<CertificateBindingResponse> listBindings(@PathVariable String batchKey) {
        return service.listBindings(batchKey);
    }

    private ResponseEntity<String> stored(StoredResponse response) {
        return ResponseEntity.status(response.status())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(response.body());
    }
}
