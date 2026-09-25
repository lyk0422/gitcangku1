package com.example.starter.calibration.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.calibration.api.dto.JointReleaseDetailResponse;
import com.example.starter.calibration.api.dto.JointReleaseRequest;
import com.example.starter.calibration.api.dto.JointReleaseResponse;
import com.example.starter.calibration.service.JointReleaseService;

/**
 * 跨仪器联合批次放行接口：提交联合批次一致放行、查询不可变联合放行记录及明细。
 */
@RestController
@RequestMapping("/api/joint-releases")
public class JointReleaseController {

    private final JointReleaseService jointReleases;

    public JointReleaseController(JointReleaseService jointReleases) {
        this.jointReleases = jointReleases;
    }

    /**
     * 联合批次一致放行（2～20 条，可跨仪器）：200；参数非法 400；
     * 任一测量不满足条件整批 422 并逐条返回原因；并发冲突/幂等异参 409。
     * 放行人通过 X-Actor-Id 请求头提供。
     */
    @PostMapping
    public JointReleaseResponse release(@RequestBody JointReleaseRequest request,
                                        @RequestHeader("X-Actor-Id") String actor) {
        return jointReleases.release(request, actor);
    }

    /**
     * 按 jointBatchKey 查询联合放行记录及按测量键字典序稳定排序的明细；不存在 404。
     */
    @GetMapping("/{jointBatchKey}")
    public JointReleaseDetailResponse detail(@PathVariable String jointBatchKey) {
        return jointReleases.detail(jointBatchKey);
    }
}
