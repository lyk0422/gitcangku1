package com.example.starter.translation.web;

import com.example.starter.translation.dto.Dtos.AddSegmentRequest;
import com.example.starter.translation.dto.Dtos.ApproveRequest;
import com.example.starter.translation.dto.Dtos.CreateDocumentRequest;
import com.example.starter.translation.dto.Dtos.PublicationView;
import com.example.starter.translation.dto.Dtos.PublishRequest;
import com.example.starter.translation.dto.Dtos.ReviseSourceRequest;
import com.example.starter.translation.dto.Dtos.SubmitTranslationRequest;
import com.example.starter.translation.service.IdempotencyService;
import com.example.starter.translation.service.IdempotencyService.Fingerprint;
import com.example.starter.translation.service.IdempotencyService.StoredResponse;
import com.example.starter.translation.service.TranslationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.function.Supplier;

/**
 * 多语种段落修订与发布快照 API。
 * 写操作以 X-Actor-Id 标识本地操作者，并携带全局唯一 requestId 做幂等去重。
 */
@RestController
@RequestMapping("/api/documents")
public class TranslationController {

    private final TranslationService service;
    private final IdempotencyService idempotency;

    public TranslationController(TranslationService service, IdempotencyService idempotency) {
        this.service = service;
        this.idempotency = idempotency;
    }

    /**
     * 建文档/段落：创建文档、目标语言与初始段落。
     */
    @PostMapping
    public ResponseEntity<String> createDocument(@RequestHeader("X-Actor-Id") String actor,
                                                 @RequestBody CreateDocumentRequest req,
                                                 HttpServletRequest http) {
        return idempotent(req == null ? null : req.requestId(), actor, http, req,
                () -> service.createDocument(req));
    }

    /**
     * 增段落：文档草稿版本加一。
     */
    @PostMapping("/{documentId}/segments")
    public ResponseEntity<String> addSegment(@RequestHeader("X-Actor-Id") String actor,
                                             @PathVariable String documentId,
                                             @RequestBody AddSegmentRequest req,
                                             HttpServletRequest http) {
        return idempotent(req == null ? null : req.requestId(), actor, http, req,
                () -> service.addSegment(documentId, req));
    }

    /**
     * 源文修订：源文版本加一，相关译文待更新，草稿版本加一。
     */
    @PostMapping("/{documentId}/segments/{segmentId}/source")
    public ResponseEntity<String> reviseSource(@RequestHeader("X-Actor-Id") String actor,
                                               @PathVariable String documentId,
                                               @PathVariable String segmentId,
                                               @RequestBody ReviseSourceRequest req,
                                               HttpServletRequest http) {
        return idempotent(req == null ? null : req.requestId(), actor, http, req,
                () -> service.reviseSource(documentId, segmentId, req));
    }

    /**
     * 译文提交：必须匹配当前源文版本，译文版本递增，草稿版本加一。
     */
    @PostMapping("/{documentId}/segments/{segmentId}/translations")
    public ResponseEntity<String> submitTranslation(@RequestHeader("X-Actor-Id") String actor,
                                                    @PathVariable String documentId,
                                                    @PathVariable String segmentId,
                                                    @RequestBody SubmitTranslationRequest req,
                                                    HttpServletRequest http) {
        return idempotent(req == null ? null : req.requestId(), actor, http, req,
                () -> service.submitTranslation(documentId, segmentId, req, actor));
    }

    /**
     * 译文批准：审核人不得为译文作者，必须同时匹配当前源文与译文版本。
     */
    @PostMapping("/{documentId}/segments/{segmentId}/translations/{language}/approval")
    public ResponseEntity<String> approve(@RequestHeader("X-Actor-Id") String actor,
                                          @PathVariable String documentId,
                                          @PathVariable String segmentId,
                                          @PathVariable String language,
                                          @RequestBody ApproveRequest req,
                                          HttpServletRequest http) {
        return idempotent(req == null ? null : req.requestId(), actor, http, req,
                () -> service.approve(documentId, segmentId, language, req, actor));
    }

    /**
     * 发布：校验期望版本与全部有效批准后，原子生成完整只读快照并递增发布版本。
     */
    @PostMapping("/{documentId}/publications")
    public ResponseEntity<String> publish(@RequestHeader("X-Actor-Id") String actor,
                                          @PathVariable String documentId,
                                          @RequestBody PublishRequest req,
                                          HttpServletRequest http) {
        return idempotent(req == null ? null : req.requestId(), actor, http, req,
                () -> service.publish(documentId, req));
    }

    /**
     * 查询指定发布版本的完整只读快照。
     */
    @GetMapping("/{documentId}/publications/{publishedVersion}")
    public PublicationView getPublication(@PathVariable String documentId,
                                          @PathVariable int publishedVersion) {
        return service.getPublication(documentId, publishedVersion);
    }

    private ResponseEntity<String> idempotent(String requestId, String actor, HttpServletRequest http,
                                              Object payload, Supplier<Object> business) {
        Fingerprint fingerprint = new Fingerprint(http.getMethod(), http.getRequestURI(), actor, payload);
        StoredResponse response = idempotency.execute(requestId, fingerprint, business);
        return ResponseEntity.status(response.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.body());
    }
}
