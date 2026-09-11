package com.example.backend.billing;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LLM 计费单价配置（billing.*）。
 *
 * billing:
 *   models:
 *     deepseek-v4-flash:
 *       input-per-mtok: 1     # 元 / 百万输入 token（按缓存未命中价，保守估算）
 *       output-per-mtok: 2
 *
 * 未配置的模型费用按 0 计（BillingService 打 WARN），可随时改 yml 生效，无需改代码。
 */
@ConfigurationProperties(prefix = "billing")
public class BillingProperties {

    /** key = 模型名（与 LLM 响应 metadata.modelName 对应） */
    private Map<String, ModelPrice> models = new LinkedHashMap<>();

    public Map<String, ModelPrice> getModels() {
        return models;
    }

    public void setModels(Map<String, ModelPrice> models) {
        this.models = models;
    }

    public static class ModelPrice {
        /** 输入单价：元 / 百万 token */
        private double inputPerMtok;
        /** 输出单价：元 / 百万 token */
        private double outputPerMtok;

        public double getInputPerMtok() { return inputPerMtok; }
        public void setInputPerMtok(double inputPerMtok) { this.inputPerMtok = inputPerMtok; }
        public double getOutputPerMtok() { return outputPerMtok; }
        public void setOutputPerMtok(double outputPerMtok) { this.outputPerMtok = outputPerMtok; }
    }
}
