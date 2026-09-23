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

import com.example.starter.calibration.api.dto.CreateInvalidationRequest;
import com.example.starter.calibration.api.dto.ImpactResponse;
import com.example.starter.calibration.api.dto.InvalidationResponse;
import com.example.starter.calibration.service.InvalidationService;

/**
 * 标准器失效单接口：创建（幂等）、详情、预览、双人确认、激活。
 */
@RestController
@RequestMapping("/api/invalidations")
public class InvalidationController {

    private final InvalidationService invalidations;

    public InvalidationController(InvalidationService invalidations) {
        this.invalidations = invalidations;
    }

    /**
     * 创建失效单：201；参数非法 400；根标准器不存在 404；
     * requestId 异参/业务键重复/版本不匹配 409。requestId 同参重放返回首次闭包快照。
     * 创建人（质量负责人）通过 X-Actor-Id 请求头提供。
     */
    @PostMapping
    public ResponseEntity<InvalidationResponse> create(@RequestBody CreateInvalidationRequest request,
                                                       @RequestHeader("X-Actor-Id") String actor) {
        return ResponseEntity.status(HttpStatus.CREATED).body(invalidations.create(request, actor));
    }

    /**
     * 失效单详情（首次闭包快照）：200；不存在 404。
     */
    @GetMapping("/{key}")
    public InvalidationResponse get(@PathVariable String key) {
        return invalidations.get(key);
    }

    /**
     * 失效预览（实时重算闭包，只读不写数据）：200；不存在 404。
     */
    @GetMapping("/{key}/preview")
    public InvalidationResponse preview(@PathVariable String key) {
        return invalidations.preview(key);
    }

    /**
     * 双人确认：200；不存在 404；已激活/确认人为创建人/重复确认 409。
     * 确认人（质量人员）通过 X-Actor-Id 请求头提供。
     */
    @PostMapping("/{key}/confirm")
    public InvalidationResponse confirm(@PathVariable String key,
                                        @RequestHeader("X-Actor-Id") String actor) {
        return invalidations.confirm(key, actor);
    }

    /**
     * 激活失效单：200；不存在 404；未双人确认/版本不匹配/闭包漂移/重复激活 409。
     */
    @PostMapping("/{key}/activate")
    public ImpactResponse activate(@PathVariable String key,
                                   @RequestHeader("X-Actor-Id") String actor) {
        return invalidations.activate(key, actor);
    }
}
