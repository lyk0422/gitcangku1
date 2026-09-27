package com.example.starter.firmware.api;

import java.util.List;

/**
 * 发布单目标标签策略请求。三类条件均可选，但至少给出一个；
 * 已给出的条件不能为空列表。标签在服务端规范化（去空白、转小写）。
 */
public record TargetPolicyRequest(List<String> includeAll, List<String> excludeAny,
                                  List<String> includeAny) {
}
