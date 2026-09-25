package com.example.starter.api;

import com.example.starter.api.dto.LicensePolicyRequest;
import com.example.starter.api.dto.LicensePolicyResponse;
import com.example.starter.api.dto.MissingNoticeView;
import com.example.starter.api.dto.NarrowRegionsRequest;
import com.example.starter.api.dto.NoticeCoverageView;
import com.example.starter.api.dto.NoticeTextRequest;
import com.example.starter.api.dto.NoticeTextResponse;
import com.example.starter.api.dto.PolicyHitView;
import com.example.starter.api.dto.PublishRequest;
import com.example.starter.api.dto.PublishResponse;
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
 * 许可证告知与锁定图发布 REST API。
 */
@RestController
@RequestMapping("/api")
@Validated
public class LicenseController {

    private final LicenseService licenseService;

    public LicenseController(LicenseService licenseService) {
        this.licenseService = licenseService;
    }

    /** 登记告知文本版本（初始 DRAFT）。 */
    @PostMapping("/notice-texts")
    public ResponseEntity<NoticeTextResponse> registerNoticeText(
            @RequestHeader("X-Request-Id") String requestId,
            @Valid @RequestBody NoticeTextRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(licenseService.registerNoticeText(requestId, request));
    }

    /** 批准告知文本版本。 */
    @PostMapping("/notice-texts/{textKey}/versions/{version}/approve")
    public NoticeTextResponse approveNoticeText(
            @RequestHeader("X-Request-Id") String requestId,
            @PathVariable String textKey,
            @PathVariable @Positive int version) {
        return licenseService.approveNoticeText(requestId, textKey, version);
    }

    /** 撤销告知文本版本，仅影响后续发布。 */
    @PostMapping("/notice-texts/{textKey}/versions/{version}/withdraw")
    public NoticeTextResponse withdrawNoticeText(
            @RequestHeader("X-Request-Id") String requestId,
            @PathVariable String textKey,
            @PathVariable @Positive int version) {
        return licenseService.withdrawNoticeText(requestId, textKey, version);
    }

    /** 缩窄告知文本地区覆盖，仅影响后续发布。 */
    @PostMapping("/notice-texts/{textKey}/versions/{version}/narrow-regions")
    public NoticeTextResponse narrowNoticeTextRegions(
            @RequestHeader("X-Request-Id") String requestId,
            @PathVariable String textKey,
            @PathVariable @Positive int version,
            @Valid @RequestBody NarrowRegionsRequest request) {
        return licenseService.narrowNoticeTextRegions(requestId, textKey, version, request);
    }

    /** 查询告知文本版本的地区覆盖与状态。 */
    @GetMapping("/notice-texts/{textKey}/versions/{version}/coverage")
    public NoticeCoverageView getNoticeCoverage(
            @PathVariable String textKey,
            @PathVariable @Positive int version) {
        return licenseService.getNoticeCoverage(textKey, version);
    }

    /** 登记许可证策略（LOCK_FILE 或 ARTIFACT 作用域）。 */
    @PostMapping("/license-policies")
    public ResponseEntity<LicensePolicyResponse> registerPolicy(
            @RequestHeader("X-Request-Id") String requestId,
            @Valid @RequestBody LicensePolicyRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(licenseService.registerPolicy(requestId, request));
    }

    /** 查询全部许可证策略。 */
    @GetMapping("/license-policies")
    public List<LicensePolicyResponse> listPolicies() {
        return licenseService.listPolicies();
    }

    /** 查询锁定图闭包内全部制品的策略命中路径。 */
    @GetMapping("/lock-notices/{lockFileId}/hits")
    public List<PolicyHitView> listPolicyHits(@PathVariable @Positive long lockFileId) {
        return licenseService.listPolicyHits(lockFileId);
    }

    /** 按目标地区预检锁定图的告知缺失/未批准/地区不覆盖项。 */
    @GetMapping("/lock-notices/{lockFileId}/missing")
    public List<MissingNoticeView> listMissingNotices(
            @PathVariable @Positive long lockFileId,
            @RequestParam List<String> regions) {
        return licenseService.listMissingNotices(lockFileId, regions);
    }

    /** 批量发布锁定图（整批原子，noticeKey 幂等）。 */
    @PostMapping("/lock-publishes")
    public ResponseEntity<PublishResponse> publishLocks(
            @Valid @RequestBody PublishRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(licenseService.publishLocks(request));
    }

    /** 查询全部历史发布快照。 */
    @GetMapping("/lock-publishes")
    public List<PublishResponse> listPublishes() {
        return licenseService.listPublishes();
    }

    /** 按 ID 查询历史发布快照。 */
    @GetMapping("/lock-publishes/{id}")
    public PublishResponse getPublish(@PathVariable @Positive long id) {
        return licenseService.getPublish(id);
    }
}
