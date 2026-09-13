package com.example.backend.feedback;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 点踩原因码白名单（P2-11.1）。
 * 前后端共用同一固定集合；后端校验非空 reasonCode 必须命中，管理后台据此出中文文案。
 */
public final class FeedbackReasons {

    /** code → 中文文案（有序，管理后台下拉/展示按此顺序） */
    public static final Map<String, String> REASONS;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("factual_error", "事实错误");
        m.put("off_topic", "答非所问");
        m.put("incomplete", "内容不完整");
        m.put("outdated", "信息过时");
        m.put("tool_error", "工具调用错误");
        m.put("bad_format", "表达/格式问题");
        m.put("other", "其他");
        // 注意：不能用 Map.copyOf —— 它不保证遍历顺序，前端原因下拉/后台统计会乱序
        REASONS = Collections.unmodifiableMap(new LinkedHashMap<>(m));
    }

    public static final int RATING_UP = 1;
    public static final int RATING_DOWN = -1;
    /** 取消评价（删除反馈行） */
    public static final int RATING_CANCEL = 0;

    /** 备注长度上限 */
    public static final int COMMENT_MAX_LENGTH = 1000;

    private FeedbackReasons() {
    }

    public static Set<String> codes() {
        return REASONS.keySet();
    }

    public static boolean isValidCode(String code) {
        return code != null && REASONS.containsKey(code);
    }

    public static String labelOf(String code) {
        return code == null ? null : REASONS.get(code);
    }
}
