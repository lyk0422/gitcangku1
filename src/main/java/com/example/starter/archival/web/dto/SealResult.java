package com.example.starter.archival.web.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 封存结果。sealed=false 时 problems 列出全部问题页，批次保持/转入 REJECTED 且数据不改动；
 * sealed=true 时批次已封存只读、占用已释放。replayed=true 表示同 requestId 同参重放的首次结果。
 *
 * @param batchNo   批次号
 * @param sealed    是否封存成功
 * @param problems  全部问题页及原因；成功为空列表
 * @param sealedAt  封存时间（Asia/Shanghai）；失败为 null
 * @param replayed  是否为幂等重放
 */
public record SealResult(String batchNo, boolean sealed, List<SealProblem> problems,
                         LocalDateTime sealedAt, boolean replayed) {
}
