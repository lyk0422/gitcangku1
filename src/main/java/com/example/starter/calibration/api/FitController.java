package com.example.starter.calibration.api;

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
import com.example.starter.calibration.service.FitOutcome;
import com.example.starter.calibration.service.FitService;

/**
 * 参考点拟合接口：提交拟合、批准（创建不可变证书）、拟合历史查询。
 * 写操作携带全局 requestId 幂等：同参重放首次完整响应、异参 409、失败不占键。
 */
@RestController
@RequestMapping("/api/fits")
public class FitController {

    private final FitService fits;

    public FitController(FitService fits) {
        this.fits = fits;
    }

    /**
     * 提交拟合：201 冻结拟合记录；参数非法 400；系数不可精确表示或残差超限 422；
     * 同 requestId 异参 409。
     */
    @PostMapping
    public ResponseEntity<String> submit(@RequestBody FitSubmitRequest request) {
        return toEntity(fits.submit(request));
    }

    /**
     * 批准拟合：201 创建不可变证书；拟合不存在 404；批准人与提交人相同或区间重叠 409；
     * 已批准的拟合再次批准重放首次证书（200），不再签发。
     * 批准人通过 X-Actor-Id 请求头提供。
     */
    @PostMapping("/{id}/approve")
    public ResponseEntity<String> approve(@PathVariable long id,
                                          @RequestBody FitApproveRequest request,
                                          @RequestHeader("X-Actor-Id") String actor) {
        return toEntity(fits.approve(id, request, actor));
    }

    /**
     * 查询拟合历史：200；不存在 404。历史不被后续撤证改写。
     */
    @GetMapping("/{id}")
    public FitResponse get(@PathVariable long id) {
        return fits.get(id);
    }

    private ResponseEntity<String> toEntity(FitOutcome outcome) {
        return ResponseEntity.status(outcome.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(outcome.body());
    }
}
