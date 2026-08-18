package dev.celestia.mcalive2.formula;

import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ExprTest {

    private static Map<String, Double> vars(Object... kv) {
        java.util.HashMap<String, Double> m = new java.util.HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], ((Number) kv[i + 1]).doubleValue());
        }
        return m;
    }

    @Test
    void basicArithmetic() {
        assertEquals(5.0, Expr.eval("2 + 3", Map.of()));
        assertEquals(-1.0, Expr.eval("2 - 3", Map.of()));
        assertEquals(6.0, Expr.eval("2 * 3", Map.of()));
        assertEquals(2.0, Expr.eval("6 / 3", Map.of()));
    }

    @Test
    void precedenceAndParens() {
        assertEquals(14.0, Expr.eval("2 + 3 * 4", Map.of()));
        assertEquals(20.0, Expr.eval("(2 + 3) * 4", Map.of()));
        assertEquals(1.0, Expr.eval("10 - 3 * 3", Map.of()));
        assertEquals(2.5, Expr.eval("(1 + 4) / 2", Map.of()));
    }

    @Test
    void unaryMinusAndPlus() {
        assertEquals(-5.0, Expr.eval("-5", Map.of()));
        assertEquals(5.0, Expr.eval("+5", Map.of()));
        assertEquals(-1.0, Expr.eval("-(2 + 3) + 4", Map.of()));
        assertEquals(-8.0, Expr.eval("2 * -4", Map.of()));
    }

    @Test
    void variables() {
        assertEquals(15.0, Expr.eval("cx + r", vars("cx", 10, "r", 5)));
        assertEquals(-5.0, Expr.eval("cx - r", vars("cx", 5, "r", 10)));
    }

    @Test
    void unknownVariableErrors() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> Expr.eval("cx + 1", Map.of()));
        assertTrue(ex.getMessage().contains("cx"));
    }

    @Test
    void malformedExpressionErrors() {
        assertThrows(IllegalArgumentException.class, () -> Expr.eval("2 + ", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> Expr.eval("2 + )", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> Expr.eval("(2 + 3", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> Expr.eval("2 3", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> Expr.eval("", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> Expr.eval("   ", Map.of()));
    }

    @Test
    void divisionByZeroErrors() {
        assertThrows(IllegalArgumentException.class, () -> Expr.eval("1 / 0", Map.of()));
    }

    @Test
    void floorFunction() {
        assertEquals(2.0, Expr.eval("floor(2.9)", Map.of()));
        assertEquals(-3.0, Expr.eval("floor(-2.1)", Map.of()));
    }

    @Test
    void randWithinBounds() {
        for (int i = 0; i < 500; i++) {
            double v = Expr.eval("rand(-3, 3)", Map.of());
            assertTrue(v >= -3.0 && v < 3.0, "rand out of bounds: " + v);
        }
        // order-independent bounds
        for (int i = 0; i < 200; i++) {
            double v = Expr.eval("rand(5, 2)", Map.of());
            assertTrue(v >= 2.0 && v < 5.0, "rand out of bounds: " + v);
        }
    }

    @Test
    void randintWithinInclusiveBounds() {
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (int i = 0; i < 1000; i++) {
            double v = Expr.eval("randint(1, 3)", Map.of());
            assertEquals(v, Math.floor(v), "randint should be an integer value");
            int iv = (int) v;
            assertTrue(iv >= 1 && iv <= 3, "randint out of bounds: " + iv);
            seen.add(iv);
        }
        assertEquals(java.util.Set.of(1, 2, 3), seen, "randint should be able to hit every value in range");
    }

    @Test
    void functionsWithWrongArgCountError() {
        assertThrows(IllegalArgumentException.class, () -> Expr.eval("rand(1)", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> Expr.eval("floor(1,2)", Map.of()));
    }

    @Test
    void unknownFunctionErrors() {
        assertThrows(IllegalArgumentException.class, () -> Expr.eval("nope(1)", Map.of()));
    }

    @Test
    void combinedExpressionWithVariablesAndFunctions() {
        // "${cx + rand(-r, r)}" style, deterministically checkable via bounds
        Map<String, Double> v = vars("cx", 100, "r", 4);
        for (int i = 0; i < 200; i++) {
            double result = Expr.eval("cx + rand(-r, r)", v);
            assertTrue(result >= 96.0 && result < 104.0, "out of expected range: " + result);
        }
    }

    @Test
    void resolveValueEvaluatesDollarBraceExpressions() {
        var resolved = Expr.resolveValue(new JsonPrimitive("${2 + 3}"), Map.of());
        assertTrue(resolved.isJsonPrimitive());
        assertEquals(5.0, resolved.getAsDouble());
    }

    @Test
    void resolveValuePassesThroughPlainValues() {
        assertEquals("hello", Expr.resolveValue(new JsonPrimitive("hello"), Map.of()).getAsString());
        assertEquals(42, Expr.resolveValue(new JsonPrimitive(42), Map.of()).getAsInt());
        assertTrue(Expr.resolveValue(new JsonPrimitive(true), Map.of()).getAsBoolean());
    }

    @Test
    void resolveValueUsesVariablesFromContext() {
        var resolved = Expr.resolveValue(new JsonPrimitive("${cx + rand(-0, 0)}"), vars("cx", 42));
        assertEquals(42.0, resolved.getAsDouble());
    }
}
