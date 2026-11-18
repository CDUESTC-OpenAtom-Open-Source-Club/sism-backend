package com.sism.analytics.interfaces.dto;

/**
 * MonthState - 看板「按月快照」口径下的单月月报状态标记（2026-11-18 用户拍板）。
 *
 * <p>看板不再沿用上月结论：每个选中月份对指标的整体评判是一份当月快照——</p>
 * <ul>
 *   <li>{@link #APPROVED}：当月月报已审批通过，当月鉴定值有效，正常出图；</li>
 *   <li>{@link #IN_REVIEW}：当月已填报但审批未通过（SUBMITTED/IN_REVIEW），鉴定值无效，
 *       前端置灰并提示「审批中」；</li>
 *   <li>{@link #NOT_REPORTED}：当月未填报，无鉴定值，前端置灰。</li>
 * </ul>
 *
 * <p>各月互不沿用：未出结果的月份一律置灰，不继承最近一次已批准鉴定。</p>
 */
public enum MonthState {

    /** 当月月报已审批通过，鉴定值有效 */
    APPROVED,

    /** 当月已填报但审批未通过（SUBMITTED/IN_REVIEW 落库值），无鉴定值，前端置灰标「审批中」 */
    IN_REVIEW,

    /** 当月未填报，无鉴定值，前端置灰 */
    NOT_REPORTED
}
