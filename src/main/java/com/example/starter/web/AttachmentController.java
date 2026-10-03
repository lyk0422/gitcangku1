package com.example.starter.web;

import com.example.starter.service.AttachmentService;
import com.example.starter.service.AttachmentService.AttachmentView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 标签附着与摘除。
 */
@RestController
@RequestMapping("/api/attachments")
public class AttachmentController {

    /**
     * 附着请求。
     *
     * @param requestId 幂等请求键
     * @param tagCode 标签编号
     * @param animalCode 动物编号（合成）
     * @param stationCode 执行附着的站点编号
     * @param attachedAt 附着开始时刻（UTC，左闭）
     */
    public record AttachRequest(@NotBlank String requestId, @NotBlank String tagCode,
            @NotBlank String animalCode, @NotBlank String stationCode,
            @NotNull Instant attachedAt) {
    }

    /**
     * 摘除请求。
     *
     * @param tagCode 标签编号
     * @param stationCode 执行摘除的站点编号
     * @param detachedAt 摘除时刻（UTC，右开）
     */
    public record DetachRequest(@NotBlank String tagCode, @NotBlank String stationCode,
            @NotNull Instant detachedAt) {
    }

    private final AttachmentService attachmentService;

    public AttachmentController(AttachmentService attachmentService) {
        this.attachmentService = attachmentService;
    }

    /**
     * 附着：同 requestId 同参重放，异参 409。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AttachmentView attach(@Valid @RequestBody AttachRequest request) {
        return attachmentService.attach(request.requestId(), request.tagCode(),
                request.animalCode(), request.stationCode(), request.attachedAt());
    }

    /**
     * 摘除：冻结上一段关联，不覆盖历史。
     */
    @PostMapping("/detach")
    public AttachmentView detach(@Valid @RequestBody DetachRequest request) {
        return attachmentService.detach(request.tagCode(), request.stationCode(),
                request.detachedAt());
    }
}