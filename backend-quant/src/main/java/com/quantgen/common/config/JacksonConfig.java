package com.quantgen.common.config;

import java.math.BigDecimal;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.quantgen.common.money.Money;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.StdSerializer;

/**
 * Money is a JSON string (PRD F-LED-6): every {@link BigDecimal} is written as a plain decimal string,
 * never as a number or in scientific notation. Strings and numbers are both accepted on input.
 */
@Configuration(proxyBeanMethods = false)
public class JacksonConfig {

    @Bean
    JacksonModule moneyModule() {
        SimpleModule module = new SimpleModule("quantgen-money");
        module.addSerializer(BigDecimal.class, new BigDecimalAsStringSerializer());
        return module;
    }

    static final class BigDecimalAsStringSerializer extends StdSerializer<BigDecimal> {

        BigDecimalAsStringSerializer() {
            super(BigDecimal.class);
        }

        @Override
        public void serialize(BigDecimal value, JsonGenerator gen, SerializationContext ctxt) {
            gen.writeString(Money.toJson(value));
        }
    }
}
