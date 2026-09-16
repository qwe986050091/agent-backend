package com.example.backend.knowledge;

import com.example.backend.agent.AgentClient;
import com.example.backend.auth.AuthInterceptor;
import com.example.backend.user.UserEntity;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 知识库接口（需登录）
 *
 *   POST   /api/knowledge/upload          上传文档（txt/md 纯文本 或 PDF/Office/HTML 二进制），
 *                                         切块向量化存入 agent（二进制由 agent 侧 Tika 抽正文）
 *   GET    /api/knowledge/documents       当前用户的文档列表
 *   DELETE /api/knowledge/documents/{id}  删除文档元数据
 *
 * 说明：文档全文不落 MySQL，只存元数据；文本切块后存入 agent 侧向量库供 RAG 检索
 * （agent 侧向量库会快照到磁盘文件，服务重启后自动恢复，无需重新上传）。
 */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeController.class);

    /** 纯文本格式：backend 直接 UTF-8 解码走 JSON 索引 */
    private static final Set<String> TEXT_EXTENSIONS = Set.of("txt", "md", "text", "markdown");
    /** 二进制格式：原始字节 multipart 转发 agent，由 Tika 抽取正文（P1-9.2） */
    private static final Set<String> BINARY_EXTENSIONS = Set.of(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "html", "htm");
    /** 当前允许上传的全部文档类型（纯文本 + 二进制） */
    private static final Set<String> ALLOWED_EXTENSIONS =
            java.util.stream.Stream.concat(TEXT_EXTENSIONS.stream(), BINARY_EXTENSIONS.stream())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());

    private final AgentClient agentClient;
    private final KnowledgeDocumentRepository repository;

    public KnowledgeController(AgentClient agentClient, KnowledgeDocumentRepository repository) {
        this.agentClient = agentClient;
        this.repository = repository;
    }

    /** 上传文档：multipart 文件 */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file,
                                    HttpServletRequest request) {
        UserEntity user = (UserEntity) request.getAttribute(AuthInterceptor.CURRENT_USER_ATTR);
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(error("BAD_REQUEST", "上传文件不能为空"));
        }

        String originalName = file.getOriginalFilename() == null ? "未命名文档" : file.getOriginalFilename();
        String ext = extensionOf(originalName);
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            return ResponseEntity.badRequest()
                    .body(error("UNSUPPORTED_TYPE",
                            "仅支持 txt/md/pdf/Word/Excel/PPT/HTML 文件，当前文件: " + originalName));
        }

        String username = user.getUsername();
        String docId = UUID.randomUUID().toString().replace("-", "");

        // 调 agent 切块 + 向量化；失败则不落库。
        // 纯文本走 JSON 接口（backend 直接解码）；二进制走 multipart 接口（agent Tika 抽正文）
        int chunks;
        try {
            if (TEXT_EXTENSIONS.contains(ext)) {
                String text;
                try {
                    text = new String(file.getBytes(), StandardCharsets.UTF_8);
                } catch (Exception e) {
                    return ResponseEntity.badRequest()
                            .body(error("READ_FAILED", "文件读取失败: " + e.getMessage()));
                }
                if (text.isBlank()) {
                    return ResponseEntity.badRequest().body(error("EMPTY_DOC", "文档内容为空"));
                }
                chunks = agentClient.indexDocument(username, docId, originalName, text);
            } else {
                chunks = agentClient.indexDocumentFile(
                        username, docId, originalName, originalName, file.getBytes());
            }
        } catch (HttpClientErrorException e) {
            // agent 侧 4xx：文档不支持/加密/损坏/空文本/超限(422)、文件超 20MB(413) 等，
            // 原样透传状态码与可读原因，用户可处理后重试；4xx 不顶开 agent 熔断器
            log.info("[Knowledge] agent 拒绝文档《{}》: {} {}", originalName,
                    e.getStatusCode().value(), e.getResponseBodyAsString());
            return ResponseEntity.status(e.getStatusCode())
                    .body(error(agentErrorCode(e), agentErrorMessage(e)));
        } catch (Exception e) {
            log.warn("[Knowledge] 文档索引到 agent 失败: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(error("INDEX_FAILED", "文档索引失败（Agent/RAG 不可用）: " + e.getMessage()));
        }

        KnowledgeDocumentEntity entity = new KnowledgeDocumentEntity();
        entity.setUsername(username);
        entity.setDocId(docId);
        entity.setDocName(originalName);
        entity.setDocType(ext);
        entity.setChunkCount(chunks);
        repository.save(entity);

        log.info("[Knowledge] 用户 {} 上传文档《{}》，切块 {} 个", username, originalName, chunks);

        Map<String, Object> data = new HashMap<>();
        data.put("id", entity.getId());
        data.put("docName", originalName);
        data.put("chunks", chunks);
        return ResponseEntity.ok(data);
    }

    /** 当前用户文档列表 */
    @GetMapping("/documents")
    public ResponseEntity<?> documents(HttpServletRequest request) {
        UserEntity user = (UserEntity) request.getAttribute(AuthInterceptor.CURRENT_USER_ATTR);
        List<KnowledgeDocumentEntity> docs = repository.findByUsernameOrderByIdDesc(user.getUsername());

        List<Map<String, Object>> list = docs.stream().map(d -> {
            Map<String, Object> item = new HashMap<>();
            item.put("id", d.getId());
            item.put("docName", d.getDocName());
            item.put("docType", d.getDocType());
            item.put("chunkCount", d.getChunkCount());
            item.put("createdAt", d.getCreatedAt());
            return item;
        }).toList();

        return ResponseEntity.ok(Map.of("documents", list));
    }

    /** 删除文档元数据 */
    @DeleteMapping("/documents/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id, HttpServletRequest request) {
        UserEntity user = (UserEntity) request.getAttribute(AuthInterceptor.CURRENT_USER_ATTR);
        return repository.findById(id).map(doc -> {
            if (!doc.getUsername().equals(user.getUsername())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(error("FORBIDDEN", "无权删除他人文档"));
            }
            repository.delete(doc);
            // 同步删除 agent 侧向量片段（best-effort：agent 不可用时只记日志，
            // 不影响元数据删除；极端情况下残留向量需重新上传/删除一次或重启清理）
            try {
                agentClient.removeDocument(doc.getDocId());
            } catch (Exception e) {
                log.warn("[Knowledge] 删除文档《{}》的 agent 向量失败: {}", doc.getDocName(), e.getMessage());
            }
            return ResponseEntity.ok(Map.of("deleted", id));
        }).orElse(ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(error("NOT_FOUND", "文档不存在")));
    }

    private String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase();
    }

    private Map<String, String> error(String code, String message) {
        Map<String, String> m = new HashMap<>();
        m.put("code", code);
        m.put("message", message);
        return m;
    }

    /** 从 agent 的错误响应体（{code,message} JSON）提取原因码，失败时给通用码 */
    private String agentErrorCode(HttpClientErrorException e) {
        return extractAgentField(e, "code", "AGENT_REJECTED");
    }

    /** 从 agent 的错误响应体提取可读原因，失败时用状态文本兜底 */
    private String agentErrorMessage(HttpClientErrorException e) {
        return extractAgentField(e, "message", "文档被 Agent 拒绝（HTTP " + e.getStatusCode().value() + "）");
    }

    private String extractAgentField(HttpClientErrorException e, String field, String fallback) {
        try {
            String body = e.getResponseBodyAsString();
            if (body != null && !body.isBlank()) {
                JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
                JsonNode value = node.get(field);
                if (value != null && !value.asText().isBlank()) {
                    return value.asText();
                }
            }
        } catch (Exception ignored) {
            // agent 响应非 JSON，走兜底
        }
        return fallback;
    }
}