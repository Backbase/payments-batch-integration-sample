package com.mybank.payments.batches.integration.executor;

import com.mybank.payments.batches.integration.ExampleMode;

public interface Executor {

    void execute();
    ExampleMode getType();

}
