package com.example.backend.agent.trace;

/**
 * 一次子代理委派（对应 agent 的 event: delegation）。
 *
 * 父子关系用 parentId 表达：null=主 Agent 直接发起；非空=某次计划编排/委派节点发起。
 * 前端据此还原「主 Agent → 计划 → 子代理」的调用树。
 *
 * @param id         本次委派唯一 id
 * @param agent      子代理标识：技能名；计划编排固定为 planner
 * @param parentId   父委派 id（null=顶层）
 * @param task       交给子代理的子任务指令（截断 300）
 * @param success    是否正常返回
 * @param error      失败原因（成功为 null）
 * @param durationMs 委派耗时
 * @param tokens     本次委派消耗的 token（无法测量时为 0）
 */
public record DelegationData(String id,
                             String agent,
                             String parentId,
                             String task,
                             boolean success,
                             String error,
                             long durationMs,
                             int tokens) {
}
