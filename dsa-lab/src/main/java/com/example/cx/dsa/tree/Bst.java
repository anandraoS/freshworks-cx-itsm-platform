package com.example.cx.dsa.tree;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Unbalanced binary search tree with insert, search, delete and the four traversals.
 *
 * <p><b>Purpose / platform use case:</b> ordered in-memory index of tickets by due date or number for range queries
 * ("tickets due between A and B") and ordered iteration. In production an ordered index lives in the database (B-tree) or
 * {@code TreeMap} (red-black tree); this class shows the structure those build on.
 *
 * <p><b>Algorithm:</b> invariant: left subtree &lt; node &lt; right subtree. Delete handles three cases: leaf, one child, two
 * children (replace with in-order successor). In-order traversal yields sorted output; level-order uses a queue; the
 * iterative in-order uses an explicit stack to avoid deep recursion.
 *
 * <p><b>Complexity:</b> search/insert/delete O(h): O(log n) when balanced, O(n) when degenerate (sorted insertions).
 * Traversals O(n) time, O(h) space (O(w) for level order).
 *
 * <p><b>Edge cases:</b> empty tree, duplicates (ignored here), delete root / missing key, skewed tree from sorted input,
 * deep recursion (use the iterative traversal).
 *
 * <p><b>Brute force vs optimized:</b> scanning a list is O(n) per query; a balanced BST is O(log n). Self-balancing (AVL,
 * red-black) fixes the worst case.
 *
 * <p><b>Interview follow-ups:</b> validate a BST (min/max bounds, not just parent comparison)? Lowest common ancestor?
 * k-th smallest (augment with subtree sizes)? Morris traversal in O(1) space? Why databases use B-trees instead of BSTs?
 */
public class Bst<T extends Comparable<T>> {
    private static final class Node<T> {
        T value;
        Node<T> left, right;
        Node(T value) { this.value = value; }
    }

    private Node<T> root;
    private int size;

    public int size() { return size; }
    public boolean isEmpty() { return size == 0; }

    public boolean insert(T v) {
        if (v == null) throw new NullPointerException();
        if (root == null) {
            root = new Node<>(v);
            size++;
            return true;
        }
        Node<T> cur = root;
        while (true) {
            int c = v.compareTo(cur.value);
            if (c == 0) return false;
            Node<T> next = c < 0 ? cur.left : cur.right;
            if (next == null) {
                if (c < 0) cur.left = new Node<>(v); else cur.right = new Node<>(v);
                size++;
                return true;
            }
            cur = next;
        }
    }

    public boolean contains(T v) {
        Node<T> cur = root;
        while (cur != null) {
            int c = v.compareTo(cur.value);
            if (c == 0) return true;
            cur = c < 0 ? cur.left : cur.right;
        }
        return false;
    }

    public boolean delete(T v) {
        int before = size;
        root = delete(root, v);
        return size < before;
    }

    private Node<T> delete(Node<T> n, T v) {
        if (n == null) return null;
        int c = v.compareTo(n.value);
        if (c < 0) n.left = delete(n.left, v);
        else if (c > 0) n.right = delete(n.right, v);
        else {
            size--;
            if (n.left == null) return n.right;
            if (n.right == null) return n.left;
            Node<T> succ = n.right;
            while (succ.left != null) succ = succ.left;
            n.value = succ.value;
            size++;                                   // the recursive delete below decrements again
            n.right = delete(n.right, succ.value);
        }
        return n;
    }

    public T min() {
        if (root == null) return null;
        Node<T> n = root;
        while (n.left != null) n = n.left;
        return n.value;
    }

    public T max() {
        if (root == null) return null;
        Node<T> n = root;
        while (n.right != null) n = n.right;
        return n.value;
    }

    public int height() { return height(root); }

    private int height(Node<T> n) { return n == null ? 0 : 1 + Math.max(height(n.left), height(n.right)); }

    public List<T> inOrder() {
        List<T> out = new ArrayList<>(size);
        Deque<Node<T>> stack = new ArrayDeque<>();
        Node<T> cur = root;
        while (cur != null || !stack.isEmpty()) {
            while (cur != null) { stack.push(cur); cur = cur.left; }
            cur = stack.pop();
            out.add(cur.value);
            cur = cur.right;
        }
        return out;
    }

    public List<T> preOrder() {
        List<T> out = new ArrayList<>(size);
        pre(root, out);
        return out;
    }

    private void pre(Node<T> n, List<T> out) {
        if (n == null) return;
        out.add(n.value);
        pre(n.left, out);
        pre(n.right, out);
    }

    public List<T> postOrder() {
        List<T> out = new ArrayList<>(size);
        post(root, out);
        return out;
    }

    private void post(Node<T> n, List<T> out) {
        if (n == null) return;
        post(n.left, out);
        post(n.right, out);
        out.add(n.value);
    }

    public List<T> levelOrder() {
        List<T> out = new ArrayList<>(size);
        if (root == null) return out;
        Deque<Node<T>> q = new ArrayDeque<>();
        q.add(root);
        while (!q.isEmpty()) {
            Node<T> n = q.poll();
            out.add(n.value);
            if (n.left != null) q.add(n.left);
            if (n.right != null) q.add(n.right);
        }
        return out;
    }

    /** Values in [lo, hi] in ascending order, pruning subtrees that cannot contain results. */
    public List<T> range(T lo, T hi) {
        List<T> out = new ArrayList<>();
        range(root, lo, hi, out);
        return out;
    }

    private void range(Node<T> n, T lo, T hi, List<T> out) {
        if (n == null) return;
        if (lo.compareTo(n.value) < 0) range(n.left, lo, hi, out);
        if (lo.compareTo(n.value) <= 0 && hi.compareTo(n.value) >= 0) out.add(n.value);
        if (hi.compareTo(n.value) > 0) range(n.right, lo, hi, out);
    }
}
