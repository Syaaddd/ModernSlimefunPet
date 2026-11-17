package id.alwination.slimefunpet.packet.display;

import org.joml.Vector3f;

/**
 * How a particle cube's puffs move around its centre, see 'animation' under
 * 'particles' in pets.yml. Shapes lie flat (on the world's horizontal plane)
 * and repeat every 'period' ticks.
 *
 * @param radius blocks out from the centre
 * @param height blocks the rising shapes climb
 * @param period ticks for one full loop
 * @param points particles in one ring
 */
public record ParticleMotion(Kind kind, float radius, float height, int period, int points) {

    // A ring is one packet per point per viewer, every puff
    public static final int MAX_POINTS = 24;

    public static final ParticleMotion POINT = new ParticleMotion(Kind.POINT, 0, 0, 1, 1);

    public enum Kind {
        /** At the centre, the plain puff. */
        POINT,
        /** One particle going round the centre. */
        CIRCLE,
        /** Two particles going round on opposite sides while climbing, then starting over. */
        HELIX,
        /** A full ring of 'points' particles, slowly turning. */
        RING,
        /** A ring that grows from the centre out to 'radius'. */
        PULSE,
        /** One particle climbing straight up. */
        RISE,
        /** One particle circling inward as it climbs, a little whirlwind. */
        SPIRAL
    }

    /**
     * Where this puff's particles go, blocks from the centre.
     *
     * @param out filled from index 0, at least MAX_POINTS long
     * @return how many of 'out' were written
     */
    public int positions(long tick, Vector3f[] out) {
        float phase = Math.floorMod(tick, (long) period) / (float) period;
        float angle = (float) (Math.PI * 2 * phase);
        switch (kind) {
            case CIRCLE -> {
                out[0].set(radius * Math.cos(angle), 0, radius * Math.sin(angle));
                return 1;
            }
            case HELIX -> {
                float y = phase * height;
                out[0].set(radius * Math.cos(angle), y, radius * Math.sin(angle));
                out[1].set(-out[0].x, y, -out[0].z);
                return 2;
            }
            case RING, PULSE -> {
                float r = kind == Kind.PULSE ? radius * phase : radius;
                for (int i = 0; i < points; i++) {
                    double a = angle + Math.PI * 2 * i / points;
                    out[i].set(r * Math.cos(a), 0, r * Math.sin(a));
                }
                return points;
            }
            case RISE -> {
                out[0].set(0, phase * height, 0);
                return 1;
            }
            case SPIRAL -> {
                float r = radius * (1 - phase);
                out[0].set(r * Math.cos(angle * 3), phase * height, r * Math.sin(angle * 3));
                return 1;
            }
            default -> {
                out[0].zero();
                return 1;
            }
        }
    }

}
