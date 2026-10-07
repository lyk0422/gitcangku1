package com.example.starter.evidence;

import com.example.starter.evidence.dto.ContactBatchCreateRequest;
import com.example.starter.evidence.dto.ContactBatchView;
import com.example.starter.evidence.dto.ContaminationCreateRequest;
import com.example.starter.evidence.dto.ContaminationView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 接触批次与污染传播 API。操作人通过 X-Actor-Id 请求头提供；
 * 写操作携带 requestId 保证幂等：同键同参重放返回首次结果，同键改参返回 409；
 * 越权（非当前保管人）返回 403 且先于幂等重放判定。
 */
@RestController
@RequestMapping("/api/evidence")
@Validated
public class ContactController {

    private final ContactService contactService;
    private final IdempotencyAdvisor idempotencyAdvisor;

    public ContactController(ContactService contactService, IdempotencyAdvisor idempotencyAdvisor) {
        this.contactService = contactService;
        this.idempotencyAdvisor = idempotencyAdvisor;
    }

    /**
     * 登记接触批次：当前保管人将同属自己的 2 至 5 件 SEALED 证物组成一次接触批次。
     */
    @PostMapping("/contact-batches")
    public ResponseEntity<String> registerBatch(
            @RequestHeader(EvidenceController.ACTOR_HEADER) @NotBlank String actorId,
            @Valid @RequestBody ContactBatchCreateRequest request) {
        String hash = idempotencyAdvisor.hash(ContactService.OP_CONTACT_BATCH, actorId,
                null, request);
        StoredResponse response = idempotencyAdvisor.guard(request.requestId(), hash,
                () -> contactService.registerBatch(actorId, request, hash));
        return toEntity(response);
    }

    /**
     * 登记污染：指定一件本人持有的证物及一个已有事件序号作为污染起点，向后传播隔离。
     */
    @PostMapping("/contaminations")
    public ResponseEntity<String> registerContamination(
            @RequestHeader(EvidenceController.ACTOR_HEADER) @NotBlank String actorId,
            @Valid @RequestBody ContaminationCreateRequest request) {
        String hash = idempotencyAdvisor.hash(ContactService.OP_CONTAMINATION, actorId,
                request.evidenceKey(), request);
        StoredResponse response = idempotencyAdvisor.guard(request.requestId(), hash,
                () -> contactService.registerContamination(actorId, request, hash));
        return toEntity(response);
    }

    /**
     * 查询接触批次（含成员与事件序号）。
     */
    @GetMapping("/contact-batches/{batchKey}")
    public ContactBatchView getBatch(@PathVariable String batchKey) {
        return contactService.getBatch(batchKey);
    }

    /**
     * 查询污染登记（含冻结的传播路径）。
     */
    @GetMapping("/contaminations/{contaminationKey}")
    public ContaminationView getContamination(@PathVariable String contaminationKey) {
        return contactService.getContamination(contaminationKey);
    }

    private ResponseEntity<String> toEntity(StoredResponse response) {
        return ResponseEntity.status(HttpStatus.valueOf(response.status()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.body());
    }
}
