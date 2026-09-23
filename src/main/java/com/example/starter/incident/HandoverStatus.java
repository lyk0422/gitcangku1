package com.example.starter.incident;

/**
 * 联合指挥交接单状态：PENDING 预览冻结待接受，ACCEPTED 已接受并整体切换指挥权。
 */
public enum HandoverStatus {

    /** 待接受：闭包与冻结摘要已生成，指挥权尚未切换。 */
    PENDING,

    /** 已接受：全部闭包事件指挥人已在单事务内切换，闭包快照已保存。 */
    ACCEPTED
}
