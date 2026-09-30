package com.example.starter.exposure.web;

import com.example.starter.exposure.suppression.SuppressionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
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
@RequestMapping("/api/suppression")
public class SuppressionController {

    private final SuppressionService suppressionService;

    public SuppressionController(SuppressionService suppressionService) {
        this.suppressionService = suppressionService;
    }

    /** 加入抑制名单：原子取消该访客全部未确认预占并释放对应额度。 */
    @PostMapping("/visitors/{visitorId}/suppress")
    public ResponseEntity<SuppressionResponse> suppress(@PathVariable String visitorId,
                                                        @Valid @RequestBody SuppressionActionRequest request) {
        return ResponseEntity.status(HttpStatus.OK).body(suppressionService.suppress(visitorId, request));
    }

    /** 解除抑制：仅放行后续申请，不恢复已取消预占。 */
    @PostMapping("/visitors/{visitorId}/release")
    public ResponseEntity<SuppressionResponse> release(@PathVariable String visitorId,
                                                       @Valid @RequestBody SuppressionActionRequest request) {
        return ResponseEntity.status(HttpStatus.OK).body(suppressionService.release(visitorId, request));
    }

    /** 查询抑制起止、取消的预占明细与额度变化。 */
    @GetMapping("/visitors/{visitorId}")
    public SuppressionResponse getSuppression(@PathVariable String visitorId) {
        return suppressionService.getSuppression(visitorId);
    }
}
