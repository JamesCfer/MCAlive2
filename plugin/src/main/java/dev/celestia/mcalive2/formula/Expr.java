package dev.celestia.mcalive2.formula;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Tiny numeric expression evaluator for formula step arguments (see {@link Formula}
 * conventions used by {@code FormulaActuators}). Bukkit-free and unit tested directly.
 *
 * <pre>
 * expr    := term (('+'|'-') term)*
 * term    := factor (('*'|'/') factor)*
 * factor  := ('-'|'+') factor | primary
 * primary := NUMBER | IDENT | IDENT '(' expr (',' expr)* ')' | '(' expr ')'
 * </pre>
 *
 * Supported functions: {@code rand(a,b)} uniform double in [min(a,b), max(a,b)),
 * {@code randint(a,b)} uniform int inclusive of both bounds (order-independent),
 * {@code floor(x)}.
 */
public final class Expr {

    private Expr() {}

    /** Evaluate a bare numeric expression (no surrounding {@code "${" "}"}). */
    public static double eval(String expression, Map<String, Double> vars) {
        return new Parser(expression, vars).parseAll();
    }

    /**
     * Resolve one raw formula-step argument value: JSON strings of the form
     * {@code "${...}"} are evaluated as numeric expressions and returned as a numeric
     * {@link JsonElement}; every other value (plain strings, numbers, booleans, null)
     * passes through untouched.
     */
    public static JsonElement resolveValue(JsonElement raw, Map<String, Double> vars) {
        if (raw != null && raw.isJsonPrimitive() && raw.getAsJsonPrimitive().isString()) {
            String s = raw.getAsString();
            if (s.startsWith("${") && s.endsWith("}")) {
                double v = eval(s.substring(2, s.length() - 1), vars);
                return new JsonPrimitive(v);
            }
        }
        return raw;
    }

    private static final class Parser {
        private final String src;
        private final Map<String, Double> vars;
        private int pos = 0;

        Parser(String src, Map<String, Double> vars) {
            this.src = src;
            this.vars = vars;
        }

        double parseAll() {
            if (src == null || src.isBlank()) {
                throw new IllegalArgumentException("empty expression");
            }
            double v = parseExpr();
            skipWs();
            if (pos != src.length()) {
                throw new IllegalArgumentException("malformed expression at position " + pos + ": " + src);
            }
            return v;
        }

        double parseExpr() {
            double v = parseTerm();
            while (true) {
                skipWs();
                if (peek('+')) { pos++; v += parseTerm(); }
                else if (peek('-')) { pos++; v -= parseTerm(); }
                else break;
            }
            return v;
        }

        double parseTerm() {
            double v = parseFactor();
            while (true) {
                skipWs();
                if (peek('*')) { pos++; v *= parseFactor(); }
                else if (peek('/')) {
                    pos++;
                    double d = parseFactor();
                    if (d == 0) throw new IllegalArgumentException("division by zero");
                    v /= d;
                } else break;
            }
            return v;
        }

        double parseFactor() {
            skipWs();
            if (peek('-')) { pos++; return -parseFactor(); }
            if (peek('+')) { pos++; return parseFactor(); }
            return parsePrimary();
        }

        double parsePrimary() {
            skipWs();
            if (pos >= src.length()) throw new IllegalArgumentException("unexpected end of expression: " + src);
            char c = src.charAt(pos);
            if (c == '(') {
                pos++;
                double v = parseExpr();
                skipWs();
                expect(')');
                return v;
            }
            if (Character.isDigit(c) || c == '.') {
                return parseNumber();
            }
            if (Character.isLetter(c) || c == '_') {
                String ident = parseIdent();
                skipWs();
                if (pos < src.length() && src.charAt(pos) == '(') {
                    pos++;
                    List<Double> args = new ArrayList<>();
                    skipWs();
                    if (pos < src.length() && src.charAt(pos) != ')') {
                        args.add(parseExpr());
                        skipWs();
                        while (pos < src.length() && src.charAt(pos) == ',') {
                            pos++;
                            args.add(parseExpr());
                            skipWs();
                        }
                    }
                    expect(')');
                    return callFunction(ident, args);
                }
                Double v = vars == null ? null : vars.get(ident);
                if (v == null) throw new IllegalArgumentException("unknown variable: " + ident);
                return v;
            }
            throw new IllegalArgumentException("malformed expression at position " + pos + ": " + src);
        }

        double callFunction(String name, List<Double> args) {
            return switch (name) {
                case "rand" -> {
                    require(args, 2, "rand");
                    double a = args.get(0), b = args.get(1);
                    double lo = Math.min(a, b), hi = Math.max(a, b);
                    yield lo + ThreadLocalRandom.current().nextDouble() * (hi - lo);
                }
                case "randint" -> {
                    require(args, 2, "randint");
                    int a = (int) Math.round(args.get(0));
                    int b = (int) Math.round(args.get(1));
                    int lo = Math.min(a, b), hi = Math.max(a, b);
                    yield lo + ThreadLocalRandom.current().nextInt(hi - lo + 1);
                }
                case "floor" -> {
                    require(args, 1, "floor");
                    yield Math.floor(args.get(0));
                }
                default -> throw new IllegalArgumentException("unknown function: " + name);
            };
        }

        void require(List<Double> args, int n, String fn) {
            if (args.size() != n) throw new IllegalArgumentException(fn + " requires " + n + " argument(s)");
        }

        double parseNumber() {
            int start = pos;
            boolean sawDot = false;
            while (pos < src.length() && (Character.isDigit(src.charAt(pos)) || (!sawDot && src.charAt(pos) == '.'))) {
                if (src.charAt(pos) == '.') sawDot = true;
                pos++;
            }
            String num = src.substring(start, pos);
            try {
                return Double.parseDouble(num);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("malformed number: " + num);
            }
        }

        String parseIdent() {
            int start = pos;
            while (pos < src.length() && (Character.isLetterOrDigit(src.charAt(pos)) || src.charAt(pos) == '_')) pos++;
            return src.substring(start, pos);
        }

        void skipWs() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++;
        }

        boolean peek(char c) {
            return pos < src.length() && src.charAt(pos) == c;
        }

        void expect(char c) {
            if (pos >= src.length() || src.charAt(pos) != c) {
                throw new IllegalArgumentException("expected '" + c + "' at position " + pos + ": " + src);
            }
            pos++;
        }
    }
}
