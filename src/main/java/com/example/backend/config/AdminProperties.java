package com.example.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 管理员配置（P2-11.1）：用户名白名单。
 *
 * 生产由环境变量 APP_ADMIN_USERNAMES 注入（逗号分隔，如 {@code alice,bob}），
 * 经 systemd unit 的 Environment= 配置；本地默认空名单 = 无任何管理员（fail-closed，
 * 后台接口一律 403）。系统暂无角色表，先用白名单以最小代价支撑 bad case 后台。
 */
@ConfigurationProperties(prefix = "app.admin")
public class AdminProperties {

    private List<String> usernames = new ArrayList<>();

    public List<String> getUsernames() {
        return usernames;
    }

    public void setUsernames(List<String> usernames) {
        this.usernames = usernames == null ? new ArrayList<>() : usernames;
    }

    /** 判断用户是否管理员（空白项忽略，精确匹配） */
    public boolean isAdmin(String username) {
        if (username == null || username.isBlank()) {
            return false;
        }
        for (String name : usernames) {
            if (name != null && name.trim().equals(username)) {
                return true;
            }
        }
        return false;
    }
}
