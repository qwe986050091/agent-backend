package com.example.backend.auth;

import com.example.backend.config.AdminProperties;
import com.example.backend.user.UserEntity;
import com.example.backend.user.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 认证接口：注册 / 登录 / 登出 / 当前用户
 *
 * 令牌方案：登录成功后生成随机 token 存入 Redis 会话（TTL 过期 + 滑动续期 + 单端互踢），
 * 后续请求通过 Authorization: Bearer <token> 携带（见 AuthInterceptor）。
 * 登录/注册接口带失败限流（用户名 + IP 双维度，连续失败锁定），防暴力破解。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final SessionStore sessionStore;
    private final LoginRateLimiter rateLimiter;
    private final AdminProperties adminProperties;

    public AuthController(UserRepository userRepository, SessionStore sessionStore,
                          LoginRateLimiter rateLimiter, AdminProperties adminProperties) {
        this.userRepository = userRepository;
        this.sessionStore = sessionStore;
        this.rateLimiter = rateLimiter;
        this.adminProperties = adminProperties;
    }

    /** 注册（注册成功即自动登录，返回 token） */
    @PostMapping("/register")
    @Transactional
    public ResponseEntity<?> register(@RequestBody AuthRequest request, HttpServletRequest http) {
        String ip = clientIp(http);
        if (rateLimiter.isLockedByIp(ip)) {
            return tooManyRequests();
        }

        String username = trim(request.getUsername());
        String password = request.getPassword();
        String nickname = trim(request.getNickname());

        if (username == null || username.length() < 3) {
            return badRequest("用户名至少 3 个字符");
        }
        if (password == null || password.length() < 6) {
            return badRequest("密码至少 6 位");
        }
        if (userRepository.existsByUsername(username)) {
            rateLimiter.recordFailure(username, ip);
            return badRequest("用户名已存在");
        }

        UserEntity user = new UserEntity();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setNickname(nickname == null || nickname.isBlank() ? username : nickname);
        userRepository.save(user);

        String token = sessionStore.createSession(user.getId());
        return ResponseEntity.ok(authResponse(user, token));
    }

    /** 登录 */
    @PostMapping("/login")
    @Transactional
    public ResponseEntity<?> login(@RequestBody AuthRequest request, HttpServletRequest http) {
        String username = trim(request.getUsername());
        String password = request.getPassword();
        String ip = clientIp(http);

        if (username == null || password == null) {
            return badRequest("用户名和密码不能为空");
        }
        if (rateLimiter.isLocked(username, ip)) {
            return tooManyRequests();
        }

        UserEntity user = userRepository.findByUsername(username).orElse(null);
        if (user == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            rateLimiter.recordFailure(username, ip);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(error("AUTH_FAILED", "用户名或密码错误"));
        }

        rateLimiter.recordSuccess(username, ip);
        String token = sessionStore.createSession(user.getId());
        return ResponseEntity.ok(authResponse(user, token));
    }

    /** 登出：删除 Redis 会话 */
    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletRequest request) {
        sessionStore.revoke(AuthInterceptor.extractToken(request));
        return ResponseEntity.ok(Map.of("message", "已退出登录"));
    }

    /** 获取当前登录用户 */
    @GetMapping("/me")
    public ResponseEntity<?> me(HttpServletRequest request) {
        String token = AuthInterceptor.extractToken(request);
        Long userId = sessionStore.validate(token);
        UserEntity user = userId == null ? null : userRepository.findById(userId).orElse(null);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(error("UNAUTHORIZED", "未登录或登录已过期"));
        }
        return ResponseEntity.ok(authResponse(user, token));
    }

    // ==================== 工具方法 ====================

    /** 取客户端 IP（经 nginx 反代时取 X-Forwarded-For 第一个地址） */
    private static String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        String ip;
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            ip = (comma == -1 ? xff : xff.substring(0, comma)).trim();
        } else {
            ip = request.getRemoteAddr();
        }
        if (ip == null || ip.isEmpty()) {
            return "unknown";
        }
        // 本地回环地址统一为 127.0.0.1（Java 侧 IPv6 回环为 0:0:0:0:0:0:0:1，避免限流桶分裂）
        return ip.equals("0:0:0:0:0:0:0:1") || ip.equals("::1") ? "127.0.0.1" : ip;
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }

    private static ResponseEntity<Map<String, Object>> badRequest(String message) {
        return ResponseEntity.badRequest().body(error("BAD_REQUEST", message));
    }

    private static ResponseEntity<Map<String, Object>> tooManyRequests() {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(error("RATE_LIMITED", "尝试次数过多，请稍后再试"));
    }

    private static Map<String, Object> error(String code, String message) {
        Map<String, Object> m = new HashMap<>();
        m.put("code", code);
        m.put("message", message);
        return m;
    }

    private Map<String, Object> authResponse(UserEntity user, String token) {
        Map<String, Object> data = new HashMap<>();
        data.put("token", token);
        data.put("username", user.getUsername());
        data.put("nickname", user.getNickname());
        // 管理员标志驱动前端「反馈管理」菜单显隐；真正鉴权在 AdminInterceptor 服务端兜底
        data.put("admin", adminProperties.isAdmin(user.getUsername()));
        return data;
    }

    // ==================== DTO ====================

    public static class AuthRequest {
        private String username;
        private String password;
        private String nickname;

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getNickname() { return nickname; }
        public void setNickname(String nickname) { this.nickname = nickname; }
    }
}
