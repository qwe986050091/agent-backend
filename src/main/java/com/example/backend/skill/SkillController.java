package com.example.backend.skill;

import com.example.backend.auth.AuthInterceptor;
import com.example.backend.user.UserEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Skill 管理接口（需登录）
 *
 *   GET    /api/skills               平台所有 Skill（含当前用户是否已挂载）
 *   GET    /api/skills/tools         agent 侧内置可用 Tool 名称（上传 Skill 时选择）
 *   POST   /api/skills/upload        上传 Skill（JSON body 或 .json 文件）
 *   POST   /api/skills/mount         挂载 Skill
 *   POST   /api/skills/unmount       卸载 Skill
 *   DELETE /api/skills/{name}        删除自定义 Skill（内置不可删）
 */
@RestController
@RequestMapping("/api/skills")
public class SkillController {

    private final SkillService skillService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SkillController(SkillService skillService) {
        this.skillService = skillService;
    }

    /** Skill 列表（带当前用户挂载标记） */
    @GetMapping
    public ResponseEntity<?> list(HttpServletRequest request) {
        UserEntity user = (UserEntity) request.getAttribute(AuthInterceptor.CURRENT_USER_ATTR);
        Set<String> mounted = skillService.mountedNames(user.getUsername());

        List<Map<String, Object>> list = skillService.listAll().stream().map(entity -> {
            Map<String, Object> item = new HashMap<>();
            item.put("name", entity.getName());
            item.put("shortDescription", entity.getShortDescription());
            item.put("detailedDescription", entity.getDetailedDescription());
            item.put("systemPrompt", entity.getSystemPrompt());
            item.put("builtin", entity.isBuiltin());
            item.put("createdBy", entity.getCreatedBy());
            item.put("mounted", mounted.contains(entity.getName()));
            item.put("toolNames", parseToolNames(entity.getToolNamesJson()));
            item.put("type", entity.getType());
            return item;
        }).toList();

        return ResponseEntity.ok(Map.of("skills", list));
    }

    /** 内置可用 Tool 名称（来自 agent 服务，上传 Skill 时供前端勾选） */
    @GetMapping("/tools")
    public ResponseEntity<?> tools() {
        return ResponseEntity.ok(Map.of("tools", skillService.availableTools()));
    }

    /** 上传 Skill：JSON 请求体 */
    @PostMapping(value = "/upload", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> uploadJson(@RequestBody SkillUploadRequest req, HttpServletRequest request) {
        UserEntity user = (UserEntity) request.getAttribute(AuthInterceptor.CURRENT_USER_ATTR);
        return doUpload(req, user.getUsername());
    }

    /** 上传 Skill：multipart 文件（.json） */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadFile(@RequestParam("file") MultipartFile file,
                                        HttpServletRequest request) {
        UserEntity user = (UserEntity) request.getAttribute(AuthInterceptor.CURRENT_USER_ATTR);
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(error("BAD_REQUEST", "上传文件不能为空"));
        }
        try {
            String json = new String(file.getBytes(), StandardCharsets.UTF_8);
            SkillUploadRequest req = objectMapper.readValue(json, SkillUploadRequest.class);
            return doUpload(req, user.getUsername());
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(error("INVALID_SKILL", "Skill 文件解析失败: " + e.getMessage()));
        }
    }

    /** 挂载 */
    @PostMapping("/mount")
    public ResponseEntity<?> mount(@RequestBody Map<String, String> body, HttpServletRequest request) {
        UserEntity user = (UserEntity) request.getAttribute(AuthInterceptor.CURRENT_USER_ATTR);
        String skillName = body == null ? null : body.get("skillName");
        if (skillName == null || skillName.isBlank()) {
            return ResponseEntity.badRequest().body(error("BAD_REQUEST", "skillName 必填"));
        }
        boolean ok = skillService.mount(user.getUsername(), skillName);
        if (!ok) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(error("SKILL_NOT_FOUND", "Skill 不存在: " + skillName));
        }
        return ResponseEntity.ok(Map.of("message", "挂载成功: " + skillName));
    }

    /** 卸载 */
    @PostMapping("/unmount")
    public ResponseEntity<?> unmount(@RequestBody Map<String, String> body, HttpServletRequest request) {
        UserEntity user = (UserEntity) request.getAttribute(AuthInterceptor.CURRENT_USER_ATTR);
        String skillName = body == null ? null : body.get("skillName");
        if (skillName == null || skillName.isBlank()) {
            return ResponseEntity.badRequest().body(error("BAD_REQUEST", "skillName 必填"));
        }
        boolean ok = skillService.unmount(user.getUsername(), skillName);
        if (!ok) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(error("NOT_MOUNTED", "未挂载该 Skill: " + skillName));
        }
        return ResponseEntity.ok(Map.of("message", "卸载成功: " + skillName));
    }

    /** 删除自定义 Skill */
    @DeleteMapping("/{name}")
    public ResponseEntity<?> delete(@PathVariable String name) {
        boolean ok = skillService.delete(name);
        if (!ok) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(error("NOT_DELETABLE", "Skill 不存在或为内置 Skill，不可删除: " + name));
        }
        return ResponseEntity.ok(Map.of("message", "已删除: " + name));
    }

    // ==================== 内部方法 ====================

    private ResponseEntity<?> doUpload(SkillUploadRequest req, String uploader) {
        if (req == null || req.name() == null || req.name().isBlank()) {
            return ResponseEntity.badRequest().body(error("BAD_REQUEST", "Skill 名称不能为空"));
        }
        try {
            SkillEntity entity = skillService.upload(req, uploader);
            return ResponseEntity.ok(Map.of(
                    "message", "Skill 上传成功: " + entity.getName(),
                    "name", entity.getName()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error("INVALID_SKILL", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(error("AGENT_ERROR", e.getMessage()));
        }
    }

    private List<String> parseToolNames(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            return List.of();
        }
    }

    private static Map<String, Object> error(String code, String message) {
        Map<String, Object> m = new HashMap<>();
        m.put("code", code);
        m.put("message", message);
        return m;
    }
}
