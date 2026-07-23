final class SelfTestSupport {

    private SelfTestSupport() {
    }

    static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    static void assertEquals(Object expected, Object actual, String message) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(message + " Expected=" + expected + " Actual=" + actual);
        }
    }

    static void assertContains(String haystack, String needle, String message) {
        if (haystack == null || !haystack.contains(needle)) {
            throw new AssertionError(message + " Missing=" + needle);
        }
    }
}
