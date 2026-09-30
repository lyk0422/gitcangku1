package com.example.starter.cue.dto;

/**
 * 未开始场次重新绑定最新（或指定）版本的请求。
 *
 * @param versionNo 指定绑定的版本号；为空时绑定该剧目最新版本。绑定前会重新校验全部提示点
 */
public record BindVersionRequest(Integer versionNo) {
}
