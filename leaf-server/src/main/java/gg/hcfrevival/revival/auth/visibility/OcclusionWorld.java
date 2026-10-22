package gg.hcfrevival.revival.auth.visibility;

/** Blocks as one client was sent them, for line of sight. */
public interface OcclusionWorld {

    /** Whether the block at {@code (x, y, z)} hides what is behind it (a full opaque cube). */
    boolean occludes(int x, int y, int z);
}
