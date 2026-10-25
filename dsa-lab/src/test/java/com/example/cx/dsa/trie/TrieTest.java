package com.example.cx.dsa.trie;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class TrieTest {
    private Trie sample() {
        Trie t = new Trie();
        for (String w : new String[] {"VPN setup", "vpn down", "vpn", "printer jam", "password reset", "password"}) t.insert(w);
        return t;
    }

    @Test
    void insertContainsPrefix() {
        Trie t = sample();
        assertEquals(6, t.size());
        assertFalse(t.insert("VPN"));                         // duplicate after normalisation
        assertTrue(t.contains("Vpn Down"));
        assertFalse(t.contains("vp"));
        assertTrue(t.startsWith("vp"));
        assertFalse(t.startsWith("xyz"));
        assertFalse(t.insert("  "));
        assertFalse(t.insert(null));
    }

    @Test
    void prefixSearchAlphabeticalWithLimit() {
        Trie t = sample();
        assertEquals(List.of("vpn", "vpn down", "vpn setup"), t.withPrefix("VP", 10));
        assertEquals(List.of("vpn", "vpn down"), t.withPrefix("vpn", 2));
        assertEquals(List.of(), t.withPrefix("zzz", 5));
        assertEquals(List.of(), t.withPrefix("vpn", 0));
        assertEquals(6, t.withPrefix("", 100).size());
        assertEquals(3, t.countWithPrefix("vpn"));
        assertEquals(6, t.countWithPrefix(""));
        assertEquals(0, t.countWithPrefix("q"));
    }

    @Test
    void deleteKeepsPrefixWordsAndPrunesBranches() {
        Trie t = sample();
        assertTrue(t.delete("vpn"));                          // is a prefix of other words: nodes must stay
        assertFalse(t.contains("vpn"));
        assertTrue(t.contains("vpn down"));
        assertTrue(t.delete("vpn down"));
        assertTrue(t.delete("vpn setup"));
        assertFalse(t.startsWith("vpn"));                     // branch fully pruned
        assertFalse(t.delete("vpn"));
        assertEquals(3, t.size());
        assertEquals(List.of("password", "password reset", "printer jam"), t.withPrefix("p", 10));
    }
}
