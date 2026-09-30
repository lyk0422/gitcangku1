package com.example.starter.salinity.web;

import com.example.starter.salinity.dto.AdoptRequest;
import com.example.starter.salinity.dto.CorrectionRequest;
import com.example.starter.salinity.dto.CorrectionView;
import com.example.starter.salinity.dto.SampleView;
import com.example.starter.salinity.dto.SealResult;
import com.example.starter.salinity.dto.SubmitSampleRequest;
import com.example.starter.salinity.dto.VoyageDataView;
import com.example.starter.salinity.exception.UnprocessableException;
import com.example.starter.salinity.service.SalinityService;
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
 * 盐度采样业务入口。
 */
@RestController
@RequestMapping("/api/voyages/{voyageCode}")
public class SalinityController {

    private final SalinityService service;

    /**
     * @param service 盐度业务服务
     */
    public SalinityController(SalinityService service) {
        this.service = service;
    }

    /**
     * 提交原始样本。201 新建；同参重放 200 返回原样本；编号相同内容不同 409；越界 422。
     */
    @PostMapping("/samples")
    public ResponseEntity<SampleView> submit(@PathVariable String voyageCode,
                                             @Valid @RequestBody SubmitSampleRequest request) {
        if (!voyageCode.equals(request.voyageCode())) {
            throw new UnprocessableException("路径航次与请求体航次不一致");
        }
        SalinityService.SubmitOutcome outcome = service.submitSample(request);
        HttpStatus status = outcome.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(outcome.sample());
    }

    /**
     * 质控采纳某一版本。跨航次/封存后采纳返回 422。
     */
    @PostMapping("/adoptions")
    public ResponseEntity<SampleView> adopt(@PathVariable String voyageCode,
                                            @Valid @RequestBody AdoptRequest request) {
        if (!voyageCode.equals(request.voyageCode())) {
            throw new UnprocessableException("路径航次与请求体航次不一致");
        }
        return ResponseEntity.ok(service.adopt(request));
    }

    /**
     * 封存航次。缺深度 422 且不锁定；成功后剖面冻结；重复封存幂等返回已固化剖面。
     */
    @PostMapping("/seal")
    public SealResult seal(@PathVariable String voyageCode) {
        return service.seal(voyageCode);
    }

    /**
     * 封存后追加勘误。201 新建；同参重放 200 返回原勘误。
     */
    @PostMapping("/corrections")
    public ResponseEntity<CorrectionView> addCorrection(@PathVariable String voyageCode,
                                                        @Valid @RequestBody CorrectionRequest request) {
        SalinityService.CorrectionOutcome outcome = service.addCorrection(voyageCode, request);
        HttpStatus status = outcome.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(outcome.correction());
    }

    /**
     * 查询原始样本、采纳版本与冻结剖面。
     */
    @GetMapping
    public VoyageDataView get(@PathVariable String voyageCode) {
        return service.getVoyageData(voyageCode);
    }
}
