package com.mybank.payments.batches.integration.executor.nachafile;

import com.backbase.batches.nacha.dataprovider.FileSequenceProvider;
import java.time.LocalDate;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.springframework.stereotype.Component;

/**
 * WARNING: This implementation is not suitable for distributed services.
 */
@Component
public class SimpleFileSequenceProvider implements FileSequenceProvider {
    private @Nullable LocalDate date = null;
    private @NonNull ConcurrentMap<@NonNull String, @NonNull AtomicInteger> indexMap = new ConcurrentHashMap<>();

    @Override
    public @NonNull Character getDailyId(@NonNull String companyId) {
        AtomicInteger index = indexMap.computeIfAbsent(companyId, (cId) -> new AtomicInteger(0));
        int idx = index.get();
        LocalDate currentDate = LocalDate.now();
        if (!currentDate.equals(this.date)) {
            this.date = currentDate;
            index.compareAndSet(idx, 0);
        }
        if (FileSequenceProvider.SEQUENCE_VALUES.length() <= index.get()) {
            throw new RuntimeException("Dayly file sequence has exhausted, no more NACHA file generation possible with unique sequence");
        }
        return FileSequenceProvider.SEQUENCE_VALUES.charAt(index.getAndAdd(1));
    }

    @Override
    public void next(@NonNull String companyId) {
        Optional.ofNullable(this.indexMap.get(companyId)).ifPresent(AtomicInteger::incrementAndGet);
    }

}
