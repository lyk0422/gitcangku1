package com.example.starter.batch;

import com.example.starter.batch.dto.CloseConditionRequest;
import com.example.starter.batch.dto.ConditionReleaseResponse;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * 条件放行接口（conditionKey 全局唯一，故不嵌套在批次路径下）：
 * 明细查询与逐条核销。
 */
@RestController
@RequestMapping("/api/conditions")
public class ConditionController {

    private final BatchService service;

    public ConditionController(BatchService service) {
        this.service = service;
    }

    /**
     * 条件放行明细：创建信息、未核销子项与到期状态，只读稳定排序。
     */
    @GetMapping("/{conditionKey}")
    public ConditionReleaseResponse detail(@PathVariable String conditionKey) {
        return service.conditionDetail(conditionKey);
    }

    /**
     * 逐条核销条件子项；核销角色必须与创建角色不同。
     * 到期未核销或批次已召回返回 422 并列出未核销子项；全部核销后批次转为 RELEASED。
     */
    @PostMapping("/{conditionKey}/closings")
    public ResponseEntity<String> close(@PathVariable String conditionKey,
                                        @RequestHeader(name = "X-Actor-Id", required = false) String actorId,
                                        @RequestHeader(name = "X-Approval-Role", required = false) String role,
                                        @Valid @RequestBody CloseConditionRequest request) {
        StoredResponse response = service.closeCondition(conditionKey, actorId, role, request);
        return ResponseEntity.status(response.status())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(response.body());
    }
}
