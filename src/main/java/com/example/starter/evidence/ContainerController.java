package com.example.starter.evidence;

import com.example.starter.evidence.dto.ContainerCommandRequest;
import com.example.starter.evidence.dto.ContainerCreateRequest;
import com.example.starter.evidence.dto.ContainerMembersRequest;
import com.example.starter.evidence.dto.ContainerSealInspectionRequest;
import com.example.starter.evidence.dto.ContainerTransferInitiateRequest;
import com.example.starter.evidence.dto.ContainerTreeNode;
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
 * 封存箱（可嵌套容器）API。操作人通过 X-Actor-Id 请求头提供（沿用证物身份头）；
 * 写操作携带全局 requestId 保证幂等：同键同参重放返回首次完整响应，同键改参返回 409，
 * 失败不占键。装箱/拆箱/交接发起携带根箱 expectedVersion 做乐观并发控制。
 */
@RestController
@RequestMapping("/api/containers")
@Validated
public class ContainerController {

    static final String ACTOR_HEADER = "X-Actor-Id";

    private final ContainerService containerService;
    private final IdempotencyAdvisor idempotencyAdvisor;

    public ContainerController(ContainerService containerService, IdempotencyAdvisor idempotencyAdvisor) {
        this.containerService = containerService;
        this.idempotencyAdvisor = idempotencyAdvisor;
    }

    /**
     * 新建空根箱：初始 SEALED、封条 INTACT、版本 1，保管人为操作人。
     */
    @PostMapping
    public ResponseEntity<String> create(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                         @Valid @RequestBody ContainerCreateRequest request) {
        String hash = idempotencyAdvisor.hashStructured(ContainerService.OP_CONTAINER_CREATE,
                actorId, request.containerKey(), request);
        StoredResponse response = idempotencyAdvisor.guard(request.requestId(), hash,
                () -> containerService.create(actorId, request, hash));
        return toEntity(response);
    }

    /**
     * 装箱：向指定箱加入证物/子箱成员，根箱版本加一。
     */
    @PostMapping("/{containerKey}/members/add")
    public ResponseEntity<String> pack(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                       @PathVariable String containerKey,
                                       @Valid @RequestBody ContainerMembersRequest request) {
        String hash = idempotencyAdvisor.hashStructured(ContainerService.OP_CONTAINER_PACK,
                actorId, containerKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.requestId(), hash,
                () -> containerService.pack(actorId, containerKey, request, hash));
        return toEntity(response);
    }

    /**
     * 拆箱：从指定箱移出证物/子箱成员，根箱版本加一。
     */
    @PostMapping("/{containerKey}/members/remove")
    public ResponseEntity<String> unpack(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                         @PathVariable String containerKey,
                                         @Valid @RequestBody ContainerMembersRequest request) {
        String hash = idempotencyAdvisor.hashStructured(ContainerService.OP_CONTAINER_UNPACK,
                actorId, containerKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.requestId(), hash,
                () -> containerService.unpack(actorId, containerKey, request, hash));
        return toEntity(response);
    }

    /**
     * 根箱发起整箱交接：冻结整个子树成员、封条与版本。
     */
    @PostMapping("/{containerKey}/transfers")
    public ResponseEntity<String> initiateTransfer(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                                   @PathVariable String containerKey,
                                                   @Valid @RequestBody ContainerTransferInitiateRequest request) {
        String hash = idempotencyAdvisor.hashStructured(ContainerService.OP_CONTAINER_TRANSFER_INITIATE,
                actorId, containerKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.requestId(), hash,
                () -> containerService.initiateTransfer(actorId, containerKey, request, hash));
        return toEntity(response);
    }

    /**
     * 接受整箱交接：重新核验完整树后一次推进全部保管权。
     */
    @PostMapping("/{containerKey}/transfers/accept")
    public ResponseEntity<String> acceptTransfer(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                                 @PathVariable String containerKey,
                                                 @Valid @RequestBody ContainerCommandRequest request) {
        String hash = idempotencyAdvisor.hashStructured(ContainerService.OP_CONTAINER_TRANSFER_ACCEPT,
                actorId, containerKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.requestId(), hash,
                () -> containerService.acceptTransfer(actorId, containerKey, request, hash));
        return toEntity(response);
    }

    /**
     * 取消整箱交接：仅原保管人，子树解冻回到 SEALED。
     */
    @PostMapping("/{containerKey}/transfers/cancel")
    public ResponseEntity<String> cancelTransfer(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                                 @PathVariable String containerKey,
                                                 @Valid @RequestBody ContainerCommandRequest request) {
        String hash = idempotencyAdvisor.hashStructured(ContainerService.OP_CONTAINER_TRANSFER_CANCEL,
                actorId, containerKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.requestId(), hash,
                () -> containerService.cancelTransfer(actorId, containerKey, request, hash));
        return toEntity(response);
    }

    /**
     * 箱封条核验：仅当前保管人；失败使该箱封条进入 BROKEN（异常登记）。
     */
    @PostMapping("/{containerKey}/seal-inspections")
    public ResponseEntity<String> inspectSeal(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                              @PathVariable String containerKey,
                                              @Valid @RequestBody ContainerSealInspectionRequest request) {
        String hash = idempotencyAdvisor.hashStructured(ContainerService.OP_CONTAINER_SEAL_INSPECTION,
                actorId, containerKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.requestId(), hash,
                () -> containerService.inspectSeal(actorId, containerKey, request, hash));
        return toEntity(response);
    }

    /**
     * 查询容器树：箱信息 + 递归子箱 + 证物成员；待交接期间仍完整列出冻结成员。
     */
    @GetMapping("/{containerKey}/tree")
    public ContainerTreeNode tree(@PathVariable String containerKey) {
        return containerService.tree(containerKey);
    }

    private ResponseEntity<String> toEntity(StoredResponse response) {
        return ResponseEntity.status(HttpStatus.valueOf(response.status()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.body());
    }
}
