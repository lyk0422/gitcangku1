package com.example.starter.calibration.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.calibration.api.dto.CreateDifferentialRequest;
import com.example.starter.calibration.api.dto.DifferentialActionRequest;
import com.example.starter.calibration.api.dto.DifferentialResponse;
import com.example.starter.calibration.service.DifferentialService;

/**
 * 差分接口：创建（绑定参考/待测两条测量）、放行、取消、明细与当前可用查询。
 * 写操作均以 requestId 幂等；操作人通过 X-Actor-Id 请求头提供。
 */
@RestController
@RequestMapping("/api/differentials")
public class DifferentialController {

    private final DifferentialService differentials;

    public DifferentialController(DifferentialService differentials) {
        this.differentials = differentials;
    }

    /**
     * 创建差分：201；参数非法 400；测量不存在 404；配对/仪器/间隔/合格性不满足 422；
     * 测量已被占用或差分键重复 409；同 requestId 同参重放首次响应，异参 409。
     */
    @PostMapping
    public ResponseEntity<DifferentialResponse> create(@RequestBody CreateDifferentialRequest request,
                                                       @RequestHeader("X-Actor-Id") String actor) {
        return ResponseEntity.status(HttpStatus.CREATED).body(differentials.create(request, actor));
    }

    /**
     * 放行差分：200；同时放行两条原测量并冻结双方证书与精确值；
     * 任一证书撤销或原测量已放行整次 409；放行人不得是任一原测量提交人。
     */
    @PostMapping("/{key}/release")
    public DifferentialResponse release(@PathVariable String key,
                                        @RequestBody DifferentialActionRequest request,
                                        @RequestHeader("X-Actor-Id") String actor) {
        return differentials.release(key, request, actor);
    }

    /**
     * 取消差分：200；仅放行前可取消，取消后释放配对占用，记录保留不删除；已放行 409。
     */
    @PostMapping("/{key}/cancel")
    public DifferentialResponse cancel(@PathVariable String key,
                                       @RequestBody DifferentialActionRequest request,
                                       @RequestHeader("X-Actor-Id") String actor) {
        return differentials.cancel(key, request, actor);
    }

    /**
     * 当前可用差分：已放行且双方证书均未撤销；可按仪器过滤。
     */
    @GetMapping("/usable")
    public List<DifferentialResponse> usable(@RequestParam(required = false) String instrumentId) {
        return differentials.usable(instrumentId);
    }

    /**
     * 差分明细：未舍入差值、显示差值与放行/取消证据；证书撤销后仍可查；不存在 404。
     */
    @GetMapping("/{key}")
    public DifferentialResponse detail(@PathVariable String key) {
        return differentials.detail(key);
    }
}
