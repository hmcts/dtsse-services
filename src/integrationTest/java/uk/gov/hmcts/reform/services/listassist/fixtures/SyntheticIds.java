package uk.gov.hmcts.reform.services.listassist.fixtures;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Resolves scenario symbols to invented decimal-string identifiers from a seeded generator. Source IDs live in a
 * separate 9xxxxxxx namespace; personal codes are generated independently and are always strings.
 */
final class SyntheticIds {

    private final Random random;
    private final Map<String, String> symbols = new LinkedHashMap<>();
    private final Set<String> used = new HashSet<>();

    SyntheticIds(long seed) {
        this.random = new Random(seed);
    }

    String id(String symbol) {
        return symbols.computeIfAbsent(symbol, s -> unique("9", 7));
    }

    String personalCode(String symbol, boolean leadingZero) {
        return symbols.computeIfAbsent(symbol,
            s -> unique(leadingZero ? "0" : String.valueOf(1 + random.nextInt(9)), 6));
    }

    int fraction() {
        return random.nextInt(10_000_000);
    }

    Map<String, String> symbols() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(symbols));
    }

    private String unique(String prefix, int digits) {
        String candidate;
        do {
            StringBuilder value = new StringBuilder(prefix);
            for (int i = 0; i < digits; i++) {
                value.append(random.nextInt(10));
            }
            candidate = value.toString();
        } while (!used.add(candidate));
        return candidate;
    }
}
