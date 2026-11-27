package com.hu.launcher;

/**
 * Turns a stream of GPS fixes into distance driven. No Android types, so it can be tested on a PC.
 * Distance is measured from the last counted point (the anchor) and only once the car has clearly
 * moved, so a parked car's GPS wander adds nothing.
 */
final class Trip {
    static final float MAX_ACCURACY = 25;      // metres; worse fixes are ignored
    private static final float MOVING = 1f;    // m/s; slower than this counts as standing still
    private static final double MAX_SPEED = 70; // m/s (~250 km/h); a faster jump is a GPS glitch
    private static final long MAX_GAP_MS = 10 * 60_000; // longer without a fix: don't guess the road

    private boolean anchored;
    private double lat, lon;
    private long at;
    private int jumps;

    /** Metres to add for this fix. speed is in m/s, or negative when the GPS doesn't report it. */
    double add(double newLat, double newLon, float accuracy, float speed, long timeMs) {
        if (!(accuracy > 0 && accuracy <= MAX_ACCURACY)) return 0;
        if (!anchored || timeMs < at || timeMs - at > MAX_GAP_MS) {
            anchor(newLat, newLon, timeMs);
            return 0;
        }
        double d = distance(lat, lon, newLat, newLon);
        if (d > MAX_SPEED * Math.max(1, (timeMs - at) / 1000.0)) {
            // One wild fix is skipped; three in a row mean the anchor itself was wrong.
            if (++jumps >= 3) anchor(newLat, newLon, timeMs);
            return 0;
        }
        jumps = 0;
        boolean moving = speed >= MOVING;
        // The 50 m escape covers GPS chips that always report a speed of 0.
        if (speed >= 0 && !moving && d < 50) {
            at = timeMs; // parked or at a light: the anchor stays, and stays fresh
            return 0;
        }
        if (d < (moving ? 10 : 30)) return 0;
        anchor(newLat, newLon, timeMs);
        return d;
    }

    private void anchor(double newLat, double newLon, long timeMs) {
        anchored = true;
        lat = newLat;
        lon = newLon;
        at = timeMs;
        jumps = 0;
    }

    /** Metres between two points (haversine). */
    static double distance(double lat1, double lon1, double lat2, double lon2) {
        double a = Math.toRadians(lat1), b = Math.toRadians(lat2);
        double x = Math.sin((b - a) / 2), y = Math.sin(Math.toRadians(lon2 - lon1) / 2);
        double h = x * x + Math.cos(a) * Math.cos(b) * y * y;
        return 2 * 6_371_000 * Math.asin(Math.min(1, Math.sqrt(h)));
    }
}
