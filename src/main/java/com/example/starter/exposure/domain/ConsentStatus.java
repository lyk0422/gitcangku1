package com.example.starter.exposure.domain;

/**
 * 同意记录状态。
 * <ul>
 *     <li>ACTIVE：有效声明，其区间参与裁决（区间可能尚未到生效起点）；</li>
 *     <li>WITHDRAWN：已撤回，仅影响撤回之后的预占，已固化的预占快照不变；</li>
 *     <li>SUPERSEDED：已被更高（或允许覆盖的）版本区间整体覆盖，不再参与裁决。</li>
 * </ul>
 */
public enum ConsentStatus {
    ACTIVE,
    WITHDRAWN,
    SUPERSEDED
}
