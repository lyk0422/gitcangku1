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
 * 跨仪器联合批次放行接口：联合放行、联合放行记录及按批次的测量放行明细查询。
 */
@RestController
@RequestMapping("/api/joint-releases")
public class JointReleaseController {

    private final JointReleaseService jointReleases;

    public JointReleaseController(JointReleaseService jointReleases) {
        this.jointReleases = jointReleases;
    }

    /**
     * 联合批次放行（2～20 条，可混合仪器，原子）：200；批次非法 400；
     * 任一项不满足放行条件整批 422 并逐条返回原因；
     * 并发冲突（测量被其他批次放行、证书被撤销、同键异参）409。
     * 放行人通过 X-Actor-Id 请求头提供。
     */
    @PostMapping
    public JointReleaseResponse release(@RequestBody JointReleaseRequest request,
                                        @RequestHeader("X-Actor-Id") String actor) {
        return jointReleases.release(request, actor);
    }

    /**
     * 联合放行记录及按批次的测量放行明细查询：200；不存在 404。只读，明细稳定排序。
     */
    @GetMapping("/{jointBatchKey}")
    public JointReleaseDetailResponse detail(@PathVariable String jointBatchKey) {
        return jointReleases.detail(jointBatchKey);
    }
}
