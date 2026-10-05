package com.example.starter.calibration.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.calibration.api.dto.CreateDifferentialRequest;
import com.example.starter.calibration.api.dto.DifferentialActionRequest;
import com.example.starter.calibration.api.dto.DifferentialResponse;
import com.example.starter.calibration.service.DifferentialService;

/**
 * 差分接口：创建、放行、取消、明细查询。
 * 写操作均要求 X-Actor-Id（操作者）与 X-Request-Id（幂等键）请求头。
 */
@RestController
@RequestMapping("/api/differentials")
public class DifferentialController {

    private final DifferentialService differentials;

    public DifferentialController(DifferentialService differentials) {
        this.differentials = differentials;
    }

    /**
     * 创建差分：201；参数非法 400；测量不存在 404；占用/状态冲突 409；配对约束不满足 422。
     * 同 requestId 同参数重放返回首次完整响应；同 requestId 异参数返回 409。
     */
    @PostMapping
    public ResponseEntity<DifferentialResponse> create(@RequestBody CreateDifferentialRequest request,
                                                       @RequestHeader("X-Actor-Id") String actor,
                                                       @RequestHeader("X-Request-Id") String requestId) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(differentials.create(requestId, actor, request));
    }

    /**
     * 放行差分：200；不存在 404；状态/证书/版本冲突 409。同时放行两条原测量。
     */
    @PostMapping("/{id}/release")
    public DifferentialResponse release(@PathVariable long id,
                                        @RequestBody(required = false) DifferentialActionRequest request,
                                        @RequestHeader("X-Actor-Id") String actor,
                                        @RequestHeader("X-Request-Id") String requestId) {
        return differentials.release(requestId, actor, id, expectedVersion(request));
    }

    /**
     * 取消差分：200；不存在 404；已放行/已取消 409。记录保留，占用释放。
     */
    @PostMapping("/{id}/cancel")
    public DifferentialResponse cancel(@PathVariable long id,
                                       @RequestBody(required = false) DifferentialActionRequest request,
                                       @RequestHeader("X-Actor-Id") String actor,
                                       @RequestHeader("X-Request-Id") String requestId) {
        return differentials.cancel(requestId, actor, id, expectedVersion(request));
    }

    /**
     * 差分明细：200；不存在 404。撤销证书后仍可查，usable 变为 false。
     */
    @GetMapping("/{id}")
    public DifferentialResponse detail(@PathVariable long id) {
        return differentials.detail(id);
    }

    private static Long expectedVersion(DifferentialActionRequest request) {
        return request == null ? null : request.expectedVersion();
    }
}
