package io.github.andreaacanfora.loomkv.store;

/** Redis-style glob matching for KEYS: {@code *}, {@code ?}, {@code [abc]}, {@code [^a-z]}, {@code \} escapes. */
public final class Glob {

    private Glob() {}

    public static boolean matches(byte[] pattern, byte[] text) {
        return match(pattern, 0, text, 0);
    }

    private static boolean match(byte[] p, int pi, byte[] s, int si) {
        while (pi < p.length) {
            byte c = p[pi];
            switch (c) {
                case '*' -> {
                    while (pi + 1 < p.length && p[pi + 1] == '*') {
                        pi++;
                    }
                    if (pi + 1 == p.length) {
                        return true;
                    }
                    for (int i = si; i <= s.length; i++) {
                        if (match(p, pi + 1, s, i)) {
                            return true;
                        }
                    }
                    return false;
                }
                case '?' -> {
                    if (si >= s.length) {
                        return false;
                    }
                    si++;
                    pi++;
                }
                case '[' -> {
                    if (si >= s.length) {
                        return false;
                    }
                    int end = pi + 1;
                    boolean negate = end < p.length && p[end] == '^';
                    if (negate) {
                        end++;
                    }
                    boolean found = false;
                    while (end < p.length && p[end] != ']') {
                        if (p[end] == '\\' && end + 1 < p.length) {
                            end++;
                            found |= p[end] == s[si];
                        } else if (end + 2 < p.length && p[end + 1] == '-' && p[end + 2] != ']') {
                            int lo = Math.min(p[end] & 0xff, p[end + 2] & 0xff);
                            int hi = Math.max(p[end] & 0xff, p[end + 2] & 0xff);
                            int ch = s[si] & 0xff;
                            found |= ch >= lo && ch <= hi;
                            end += 2;
                        } else {
                            found |= p[end] == s[si];
                        }
                        end++;
                    }
                    if (found == negate) {
                        return false;
                    }
                    pi = Math.min(end + 1, p.length);
                    si++;
                }
                case '\\' -> {
                    if (pi + 1 < p.length) {
                        pi++;
                    }
                    if (si >= s.length || p[pi] != s[si]) {
                        return false;
                    }
                    pi++;
                    si++;
                }
                default -> {
                    if (si >= s.length || c != s[si]) {
                        return false;
                    }
                    pi++;
                    si++;
                }
            }
        }
        return si == s.length;
    }
}
