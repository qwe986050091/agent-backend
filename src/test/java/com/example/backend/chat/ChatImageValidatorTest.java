package com.example.backend.chat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ChatImageValidator} 单测（P1-8 权威校验闸门）：
 * 数量、大小、base64 合法性、magic bytes 与声明 MIME 一致性、开关、data: 前缀容错。
 */
class ChatImageValidatorTest {

    private ChatImageProperties props;
    private ChatImageValidator validator;

    /** 各格式最小合法文件头（后面补零到 32 字节） */
    private static byte[] magic(String type) {
        byte[] head = switch (type) {
            case "image/jpeg" -> new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};
            case "image/png" -> new byte[]{
                    (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
            case "image/gif" -> new byte[]{'G', 'I', 'F', '8', '9', 'a'};
            case "image/webp" -> new byte[]{
                    'R', 'I', 'F', 'F', 0x00, 0x00, 0x00, 0x00, 'W', 'E', 'B', 'P'};
            default -> throw new IllegalArgumentException(type);
        };
        byte[] bytes = new byte[32];
        System.arraycopy(head, 0, bytes, 0, head.length);
        return bytes;
    }

    private ChatSendRequest.ImageInput input(String mime, byte[] bytes, boolean withDataUrlPrefix) {
        String b64 = Base64.getEncoder().encodeToString(bytes);
        return new ChatSendRequest.ImageInput(mime,
                withDataUrlPrefix ? "data:" + mime + ";base64," + b64 : b64);
    }

    @BeforeEach
    void setUp() {
        props = new ChatImageProperties();
        props.setEnabled(true);
        props.setMaxCount(3);
        props.setMaxSizeBytes(1024);
        validator = new ChatImageValidator(props);
    }

    @Test
    void nullOrEmpty_returnsEmptyList() {
        assertThat(validator.validate(null)).isEmpty();
        assertThat(validator.validate(List.of())).isEmpty();
    }

    @Test
    void allFourFormats_pass_withAndWithoutDataUrlPrefix() {
        List<ChatSendRequest.ImageInput> inputs = new ArrayList<>();
        boolean prefix = false;
        for (String mime : List.of("image/jpeg", "image/png", "image/gif", "image/webp")) {
            inputs.add(input(mime, magic(mime), prefix));
            prefix = true;
        }
        // 一次只测 3 张上限内，拆分断言四种格式（含 data: 前缀容错）
        for (String mime : List.of("image/jpeg", "image/png", "image/gif", "image/webp")) {
            ChatSendRequest.ImageInput in = input(mime, magic(mime), true);
            List<ChatImageValidator.ValidImage> out = validator.validate(List.of(in));
            assertThat(out).hasSize(1);
            assertThat(out.get(0).mimeType()).isEqualTo(mime);
            assertThat(out.get(0).bytes()).hasSize(32);
        }
    }

    @Test
    void tooManyImages_rejected() {
        List<ChatSendRequest.ImageInput> inputs = List.of(
                input("image/png", magic("image/png"), false),
                input("image/png", magic("image/png"), false),
                input("image/png", magic("image/png"), false),
                input("image/png", magic("image/png"), false));
        assertThatThrownBy(() -> validator.validate(inputs))
                .isInstanceOf(ChatImageValidator.ChatImageRejectedException.class)
                .satisfies(e -> assertThat(((ChatImageValidator.ChatImageRejectedException) e).code())
                        .isEqualTo("TOO_MANY_IMAGES"));
    }

    @Test
    void oversizedImage_rejectedWith413Code() {
        props.setMaxSizeBytes(16);
        ChatImageValidator.ChatImageRejectedException ex = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> validator.validate(List.of(input("image/png", magic("image/png"), false))),
                ChatImageValidator.ChatImageRejectedException.class);
        assertThat(ex.code()).isEqualTo("IMAGE_TOO_LARGE");
    }

    @Test
    void declaredMimeNotInWhitelist_rejected() {
        ChatImageValidator.ChatImageRejectedException ex = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> validator.validate(List.of(new ChatSendRequest.ImageInput(
                        "image/bmp", Base64.getEncoder().encodeToString(magic("image/png"))))),
                ChatImageValidator.ChatImageRejectedException.class);
        assertThat(ex.code()).isEqualTo("INVALID_IMAGE");
    }

    @Test
    void magicBytesMismatch_rejected() {
        // 声明 PNG，实际塞 JPEG 文件头——必须识破（DeepSeek 按实际内容判格式）
        ChatSendRequest.ImageInput fakePng = input("image/png", magic("image/jpeg"), false);
        ChatImageValidator.ChatImageRejectedException ex = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> validator.validate(List.of(fakePng)),
                ChatImageValidator.ChatImageRejectedException.class);
        assertThat(ex.code()).isEqualTo("INVALID_IMAGE");
    }

    @Test
    void invalidBase64_rejected() {
        ChatImageValidator.ChatImageRejectedException ex = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> validator.validate(List.of(new ChatSendRequest.ImageInput("image/png", "@@@not-base64"))),
                ChatImageValidator.ChatImageRejectedException.class);
        assertThat(ex.code()).isEqualTo("INVALID_IMAGE");
    }

    @Test
    void disabledFeature_rejectsEverything() {
        props.setEnabled(false);
        ChatImageValidator.ChatImageRejectedException ex = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> validator.validate(List.of(input("image/png", magic("image/png"), false))),
                ChatImageValidator.ChatImageRejectedException.class);
        assertThat(ex).hasMessageContaining("未开启图片发送功能");
    }
}
