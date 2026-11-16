package kr.opencourse.godaijishou;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class UpdateCheckTest {

    @Test
    public void patchReleaseIsNewer() {
        assertTrue(UpdateCheck.isNewer("2.1.7", "2.1.6"));
    }

    @Test
    public void sameOrOlderIsNotNewer() {
        assertFalse(UpdateCheck.isNewer("2.1.6", "2.1.6"));
        assertFalse(UpdateCheck.isNewer("2.1.5", "2.1.6"));
    }

    @Test
    public void comparesNumbersNotText() {
        assertTrue(UpdateCheck.isNewer("2.1.10", "2.1.9"));
        assertTrue(UpdateCheck.isNewer("2.2", "2.1.9"));
    }
}
