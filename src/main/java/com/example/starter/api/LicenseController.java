package com.example.starter.api;

import com.example.starter.api.dto.BindNoticeRequest;
import com.example.starter.api.dto.LicenseCheckView;
import com.example.starter.api.dto.LicensePolicyResponse;
import com.example.starter.api.dto.NarrowRegionsRequest;
import com.example.starter.api.dto.NoticeBindingResponse;
import com.example.starter.api.dto.NoticeTextResponse;
import com.example.starter.api.dto.RegisterNoticeTextRequest;
import com.example.starter.api.dto.RegisterPolicyRequest;
import com.example.starter.api.dto.ReleaseRequest;
import com.example.starter.api.dto.ReleaseSnapshotResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
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
 * 制品许可证告知与锁定图发布门禁 REST API。
 */
@RestController
@RequestMapping("/api/licenses")
@Validated
public class LicenseController {

    private final LicenseService licenseService;

    public LicenseController(LicenseService licenseService) {
        this.licenseService = licenseService;
    }

    /** 登记许可证策略。 */
    @PostMapping("/policies")
    public ResponseEntity<LicensePolicyResponse> registerPolicy(
            @RequestHeader("X-Request-Id") String requestId,
            @Valid @RequestBody RegisterPolicyRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(licenseService.registerPolicy(requestId, request));
    }

    /** 登记告知文本版本（初始 DRAFT）。 */
    @PostMapping("/notices")
    public ResponseEntity<NoticeTextResponse> registerNoticeText(
            @RequestHeader("X-Request-Id") String requestId,
            @Valid @RequestBody RegisterNoticeTextRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(licenseService.registerNoticeText(requestId, request));
    }

    /** 批准告知文本版本。 */
    @PostMapping("/notices/{noticeKey}/versions/{version}/approve")
    public NoticeTextResponse approveNoticeText(
            @RequestHeader("X-Request-Id") String requestId,
            @PathVariable String noticeKey,
            @PathVariable @Positive int version) {
        return licenseService.approveNoticeText(requestId, noticeKey, version);
    }

    /** 撤销告知文本版本。 */
    @PostMapping("/notices/{noticeKey}/versions/{version}/withdraw")
    public NoticeTextResponse withdrawNoticeText(
            @RequestHeader("X-Request-Id") String requestId,
            @PathVariable String noticeKey,
            @PathVariable @Positive int version) {
        return licenseService.withdrawNoticeText(requestId, noticeKey, version);
    }

    /** 缩窄告知文本地区覆盖。 */
    @PostMapping("/notices/{noticeKey}/versions/{version}/regions/narrow")
    public NoticeTextResponse narrowRegions(
            @RequestHeader("X-Request-Id") String requestId,
            @PathVariable String noticeKey,
            @PathVariable @Positive int version,
            @Valid @RequestBody NarrowRegionsRequest request) {
        return licenseService.narrowNoticeRegions(requestId, noticeKey, version, request);
    }

    /** 查询告知文本版本与当前地区覆盖。 */
    @GetMapping("/notices/{noticeKey}/versions/{version}")
    public NoticeTextResponse getNoticeText(
            @PathVariable String noticeKey,
            @PathVariable @Positive int version) {
        return licenseService.getNoticeText(noticeKey, version);
    }

    /** 登记告知绑定。 */
    @PostMapping("/bindings")
    public ResponseEntity<NoticeBindingResponse> bindNotice(
            @RequestHeader("X-Request-Id") String requestId,
            @Valid @RequestBody BindNoticeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(licenseService.bindNotice(requestId, request));
    }

    /** 发布单图或批量锁定图。 */
    @PostMapping("/releases")
    public ResponseEntity<ReleaseSnapshotResponse> release(
            @RequestHeader("X-Request-Id") String requestId,
            @Valid @RequestBody ReleaseRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(licenseService.release(requestId, request));
    }

    /** 查询全部历史发布快照。 */
    @GetMapping("/releases")
    public List<ReleaseSnapshotResponse> listReleases() {
        return licenseService.listReleases();
    }

    /** 按 ID 查询单个历史发布快照。 */
    @GetMapping("/releases/{id}")
    public ReleaseSnapshotResponse getRelease(@PathVariable long id) {
        return licenseService.getRelease(id);
    }

    /** 查询某锁定图的许可证命中路径与缺失告知。 */
    @GetMapping("/locks/{lockFileId}/check")
    public LicenseCheckView checkLock(
            @PathVariable long lockFileId,
            @RequestParam(name = "regions", required = false) List<String> regions) {
        return licenseService.checkLock(lockFileId, regions == null ? List.of() : regions);
    }
}
