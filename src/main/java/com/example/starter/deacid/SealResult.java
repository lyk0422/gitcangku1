package com.example.starter.deacid;

import java.util.List;

/**
 * 封存结果。
 *
 * @param outcome  结果：SEALED=已封存，REJECTED=质检退回
 * @param problems 问题页列表，封存成功时为空
 */
public record SealResult(String outcome, List<ProblemPage> problems) {
}