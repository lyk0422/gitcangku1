package com.example.starter.plan.web.dto;

import java.util.List;

/**
 * 候选站台视图：调度员查询某编组需求与 UTC 窗口时，每个站台返回当前能力版本、
 * 逐条不适配原因，以及与该窗口重叠的在生效占用；available 为 TRUE 表示
 * 能力适配且窗口空闲，可一次登记到发占用。
 */
public record CandidatePlatformView(String platformId, String platformName, int maxLengthMeters,
                                    List<String> powerSystems, boolean accessible, int version,
                                    boolean compatible,
                                    List<IncompatibilityView> incompatibilities,
                                    List<PlatformWindowConflictView> windowConflicts,
                                    boolean available) {
}
