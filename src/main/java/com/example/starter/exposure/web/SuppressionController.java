package com.example.starter.exposure.web;

import com.example.starter.exposure.suppression.SuppressionService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 访客受众抑制名单 API。抑制优先于公告与展示位的可投放规则。
 */
@RestController
@RequestMapping("/api/exposure/suppressions")
public class SuppressionController {

    private final SuppressionService suppressionService;

    public SuppressionController(SuppressionService suppressionService) {
        this.suppressionService = suppressionService;
    }

    /** 加入抑制名单：原子取消该访客全部未确认预占并释放两级额度。 */
    @PostMapping
    public SuppressionResponse join(@Valid @RequestBody SuppressionJoinRequest request) {
        return suppressionService.join(request);
    }

    /** 解除抑制：只允许后续新申请，不恢复已取消预占。 */
    @PostMapping("/{visitorId}/release")
    public SuppressionResponse release(@PathVariable String visitorId,
                                       @Valid @RequestBody SuppressionReleaseRequest request) {
        return suppressionService.release(visitorId, request);
    }

    /** 查询抑制起止、取消的预占、额度变化与历史已确认曝光。 */
    @GetMapping("/{visitorId}")
    public SuppressionResponse getSuppression(@PathVariable String visitorId) {
        return suppressionService.getSuppression(visitorId);
    }
}
