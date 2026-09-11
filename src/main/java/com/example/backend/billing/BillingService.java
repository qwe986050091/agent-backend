package com.example.backend.billing;

import com.example.backend.agent.trace.UsageData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Token 成本计算（P2-10）。
 *
 * cost = inputTokens/1e6 × 输入单价 + outputTokens/1e6 × 输出单价（元，6 位小数）。
 * 未配置单价的模型费用按 0 计并对每个模型名只 WARN 一次（避免刷屏）。
 */
@Service
public class BillingService {

    private static final Logger log = LoggerFactory.getLogger(BillingService.class);
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000L);

    private final BillingProperties properties;
    /** 已经告警过未配置单价的模型名（每个只告警一次） */
    private final Set<String> warnedUnknownModels = ConcurrentHashMap.newKeySet();

    public BillingService(BillingProperties properties) {
        this.properties = properties;
    }

    /** 按 token 数与配置单价计算费用（元） */
    public BigDecimal cost(String model, int inputTokens, int outputTokens) {
        BillingProperties.ModelPrice price = priceOf(model);
        if (price == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal inputCost = BigDecimal.valueOf(Math.max(inputTokens, 0))
                .multiply(BigDecimal.valueOf(price.getInputPerMtok()))
                .divide(MILLION, 6, RoundingMode.HALF_UP);
        BigDecimal outputCost = BigDecimal.valueOf(Math.max(outputTokens, 0))
                .multiply(BigDecimal.valueOf(price.getOutputPerMtok()))
                .divide(MILLION, 6, RoundingMode.HALF_UP);
        return inputCost.add(outputCost);
    }

    /** 便于 TracePersistenceService 直接按 UsageData 计算 */
    public BigDecimal cost(String model, UsageData usage) {
        return usage == null ? BigDecimal.ZERO : cost(model, usage.inputTokens(), usage.outputTokens());
    }

    private BillingProperties.ModelPrice priceOf(String model) {
        if (model == null || model.isBlank()) {
            return null;
        }
        BillingProperties.ModelPrice price = properties.getModels().get(model);
        if (price == null && warnedUnknownModels.add(model)) {
            log.warn("[Billing] 模型 {} 未配置 billing.models 单价，本轮费用按 0 计（请在 application.yml 补单价）",
                    model);
        }
        return price;
    }
}
