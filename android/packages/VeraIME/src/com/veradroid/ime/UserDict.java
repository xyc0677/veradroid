// SPDX-License-Identifier: GPL-3.0-or-later
package com.veradroid.ime;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

/**
 * What the user typed: how often each phrase was chosen (a dictionary word gets cheaper, a phrase the dictionary
 * does not have becomes a word of its own) and which word followed which (for the suggestions after a commit).
 * Bounded: the least used, oldest entries go first.  Plain Java; the caller does the file handling.
 *
 * Text form, one entry per line:   P <TAB> ni'hao <TAB> 你好 <TAB> count <TAB> stamp      B <TAB> 你好 <TAB> 世界 <TAB> count <TAB> stamp
 */
final class UserDict {
    static final int MAX_PHRASES = 3000, MAX_PAIRS = 1500, MAX_CHARS = 12;
    /** cost of a phrase the dictionary does not have, before the bonus for its count */
    static final int USER_COST = 95;

    static final class Entry {
        final String key, text;         // phrase: syllable ids as chars / characters;   pair: previous / next text
        int count, stamp;
        int sysId = -1;                 // phrase that is a dictionary word
        Entry(String k, String t) { key = k; text = t; }

        int cost() { return Math.max(20, USER_COST - bonus(count)); }
    }

    /** how much cheaper a phrase gets after being chosen n times */
    static int bonus(int n) {
        return n <= 0 ? 0 : Math.min(60, 12 + (int) Math.round(15 * Math.log(1 + n)));
    }

    private final PinyinDict dict;
    private final HashMap<String, Entry> phrases = new HashMap<String, Entry>();
    private final HashMap<String, Entry> pairs = new HashMap<String, Entry>();
    private int clock;
    int rev;
    volatile boolean dirty;
    private boolean batching, needRebuild;

    // derived by rebuild()
    private final HashMap<String, ArrayList<Entry>> own = new HashMap<String, ArrayList<Entry>>();
    private final HashSet<String> prefixes = new HashSet<String>();
    private final HashMap<String, ArrayList<Entry>> after = new HashMap<String, ArrayList<Entry>>();
    /** dictionary words the user chose, sorted by id, and their bonus */
    int[] boostId = new int[0], boostVal = new int[0];

    UserDict(PinyinDict d) { dict = d; }

    int phraseCount() { return phrases.size(); }
    int pairCount() { return pairs.size(); }
    boolean hasOwn() { return !own.isEmpty(); }

    private static String key(int[] syls) {
        char[] c = new char[syls.length];
        for (int i = 0; i < c.length; i++) c[i] = (char) syls[i];
        return new String(c);
    }

    void beginBatch() { batching = true; }

    void endBatch() {
        batching = false;
        if (needRebuild) {
            needRebuild = false;
            rebuild();
        }
    }

    /** A phrase was committed with these syllables. */
    void learn(int[] syls, String text) {
        if (syls == null || syls.length == 0 || syls.length != text.length() || text.length() > MAX_CHARS) return;
        String k = key(syls), id = k + '\0' + text;
        Entry e = phrases.get(id);
        if (e == null) {
            e = new Entry(k, text);
            e.sysId = dict.find(syls, text);
            phrases.put(id, e);
        }
        e.count++;
        e.stamp = ++clock;
        if (phrases.size() > MAX_PHRASES) trim(phrases, MAX_PHRASES);
        dirty = true;
        rev++;
        if (batching) needRebuild = true;
        else rebuild();
    }

    /** next was committed right after prev. */
    void learnPair(String prev, String next) {
        if (prev == null || prev.length() == 0 || next.length() == 0 || prev.length() > MAX_CHARS || next.length() > MAX_CHARS) return;
        String id = prev + '\0' + next;
        Entry e = pairs.get(id);
        if (e == null) { e = new Entry(prev, next); pairs.put(id, e); }
        e.count++;
        e.stamp = ++clock;
        if (pairs.size() > MAX_PAIRS) trim(pairs, MAX_PAIRS);
        dirty = true;
        rev++;
        if (batching) needRebuild = true;
        else rebuild();
    }

    void clear() {
        phrases.clear();
        pairs.clear();
        clock = 0;
        dirty = true;
        rev++;
        needRebuild = false;
        rebuild();
    }

    private static final Comparator<Entry> KEEP_FIRST = new Comparator<Entry>() {
        @Override public int compare(Entry a, Entry b) {
            int ca = Math.min(a.count, 4), cb = Math.min(b.count, 4);
            return ca != cb ? cb - ca : b.stamp - a.stamp;
        }
    };

    /** drop the least used, oldest tenth */
    private static void trim(HashMap<String, Entry> m, int max) {
        ArrayList<Entry> all = new ArrayList<Entry>(m.values());
        Collections.sort(all, KEEP_FIRST);
        for (int i = max * 9 / 10; i < all.size(); i++) { Entry e = all.get(i); m.remove(e.key + '\0' + e.text); }
    }

    private static final Comparator<Entry> MOST_USED = new Comparator<Entry>() {
        @Override public int compare(Entry a, Entry b) { return a.count != b.count ? b.count - a.count : b.stamp - a.stamp; }
    };

    private void rebuild() {
        own.clear(); prefixes.clear(); after.clear();
        ArrayList<Entry> sys = new ArrayList<Entry>();
        for (Entry e : phrases.values()) {
            if (e.sysId >= 0) { sys.add(e); continue; }
            ArrayList<Entry> l = own.get(e.key);
            if (l == null) own.put(e.key, l = new ArrayList<Entry>(1));
            l.add(e);
            for (int i = 1; i < e.key.length(); i++) prefixes.add(e.key.substring(0, i));
        }
        for (ArrayList<Entry> l : own.values()) Collections.sort(l, MOST_USED);
        Collections.sort(sys, new Comparator<Entry>() {
            @Override public int compare(Entry a, Entry b) { return a.sysId - b.sysId; }
        });
        boostId = new int[sys.size()];
        boostVal = new int[sys.size()];
        for (int i = 0; i < boostId.length; i++) { boostId[i] = sys.get(i).sysId; boostVal[i] = bonus(sys.get(i).count); }
        for (Entry e : pairs.values()) {
            ArrayList<Entry> l = after.get(e.key);
            if (l == null) after.put(e.key, l = new ArrayList<Entry>(2));
            l.add(e);
        }
        for (ArrayList<Entry> l : after.values()) Collections.sort(l, MOST_USED);
    }

    /** the user's own phrases with exactly this key (most used first), or null */
    List<Entry> own(CharSequence key) { return own.isEmpty() ? null : own.get(key.toString()); }
    /** true when a longer own phrase starts with this key */
    boolean isPrefix(CharSequence key) { return prefixes.contains(key.toString()); }
    /** what the user typed after this text, most used first, or null */
    List<Entry> after(String prev) { return prev == null ? null : after.get(prev); }

    /** index of the first chosen dictionary word with id >= id */
    int boostLower(int id) {
        int i = Arrays.binarySearch(boostId, id);
        return i >= 0 ? i : -i - 1;
    }

    int bonusOf(int id) {
        if (boostId.length == 0) return 0;
        int i = Arrays.binarySearch(boostId, id);
        return i >= 0 ? boostVal[i] : 0;
    }

    // ---------------------------------------------------------------- text form

    void load(Reader in) throws IOException {
        BufferedReader r = new BufferedReader(in);
        String line;
        while ((line = r.readLine()) != null) {
            String[] p = line.split("\t");
            if (p.length < 5) continue;
            int count, stamp;
            try { count = Integer.parseInt(p[3]); stamp = Integer.parseInt(p[4]); } catch (NumberFormatException x) { continue; }
            if (count <= 0) continue;
            clock = Math.max(clock, stamp);
            if ("P".equals(p[0])) {
                String[] s = p[1].split("'");
                int[] syls = new int[s.length];
                boolean ok = s.length == p[2].length() && s.length <= MAX_CHARS;
                for (int i = 0; ok && i < s.length; i++) ok = (syls[i] = dict.syllable(s[i])) >= 0;
                if (!ok) continue;
                Entry e = new Entry(key(syls), p[2]);
                e.count = count; e.stamp = stamp; e.sysId = dict.find(syls, p[2]);
                phrases.put(e.key + '\0' + e.text, e);
            } else if ("B".equals(p[0]) && p[1].length() > 0 && p[2].length() > 0) {
                Entry e = new Entry(p[1], p[2]);
                e.count = count; e.stamp = stamp;
                pairs.put(e.key + '\0' + e.text, e);
            }
        }
        if (phrases.size() > MAX_PHRASES) trim(phrases, MAX_PHRASES);
        if (pairs.size() > MAX_PAIRS) trim(pairs, MAX_PAIRS);
        rebuild();
    }

    void save(Writer w) throws IOException {
        StringBuilder b = new StringBuilder();
        for (Entry e : phrases.values()) {
            b.setLength(0);
            b.append("P\t");
            for (int i = 0; i < e.key.length(); i++) { if (i > 0) b.append('\''); b.append(dict.syl[e.key.charAt(i)]); }
            b.append('\t').append(e.text).append('\t').append(e.count).append('\t').append(e.stamp).append('\n');
            w.write(b.toString());
        }
        for (Entry e : pairs.values()) {
            b.setLength(0);
            b.append("B\t").append(e.key).append('\t').append(e.text).append('\t').append(e.count).append('\t').append(e.stamp).append('\n');
            w.write(b.toString());
        }
    }
}
