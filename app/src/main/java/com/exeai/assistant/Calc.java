package com.exeai.assistant;

import java.math.BigDecimal;

/**
 * Offline arithmetic for plain expressions such as "2+2", "(3 * 4) - 1" or "10 / 4".
 * Supports + - * / x × ÷, parentheses and decimals. Returns null for anything else.
 */
public final class Calc {
    private final String s;
    private int pos = 0;

    private Calc(String s) {
        this.s = s;
    }

    /** Evaluates the expression, or returns null when it is not a valid arithmetic expression. */
    public static Double eval(String expression) {
        try {
            String cleaned = expression.trim()
                    .replace('x', '*').replace('×', '*')
                    .replace('÷', '/').replace(',', '.');
            Calc c = new Calc(cleaned);
            double v = c.parseExpr();
            c.skipSpaces();
            if (c.pos != c.s.length()) return null;
            if (Double.isNaN(v) || Double.isInfinite(v)) return null;
            return v;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Formats 4.0 as "4" and 2.5 as "2.5". */
    public static String format(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return String.valueOf((long) v);
        return BigDecimal.valueOf(v).round(new java.math.MathContext(12)).stripTrailingZeros().toPlainString();
    }

    private double parseExpr() {
        double v = parseTerm();
        while (true) {
            skipSpaces();
            if (peek('+')) { pos++; v += parseTerm(); }
            else if (peek('-')) { pos++; v -= parseTerm(); }
            else return v;
        }
    }

    private double parseTerm() {
        double v = parseFactor();
        while (true) {
            skipSpaces();
            if (peek('*')) { pos++; v *= parseFactor(); }
            else if (peek('/')) {
                pos++;
                double d = parseFactor();
                if (d == 0) throw new ArithmeticException("division by zero");
                v /= d;
            } else return v;
        }
    }

    private double parseFactor() {
        skipSpaces();
        if (peek('-')) { pos++; return -parseFactor(); }
        if (peek('+')) { pos++; return parseFactor(); }
        if (peek('(')) {
            pos++;
            double v = parseExpr();
            skipSpaces();
            if (!peek(')')) throw new IllegalArgumentException("missing )");
            pos++;
            return v;
        }
        int start = pos;
        while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.')) pos++;
        if (start == pos) throw new IllegalArgumentException("number expected");
        return Double.parseDouble(s.substring(start, pos));
    }

    private boolean peek(char c) {
        return pos < s.length() && s.charAt(pos) == c;
    }

    private void skipSpaces() {
        while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
    }
}
