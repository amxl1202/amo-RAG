package com.ragkb.metrics;

/**
 * 成本参数。
 *
 * <p>价格必须可配置:模型调价时改配置,而不是改代码里的魔法数。
 * 单位是"元 / 千 token",与 DashScope 的计费口径一致。
 */
@org.springframework.boot.context.properties.ConfigurationProperties(prefix = "app.metrics")
public class MetricsProperties {

    private double inputPricePer1k = 0.0008;

    private double outputPricePer1k = 0.002;

    public double getInputPricePer1k() {
        return inputPricePer1k;
    }

    public void setInputPricePer1k(double inputPricePer1k) {
        this.inputPricePer1k = inputPricePer1k;
    }

    public double getOutputPricePer1k() {
        return outputPricePer1k;
    }

    public void setOutputPricePer1k(double outputPricePer1k) {
        this.outputPricePer1k = outputPricePer1k;
    }
}
