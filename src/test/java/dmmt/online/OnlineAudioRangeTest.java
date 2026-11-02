package dmmt.online;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class OnlineAudioRangeTest {

    @Test
    void noHeaderMeansNoRange() {
        assertNull(OnlineSessionServer.parseRange(null, 100));
    }

    @Test
    void parsesStartEndAndSuffix() {
        assertArrayEquals(new long[]{0, 9}, OnlineSessionServer.parseRange("bytes=0-9", 100));
        assertArrayEquals(new long[]{50, 99}, OnlineSessionServer.parseRange("bytes=50-", 100));
        assertArrayEquals(new long[]{90, 99}, OnlineSessionServer.parseRange("bytes=-10", 100));
    }

    @Test
    void unsatisfiableRange() {
        assertArrayEquals(new long[]{-1, -1}, OnlineSessionServer.parseRange("bytes=200-300", 100));
    }
}
