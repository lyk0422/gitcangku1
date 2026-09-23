package com.example.starter.domain;

/**
 * 一条可选依赖在锁定时的判定结果。
 *
 * @param sourceName      声明该可选依赖的已选制品名称
 * @param dependencyName  可选依赖的目标制品名称
 * @param minimumVersion  声明区间下界（含）
 * @param maximumVersion  声明区间上界（含）
 * @param included        true=已纳入锁文件；false=跳过
 * @param selectedVersion 纳入时的精确版本；跳过时为 null
 * @param reason          跳过时的稳定原因；纳入时为 null
 */
public record OptionalDecision(
        String sourceName,
        String dependencyName,
        int minimumVersion,
        int maximumVersion,
        boolean included,
        Integer selectedVersion,
        String reason) {

    /** 已纳入。 */
    public static OptionalDecision included(String sourceName, DependencyRange dep, int selectedVersion) {
        return new OptionalDecision(sourceName, dep.name(), dep.minimumVersion(), dep.maximumVersion(),
                true, selectedVersion, null);
    }

    /** 已选择但已选版本不满足声明区间（不能更换此前选择）。 */
    public static OptionalDecision skippedSelectedOutOfRange(String sourceName, DependencyRange dep,
                                                             int selectedVersion) {
        return new OptionalDecision(sourceName, dep.name(), dep.minimumVersion(), dep.maximumVersion(),
                false, null, "selected-version-out-of-range: 已选版本 " + selectedVersion
                        + " 不在区间 [" + dep.minimumVersion() + "," + dep.maximumVersion() + "] 内");
    }

    /** 目标平台下不存在可用（未撤回且兼容）版本。 */
    public static OptionalDecision skippedNoCandidate(String sourceName, DependencyRange dep) {
        return new OptionalDecision(sourceName, dep.name(), dep.minimumVersion(), dep.maximumVersion(),
                false, null, "no-compatible-candidate: 目标平台下无满足区间 ["
                        + dep.minimumVersion() + "," + dep.maximumVersion() + "] 的未撤回版本");
    }

    /** 最高可行版本及其必选闭包无法在不改变已选集合的前提下加入。 */
    public static OptionalDecision skippedInfeasibleClosure(String sourceName, DependencyRange dep) {
        return new OptionalDecision(sourceName, dep.name(), dep.minimumVersion(), dep.maximumVersion(),
                false, null, "infeasible-mandatory-closure: 无法在不改变已选集合的前提下"
                        + "加入满足区间 [" + dep.minimumVersion() + "," + dep.maximumVersion()
                        + "] 的版本及其必选闭包");
    }
}
