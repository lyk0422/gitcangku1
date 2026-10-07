package com.example.starter.calibration.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.calibration.api.dto.FitApproveRequest;
import com.example.starter.calibration.api.dto.FitResponse;
import com.example.starter.calibration.api.dto.FitSubmitRequest;
import com.example.starter.calibration.service.FitService;

/**
 * 参考点线性拟合接口：提交（幂等占键）、批准（创建不可变证书）、历史查询。
 */
@RestController
@RequestMapping("/api/fits")
public class FitController {

    private final FitService fits;

    public FitController(FitService fits) {
        this.fits = fits;
    }

    /**
     * 提交拟合：201；参数非法（点数、重复 x、分母 0 等）400；
     * 系数不可精确表示或残差超上限 422；同 requestId 异参 409。
     */
    @PostMapping
    public ResponseEntity<String> submit(@RequestBody FitSubmitRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .contentType(MediaType.APPLICATION_JSON)
                .body(fits.submit(request));
    }

    /**
     * 批准拟合：200；拟合不存在 404；批准人与提交人相同或批准时区间冲突 409。
     * 批准人通过 X-Actor-Id 请求头提供；已成功批准的拟合只重放首次证书。
     */
    @PostMapping("/{id}/approve")
    public ResponseEntity<String> approve(@PathVariable long id,
                                          @RequestBody(required = false) FitApproveRequest request,
                                          @RequestHeader("X-Actor-Id") String actor) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(fits.approve(id, request, actor));
    }

    /**
     * 查询拟合历史（含冻结参考点与评分）：200；不存在 404。
     */
    @GetMapping("/{id}")
    public FitResponse get(@PathVariable long id) {
        return fits.get(id);
    }
}
