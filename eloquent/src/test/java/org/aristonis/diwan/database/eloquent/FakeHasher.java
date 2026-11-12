package org.aristonis.diwan.database.eloquent;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.aristonis.diwan.database.eloquent.casts.AttributeHasher;

/**
 * Test hasher: reversible, different on every call, never real hashing. A hash reads
 * {@code $fake$<algorithm>$c=<cost>$m=<memory>$t=<time>$p=<threads>$<counter>$<Base64 of plain>}, where the
 * counter plays the salt. The fields are open so a test can read the call counts, switch the failures on and
 * change the settings a hash must satisfy to count as current.
 */
final class FakeHasher implements AttributeHasher {

    private static final String PREFIX = "$fake$";

    String algorithm = "bcrypt";
    int cost = 10;
    int memory = 64;
    int time = 3;
    int threads = 2;
    int counter;
    int hashCalls;
    int isHashedCalls;
    int isCurrentCalls;
    RuntimeException hashFailure;
    RuntimeException isHashedFailure;
    RuntimeException isCurrentFailure;
    boolean hashReturnsNull;
    String lastHashInput;

    @Override
    public String hash(String plain) {
        hashCalls++;
        lastHashInput = plain;
        if (hashFailure != null) {
            throw hashFailure;
        }
        if (hashReturnsNull) {
            return null;
        }
        counter++;
        return build(algorithm, cost, memory, time, threads, counter, plain);
    }

    @Override
    public boolean isHashed(String value) {
        isHashedCalls++;
        if (isHashedFailure != null) {
            throw isHashedFailure;
        }
        return value.startsWith(PREFIX);
    }

    @Override
    public boolean isCurrent(String hashed) {
        isCurrentCalls++;
        if (isCurrentFailure != null) {
            throw isCurrentFailure;
        }
        String[] parts = hashed.split("\\$");
        return parts[2].equals(algorithm)
                && numberOf(parts[3]) <= cost
                && numberOf(parts[4]) <= memory
                && numberOf(parts[5]) <= time
                && numberOf(parts[6]) <= threads;
    }

    /** Stands for the user's own verification: true when the hash was made from the given plain text. */
    boolean verify(String plain, String hashed) {
        String[] parts = hashed.split("\\$");
        String decoded = new String(Base64.getDecoder().decode(parts[8]), StandardCharsets.UTF_8);
        return decoded.equals(plain);
    }

    /** Builds a hash of the plain text made under other settings than this hasher's own. */
    String fakeFrom(String plain, String otherAlgorithm, int otherCost, int otherMemory, int otherTime,
            int otherThreads) {
        return build(otherAlgorithm, otherCost, otherMemory, otherTime, otherThreads, 0, plain);
    }

    private static String build(String algorithm, int cost, int memory, int time, int threads, int salt,
            String plain) {
        return PREFIX + algorithm + "$c=" + cost + "$m=" + memory + "$t=" + time + "$p=" + threads + "$" + salt
                + "$" + Base64.getEncoder().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
    }

    private static int numberOf(String part) {
        return Integer.parseInt(part.substring(part.indexOf('=') + 1));
    }
}
