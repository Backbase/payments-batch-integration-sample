package com.mybank.payments.batches.integration;

import java.util.Arrays;
import lombok.AllArgsConstructor;

@AllArgsConstructor
public enum ExampleMode {

    NACHA_FILE("NachaFile"),
    REST_TEMPLATE("RestTemplate"),
    WEB_CLIENT("WebClient");

    String propertyValue;

    public static ExampleMode from(String value) {
        return Arrays.stream(ExampleMode.values())
            .filter(mode -> mode.propertyValue.equals(value))
            .findAny()
            .orElseThrow(() -> new RuntimeException("Incorrect example mode selected from property."));
    }

}
