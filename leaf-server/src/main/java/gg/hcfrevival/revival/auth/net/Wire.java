package gg.hcfrevival.revival.auth.net;

import net.minecraft.util.Mth;

/**
 * What the client decodes from lossy wire formats, so the simulation applies the client's value and not the server's
 * (report 02 §3.3, H3).
 */
public final class Wire {

    private Wire() {
    }

    /**
     * {@code LpVec3.read(LpVec3.write(v))} without a buffer: the velocity a {@code SetEntityMotion} gives the client.
     * 15 bits per axis scaled by {@code ceil(max |v|)}; below {@code 3.051944088384301E-5} the whole vector is zero.
     * Writes x, y, z into {@code out[0..2]}.
     */
    public static void lpVec3(final double x, final double y, final double z, final double[] out) {
        final double sx = sanitize(x);
        final double sy = sanitize(y);
        final double sz = sanitize(z);
        final double chessboardLength = Mth.absMax(sx, Mth.absMax(sy, sz));
        if (chessboardLength < 3.051944088384301E-5) {
            out[0] = 0.0;
            out[1] = 0.0;
            out[2] = 0.0;
            return;
        }
        final long scale = Mth.ceilLong(chessboardLength);
        out[0] = unpack(pack(sx / scale)) * scale;
        out[1] = unpack(pack(sy / scale)) * scale;
        out[2] = unpack(pack(sz / scale)) * scale;
    }

    private static double sanitize(final double value) {
        return Double.isNaN(value) ? 0.0 : Math.clamp(value, -1.7179869183E10, 1.7179869183E10);
    }

    private static long pack(final double value) {
        return Math.round((value * 0.5 + 0.5) * 32766.0);
    }

    private static double unpack(final long value) {
        return Math.min((double) (value & 32767L), 32766.0) * 2.0 / 32766.0 - 1.0;
    }
}
