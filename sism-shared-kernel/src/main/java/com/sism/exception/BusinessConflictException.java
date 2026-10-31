package com.sism.exception;

/**
 * 业务规则冲突——message 本身已是面向用户的完整指引文案。
 *
 * 2026-10-30 用户定案：审批拦截类文案（如「未完成审核：还有 N 项指标的审核进度未鉴定…」）
 * 要讲清楚去哪里、做什么，不能被全局处理器统一套上词不达意的「资源冲突」前缀。
 * GlobalExceptionHandler 对本类型原样透传 message（HTTP 状态 409、错误码 1002，
 * 与 {@link ConflictException} 一致）；其余 ConflictException 的全局映射行为不受影响。
 */
public class BusinessConflictException extends ConflictException {

    public BusinessConflictException(String message) {
        super(message);
    }
}
