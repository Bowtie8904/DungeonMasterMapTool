package dmmt.lighting;

import java.util.Arrays;

/**
 * Computes a 2D visibility polygon for a point light by ray casting against blocking segments.
 * Segments are passed as flat arrays: [x1, y1, x2, y2, x1, y1, ...].
 */
public class VisibilityService {
    private static final int CIRCLE_RAYS = 96;
    private static final double ANGLE_EPSILON = 0.0001;

    public record Polygon(double[] xs, double[] ys) {
        public int size() {
            return xs.length;
        }
    }

    public Polygon compute(double lightX, double lightY, double range, double[] segments) {
        if (range <= 0) {
            return new Polygon(new double[0], new double[0]);
        }
        double[] nearby = filterSegments(lightX, lightY, range, segments);
        int segmentCount = nearby.length / 4;

        double[] angles = new double[CIRCLE_RAYS + segmentCount * 6];
        int angleCount = 0;
        for (int i = 0; i < CIRCLE_RAYS; i++) {
            angles[angleCount++] = -Math.PI + (2 * Math.PI * i) / CIRCLE_RAYS;
        }
        for (int s = 0; s < segmentCount; s++) {
            for (int p = 0; p < 2; p++) {
                double px = nearby[s * 4 + p * 2];
                double py = nearby[s * 4 + p * 2 + 1];
                double angle = Math.atan2(py - lightY, px - lightX);
                angles[angleCount++] = angle - ANGLE_EPSILON;
                angles[angleCount++] = angle;
                angles[angleCount++] = angle + ANGLE_EPSILON;
            }
        }
        Arrays.sort(angles, 0, angleCount);

        double[] xs = new double[angleCount];
        double[] ys = new double[angleCount];
        for (int i = 0; i < angleCount; i++) {
            double dx = Math.cos(angles[i]);
            double dy = Math.sin(angles[i]);
            double t = castRay(lightX, lightY, dx, dy, range, nearby);
            xs[i] = lightX + dx * t;
            ys[i] = lightY + dy * t;
        }
        return new Polygon(xs, ys);
    }

    public Polygon circle(double lightX, double lightY, double range) {
        double[] xs = new double[CIRCLE_RAYS];
        double[] ys = new double[CIRCLE_RAYS];
        for (int i = 0; i < CIRCLE_RAYS; i++) {
            double angle = (2 * Math.PI * i) / CIRCLE_RAYS;
            xs[i] = lightX + Math.cos(angle) * range;
            ys[i] = lightY + Math.sin(angle) * range;
        }
        return new Polygon(xs, ys);
    }

    /** Distance along the ray to the nearest blocking segment, capped at maxDistance. */
    private double castRay(double ox, double oy, double dx, double dy, double maxDistance, double[] segments) {
        double best = maxDistance;
        for (int i = 0; i < segments.length; i += 4) {
            double x1 = segments[i];
            double y1 = segments[i + 1];
            double ex = segments[i + 2] - x1;
            double ey = segments[i + 3] - y1;
            double denom = dx * ey - dy * ex;
            if (Math.abs(denom) < 1e-12) {
                continue;
            }
            double wx = x1 - ox;
            double wy = y1 - oy;
            double t = (wx * ey - wy * ex) / denom;
            double u = (wx * dy - wy * dx) / denom;
            if (t >= 0 && t < best && u >= 0 && u <= 1) {
                best = t;
            }
        }
        return best;
    }

    private double[] filterSegments(double lx, double ly, double range, double[] segments) {
        double[] result = new double[segments.length];
        int count = 0;
        double rangeSq = range * range;
        for (int i = 0; i + 3 < segments.length; i += 4) {
            if (distanceToSegmentSq(lx, ly, segments[i], segments[i + 1], segments[i + 2], segments[i + 3]) <= rangeSq) {
                System.arraycopy(segments, i, result, count, 4);
                count += 4;
            }
        }
        return Arrays.copyOf(result, count);
    }

    private double distanceToSegmentSq(double px, double py, double x1, double y1, double x2, double y2) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double lenSq = dx * dx + dy * dy;
        double t = lenSq == 0 ? 0 : ((px - x1) * dx + (py - y1) * dy) / lenSq;
        t = Math.max(0, Math.min(1, t));
        double sx = x1 + t * dx - px;
        double sy = y1 + t * dy - py;
        return sx * sx + sy * sy;
    }
}
