package com.example.cx.dsa.hashing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Duplicate detection three ways.
 *
 * <p><b>Purpose / platform use case:</b> the same idea as the platform's idempotency checks: the notification consumer
 * drops an event whose {@code eventId} was already processed, and ticket creation replays the original response for a
 * repeated {@code Idempotency-Key}. (Production uses a DB unique constraint so it holds across nodes; the in-memory
 * set is the single-node analogue.) Also: detecting duplicate tickets by normalised title.
 *
 * <p><b>Algorithm:</b> (1) brute force compares every pair; (2) sort then compare neighbours; (3) hash set remembers
 * what was seen. {@link #firstDuplicate} returns the earliest element whose second occurrence appears first.
 *
 * <p><b>Complexity:</b> brute force O(n^2) time, O(1) space; sorting O(n log n) time, O(n) copy (O(1) if in place);
 * hashing O(n) expected time, O(n) space.
 *
 * <p><b>Edge cases:</b> empty/one element, all equal, null elements (hash set handles one null), very large input
 * (memory), case/whitespace normalisation for text keys.
 *
 * <p><b>Interview follow-ups:</b> detect duplicates in O(1) extra space when values are in [1,n] (index marking or
 * Floyd cycle detection)? Streams too big for memory (Bloom filter: no false negatives for "seen", tolerate false
 * positives; or external sort)? Near-duplicates (shingling / MinHash)? Distributed dedup (unique constraint, Redis SETNX)?
 */
public final class DuplicateDetection {
    private DuplicateDetection() { }

    public static <T> boolean hasDuplicateBruteForce(List<T> items) {
        for (int i = 0; i < items.size(); i++) {
            for (int j = i + 1; j < items.size(); j++) {
                if (java.util.Objects.equals(items.get(i), items.get(j))) return true;
            }
        }
        return false;
    }

    public static boolean hasDuplicateSorting(int[] items) {
        int[] copy = Arrays.copyOf(items, items.length);
        Arrays.sort(copy);
        for (int i = 1; i < copy.length; i++) if (copy[i] == copy[i - 1]) return true;
        return false;
    }

    public static <T> boolean hasDuplicateHashing(List<T> items) {
        Set<T> seen = new HashSet<>();
        for (T t : items) if (!seen.add(t)) return true;
        return false;
    }

    /** First element (scanning left to right) that has already been seen, or null when all are distinct. */
    public static <T> T firstDuplicate(List<T> items) {
        Set<T> seen = new HashSet<>();
        for (T t : items) if (!seen.add(t)) return t;
        return null;
    }

    /** All values that occur more than once, in order of their first repeat. */
    public static <T> List<T> allDuplicates(List<T> items) {
        Set<T> seen = new HashSet<>();
        Set<T> dups = new LinkedHashSet<>();
        for (T t : items) if (!seen.add(t)) dups.add(t);
        return new ArrayList<>(dups);
    }

    /** O(1)-space variant for values in [1,n] in an array of length n+1 (Floyd's tortoise and hare). */
    public static int findDuplicateInRange(int[] nums) {
        int slow = nums[0], fast = nums[0];
        do {
            slow = nums[slow];
            fast = nums[nums[fast]];
        } while (slow != fast);
        slow = nums[0];
        while (slow != fast) {
            slow = nums[slow];
            fast = nums[fast];
        }
        return slow;
    }

    /** Ticket-title near duplicate key: lower-case, collapsed whitespace. */
    public static String normalizeTitle(String title) {
        return title == null ? "" : title.trim().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
    }
}
