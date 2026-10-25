package com.example.cx.dsa.trie;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Prefix tree for type-ahead search (knowledge-base titles, ticket tags, agent names).
 *
 * <p><b>Purpose / platform use case:</b> instant suggestions while an agent types in the KB search box or picks a tag. The
 * platform's KB search uses database full-text search (PostgreSQL GIN) and an in-memory scan locally; a trie is the classic
 * structure for prefix autocomplete and is educational here.
 *
 * <p><b>Algorithm:</b> each node maps a character to a child; a flag marks the end of a stored word. Insert walks/creates nodes;
 * prefix search walks to the prefix node, then collects words below it by DFS. Children are in a {@link TreeMap} so results come
 * out in alphabetical order. Input is normalised to lower case.
 *
 * <p><b>Complexity:</b> insert/search/startsWith O(L) for word length L (independent of the number of words); collecting results is
 * O(L + size of the subtree visited); space O(total characters) worst case.
 *
 * <p><b>Edge cases:</b> empty prefix (returns everything up to the limit), prefix that is itself a word, duplicates, word that
 * is a prefix of another, non-ASCII characters (map handles any char), null/blank input, result limit, delete leaving dangling
 * nodes (pruned here).
 *
 * <p><b>Brute force vs optimized:</b> scanning every title with {@code startsWith} is O(N*L); the trie is O(L + results).
 *
 * <p><b>Interview follow-ups:</b> memory cost (compressed/radix trie, DAWG)? Rank suggestions by popularity (store counts, top-K
 * per node)? Fuzzy matching (edit distance)? Why a database index or inverted index beats a trie for full-text search?
 */
public class Trie {
    private static final class Node {
        final Map<Character, Node> children = new TreeMap<>();
        boolean word;
        int count;                         // words passing through this node
    }

    private final Node root = new Node();
    private int size;

    private static String norm(String s) { return s == null ? "" : s.trim().toLowerCase(Locale.ROOT); }

    public boolean insert(String word) {
        String w = norm(word);
        if (w.isEmpty()) return false;
        if (contains(w)) return false;
        Node n = root;
        for (char c : w.toCharArray()) {
            n = n.children.computeIfAbsent(c, k -> new Node());
            n.count++;
        }
        n.word = true;
        size++;
        return true;
    }

    public boolean contains(String word) {
        Node n = find(norm(word));
        return n != null && n.word;
    }

    public boolean startsWith(String prefix) { return find(norm(prefix)) != null; }

    /** Up to {@code limit} words with the prefix, alphabetical. */
    public List<String> withPrefix(String prefix, int limit) {
        String p = norm(prefix);
        List<String> out = new ArrayList<>();
        Node n = find(p);
        if (n != null && limit > 0) collect(n, new StringBuilder(p), out, limit);
        return out;
    }

    public int countWithPrefix(String prefix) {
        String p = norm(prefix);
        if (p.isEmpty()) return size;
        Node n = find(p);
        return n == null ? 0 : n.count;
    }

    public boolean delete(String word) {
        String w = norm(word);
        if (!contains(w)) return false;
        Node n = root;
        Node parent;
        for (char c : w.toCharArray()) {
            parent = n;
            n = n.children.get(c);
            n.count--;
            if (n.count == 0) {                       // nothing else passes through: prune the whole branch
                parent.children.remove(c);
                size--;
                return true;
            }
        }
        n.word = false;
        size--;
        return true;
    }

    public int size() { return size; }

    private Node find(String s) {
        Node n = root;
        for (char c : s.toCharArray()) {
            n = n.children.get(c);
            if (n == null) return null;
        }
        return n;
    }

    private void collect(Node n, StringBuilder sb, List<String> out, int limit) {
        if (out.size() >= limit) return;
        if (n.word) out.add(sb.toString());
        for (Map.Entry<Character, Node> e : n.children.entrySet()) {
            if (out.size() >= limit) return;
            sb.append(e.getKey());
            collect(e.getValue(), sb, out, limit);
            sb.setLength(sb.length() - 1);
        }
    }
}
