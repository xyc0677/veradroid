// SPDX-License-Identifier: GPL-3.0-or-later
package com.veradroid.ime;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The phrase dictionary (assets/pinyin.dat, written by tools/ime/gen_pinyin_dict.py), read in place: the buffer is
 * a read-only mapping of the file inside the APK, so nothing but the syllable names is copied to the heap.
 * Plain Java (no Android classes): the same code runs in the JVM tests.
 *
 * A trie over syllable ids; a node at depth L holds the words of L characters with exactly those syllables, cheapest
 * first.  Costs are -ln(p) * 10.  Word ids are positions in the word table; the id {@link #start} is "start of sentence".
 */
final class PinyinDict {
    static final int SCALE = 10;

    private final ByteBuffer b;
    final int nSyl, nNodes, nWords, maxLen, nBiA, nBi;
    private final int oWords, oBiA, oBiOff, oBi;
    /** the trie index, copied out of the mapping (bulk copies): array reads are much cheaper than buffer reads,
     *  above all in the interpreter (legacy models run the IME without ahead-of-time compiled code) */
    private final char[] nodeSyl;
    private final int[] nodeChild, nodeWord;
    /** syllable id -> letters, sorted */
    final char[][] syl;
    private final int[] lenFirst, lenOff;
    /** word id of "start of sentence" (context only) */
    final int start;

    private static void checkRange(int off, long len, int cap, String what) {
        if (off < 64 || len < 0 || off + len > cap) throw new IllegalArgumentException("bad " + what + " range");
    }

    PinyinDict(ByteBuffer buf) {
        b = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        int cap = b.capacity();
        if (cap < 64 || b.getInt(0) != 0x31595056 || b.getInt(4) != 1) throw new IllegalArgumentException("not a VPY1 dictionary");
        nSyl = b.getInt(8); nNodes = b.getInt(12); nWords = b.getInt(16); maxLen = b.getInt(20);
        nBiA = b.getInt(24); nBi = b.getInt(28);
        if (nSyl <= 0 || nSyl > 65535 || nNodes <= 0 || nNodes > (cap >>> 1)
                || nWords < 0 || maxLen <= 0 || maxLen > 64 || nBiA < 0 || nBi < 0) {
            throw new IllegalArgumentException("invalid VPY1 header counts");
        }
        int oSyl = b.getInt(32);
        int oNodeSyl = b.getInt(36), oNodeChild = b.getInt(40), oNodeWord = b.getInt(44);
        int oLen = b.getInt(48);
        oWords = b.getInt(52); oBiA = b.getInt(56); oBiOff = oBiA + 4 * nBiA; oBi = b.getInt(60);
        checkRange(oSyl, 8L * nSyl, cap, "syl");
        checkRange(oNodeSyl, 2L * nNodes, cap, "nodeSyl");
        checkRange(oNodeChild, 4L * (nNodes + 1L), cap, "nodeChild");
        checkRange(oNodeWord, 4L * (nNodes + 1L), cap, "nodeWord");
        checkRange(oLen, 8L * (maxLen + 2L), cap, "len");
        checkRange(oWords, 0L, cap, "words");
        checkRange(oBiA, 4L * nBiA + 4L * (nBiA + 1L), cap, "biA");
        checkRange(oBi, 4L * nBi, cap, "bi");
        nodeSyl = new char[nNodes];
        nodeChild = new int[nNodes + 1];
        nodeWord = new int[nNodes + 1];
        ByteBuffer v = b.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        v.position(oNodeSyl); v.slice().order(ByteOrder.LITTLE_ENDIAN).asCharBuffer().get(nodeSyl);
        v.position(oNodeChild); v.slice().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(nodeChild);
        v.position(oNodeWord); v.slice().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(nodeWord);
        if (nodeChild[0] < 0 || nodeChild[nNodes] > nNodes || nodeWord[0] < 0 || nodeWord[nNodes] > nWords) {
            throw new IllegalArgumentException("invalid VPY1 trie bounds");
        }
        syl = new char[nSyl][];
        for (int i = 0; i < nSyl; i++) {
            int n = 0;
            while (n < 8 && b.get(oSyl + 8 * i + n) != 0) n++;
            syl[i] = new char[n];
            for (int k = 0; k < n; k++) syl[i][k] = (char) b.get(oSyl + 8 * i + k);
        }
        lenFirst = new int[maxLen + 2];
        lenOff = new int[maxLen + 2];
        for (int i = 0; i < maxLen + 2; i++) { lenFirst[i] = b.getInt(oLen + 8 * i); lenOff[i] = b.getInt(oLen + 8 * i + 4); }
        if (lenFirst[1] < 0 || lenFirst[maxLen + 1] > nWords) throw new IllegalArgumentException("invalid VPY1 len table");
        for (int l = 1; l <= maxLen; l++) {
            if (lenFirst[l] < 0 || lenFirst[l] > lenFirst[l + 1] || lenOff[l] < 0) {
                throw new IllegalArgumentException("invalid VPY1 len entry");
            }
            long end = oWords + (long) lenOff[l] + (long) (lenFirst[l + 1] - lenFirst[l]) * (2L * l + 3L);
            if (end > cap) throw new IllegalArgumentException("VPY1 word table truncated");
        }
        start = nWords;
    }

    // ---------------------------------------------------------------- syllables

    /** id of the syllable s[from, from+len), or -1 */
    int syllable(char[] s, int from, int len) {
        int lo = 0, hi = nSyl - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1, c = compare(syl[mid], s, from, len);
            if (c == 0) return mid;
            if (c < 0) lo = mid + 1; else hi = mid - 1;
        }
        return -1;
    }

    /** first syllable id that is >= the prefix s[from, from+len) */
    int syllableLower(char[] s, int from, int len) {
        int lo = 0, hi = nSyl;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (compare(syl[mid], s, from, len) < 0) lo = mid + 1; else hi = mid;
        }
        return lo;
    }

    /** one past the last syllable id that starts with s[from, from+len) */
    int syllableUpper(char[] s, int from, int len) {
        int i = syllableLower(s, from, len);
        while (i < nSyl && startsWith(syl[i], s, from, len)) i++;
        return i;
    }

    private static boolean startsWith(char[] a, char[] s, int from, int len) {
        if (a.length < len) return false;
        for (int i = 0; i < len; i++) if (a[i] != s[from + i]) return false;
        return true;
    }

    private static int compare(char[] a, char[] s, int from, int len) {
        int n = Math.min(a.length, len);
        for (int i = 0; i < n; i++) { int d = a[i] - s[from + i]; if (d != 0) return d; }
        return a.length - len;
    }

    int syllable(String s) { return syllable(s.toCharArray(), 0, s.length()); }

    // ---------------------------------------------------------------- trie

    int nodeSyl(int n) { return nodeSyl[n]; }
    int childStart(int n) { return nodeChild[n]; }
    int wordStart(int n) { return nodeWord[n]; }

    /** first child of node whose syllable id is >= s (may be the end of the children) */
    int childLower(int node, int s) {
        int lo = nodeChild[node], hi = nodeChild[node + 1];
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (nodeSyl[mid] < s) lo = mid + 1; else hi = mid;
        }
        return lo;
    }

    int child(int node, int s) {
        int c = childLower(node, s);
        return c < childStart(node + 1) && nodeSyl(c) == s ? c : -1;
    }

    int parent(int node) {
        int lo = 0, hi = nNodes - 1;            // last p with childStart(p) <= node
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (childStart(mid) <= node) lo = mid; else hi = mid - 1;
        }
        return lo;
    }

    /** node of a syllable sequence, or -1 */
    int node(int[] syls) {
        int n = 0;
        for (int i = 0; i < syls.length && n >= 0; i++) n = child(n, syls[i]);
        return n;
    }

    // ---------------------------------------------------------------- words

    int wordLen(int id) {
        int l = 1;
        while (l < maxLen && id >= lenFirst[l + 1]) l++;
        return l;
    }

    private int rec(int id, int len) { return oWords + lenOff[len] + (id - lenFirst[len]) * (2 * len + 3); }

    String text(int id) {
        int len = wordLen(id), o = rec(id, len);
        char[] c = new char[len];
        for (int i = 0; i < len; i++) c[i] = b.getChar(o + 2 * i);
        return new String(c);
    }

    int cost(int id, int len) { return b.get(rec(id, len) + 2 * len) & 0xff; }
    int cost(int id) { return cost(id, wordLen(id)); }

    /** backoff cost of a context word: added when the next word is not one of its listed followers */
    int backoff(int id) {
        if (id < 0 || id >= nWords) return 0;
        int len = wordLen(id);
        return b.get(rec(id, len) + 2 * len + 1) & 0xff;
    }

    /**
     * The word whose followers apply after word id: the last word of its chain (你好 -> 好), or id itself.
     * -1 when that word is not in the dictionary.
     */
    int context(int id) {
        if (id < 0 || id >= nWords) return id;
        int len = wordLen(id), o = rec(id, len), tail = b.get(o + 2 * len + 2) & 0xff;
        if (tail == 0 || tail >= len) return id;
        int[] p = path(nodeOf(id));
        if (p.length < len) return id;
        int[] syls = new int[tail];
        char[] c = new char[tail];
        for (int i = 0; i < tail; i++) { syls[i] = p[len - tail + i]; c[i] = b.getChar(o + 2 * (len - tail + i)); }
        return find(syls, new String(c));
    }

    /** the node that holds a word (its syllables are the path to it) */
    int nodeOf(int id) {
        int lo = 0, hi = nNodes - 1;            // last node with wordStart(n) <= id
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (wordStart(mid) <= id) lo = mid; else hi = mid - 1;
        }
        return lo;
    }

    /** syllable ids of a node, root first */
    int[] path(int node) {
        if (node <= 0 || node >= nNodes) return new int[0];
        int limit = Math.min(maxLen, nNodes);
        int[] rev = new int[limit];
        int d = 0;
        for (int n = node; n > 0; ) {
            if (d >= limit) return new int[0];
            rev[d++] = nodeSyl(n);
            int p = parent(n);
            if (p < 0 || p >= n) return new int[0];
            n = p;
        }
        int[] res = new int[d];
        for (int i = 0; i < d; i++) res[i] = rev[d - 1 - i];
        return res;
    }

    /** id of the word with these syllables and this text, or -1 */
    int find(int[] syls, String text) {
        int n = node(syls);
        if (n < 0 || text.length() != syls.length) return -1;
        for (int id = wordStart(n), e = wordStart(n + 1); id < e; id++) {
            int o = rec(id, syls.length), i = 0;
            while (i < syls.length && b.getChar(o + 2 * i) == text.charAt(i)) i++;
            if (i == syls.length) return id;
        }
        return -1;
    }

    // ---------------------------------------------------------------- bigrams

    /** row of a context word in the bigram table, or -1 */
    int row(int a) {
        if (a < 0) return -1;
        int lo = 0, hi = nBiA - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1, v = b.getInt(oBiA + 4 * mid);
            if (v == a) return mid;
            if (v < a) lo = mid + 1; else hi = mid - 1;
        }
        return -1;
    }

    int rowStart(int row) { return b.getInt(oBiOff + 4 * row); }
    int rowEnd(int row) { return b.getInt(oBiOff + 4 * row + 4); }
    /** entry i of the bigram table: follower id << 8 | cost */
    int entry(int i) { return b.getInt(oBi + 4 * i); }

    /** first entry in [from, to) whose follower id is >= id */
    int entryLower(int from, int to, int id) {
        while (from < to) {
            int mid = (from + to) >>> 1;
            if ((entry(mid) >>> 8) < id) from = mid + 1; else to = mid;
        }
        return from;
    }

    /** cost of word w after the context row, or -1 when it is not listed */
    int follow(int row, int w) {
        if (row < 0) return -1;
        int e = rowEnd(row), i = entryLower(rowStart(row), e, w);
        if (i < e) { int v = entry(i); if ((v >>> 8) == w) return v & 0xff; }
        return -1;
    }
}
