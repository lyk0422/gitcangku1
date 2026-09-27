package com.example.starter.firmware.api;

import java.util.List;

/**
 * 设备拉取响应。
 * task 为命中或已存在的任务；无匹配投放时为 null。
 * manifest 为该任务当前尝试代次对应的分片登记清单（按序号升序）与完整包摘要，
 * 设备须逐分片比对提交接收摘要；无任务时为 null。
 */
public record PullResponse(TaskView task, FragmentManifest manifest) {

    public PullResponse(TaskView task) {
        this(task, null);
    }

    /**
     * 分片下发清单：快照自发布单登记，发布单撤回或修改不影响已拉取证据的判定依据。
     */
    public record FragmentManifest(int attempt, String packageDigest, List<FragmentView> fragments) {
    }
}
