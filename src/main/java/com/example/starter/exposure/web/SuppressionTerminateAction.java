package com.example.starter.exposure.web;

/**
 * 批量更新中对已存在抑制区间的处置方式。
 * <ul>
 *     <li>DELETE：仅允许未开始区间（startAtUtc &gt; 当前时刻），立即失效并保留不可变删除记录；</li>
 *     <li>END_EARLY：仅允许已开始且未自然结束的区间，提前结束，新结束时刻不得早于当前时刻。</li>
 * </ul>
 */
public enum SuppressionTerminateAction {
    DELETE,
    END_EARLY
}
