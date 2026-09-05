package com.example.backend.auth;

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
import java.util.UUID;

/**
 * 认证接口：注册 / 登录 / 登出 / 当前用户
 *
 * 令牌方案：登录成功后生成随机 token 存入用户表，后续请求通过
 * Authorization: Bearer <token> 携带（见 AuthInterceptor）。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public AuthController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /** 注册（注册成功即自动登录，返回 token） */
    @PostMapping("/register")
    @Transactional
    public ResponseEntity<?> register(@RequestBody AuthRequest request) {
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
            return badRequest("用户名已存在");
        }

        UserEntity user = new UserEntity();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setNickname(nickname == null || nickname.isBlank() ? username : nickname);
        user.setToken(newToken());
        userRepository.save(user);

        return ResponseEntity.ok(authResponse(user));
    }

    /** 登录 */
    @PostMapping("/login")
    @Transactional
    public ResponseEntity<?> login(@RequestBody AuthRequest request) {
        String username = trim(request.getUsername());
        String password = request.getPassword();

        if (username == null || password == null) {
            return badRequest("用户名和密码不能为空");
        }

        UserEntity user = userRepository.findByUsername(username).orElse(null);
        if (user == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(error("AUTH_FAILED", "用户名或密码错误"));
        }

        user.setToken(newToken());
        userRepository.save(user);
        return ResponseEntity.ok(authResponse(user));
    }

    /** 登出：清空当前 token */
    @PostMapping("/logout")
    @Transactional
    public ResponseEntity<?> logout(HttpServletRequest request) {
        String token = AuthInterceptor.extractToken(request);
        if (token != null) {
            userRepository.findByToken(token).ifPresent(user -> {
                user.setToken(null);
                userRepository.save(user);
            });
        }
        return ResponseEntity.ok(Map.of("message", "已退出登录"));
    }

    /** 获取当前登录用户 */
    @GetMapping("/me")
    public ResponseEntity<?> me(HttpServletRequest request) {
        String token = AuthInterceptor.extractToken(request);
        UserEntity user = token == null ? null : userRepository.findByToken(token).orElse(null);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(error("UNAUTHORIZED", "未登录或登录已过期"));
        }
        return ResponseEntity.ok(authResponse(user));
    }

    // ==================== 工具方法 ====================

    private static String newToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }

    private static ResponseEntity<Map<String, Object>> badRequest(String message) {
        return ResponseEntity.badRequest().body(error("BAD_REQUEST", message));
    }

    private static Map<String, Object> error(String code, String message) {
        Map<String, Object> m = new HashMap<>();
        m.put("code", code);
        m.put("message", message);
        return m;
    }

    private static Map<String, Object> authResponse(UserEntity user) {
        Map<String, Object> data = new HashMap<>();
        data.put("token", user.getToken());
        data.put("username", user.getUsername());
        data.put("nickname", user.getNickname());
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
