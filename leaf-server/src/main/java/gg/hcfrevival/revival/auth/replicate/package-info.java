/**
 * Replication of authoritative player paths to other clients, shaped so 26.3's stepped interpolation never runs dry
 * (report 03 §6, K = 2): {@link gg.hcfrevival.revival.auth.replicate.PathFeed}. Pure logic: the tracker hook appends
 * steps and sends what a viewer's feed builds. Design: docs/revival/auth-combat.md.
 */
@NullMarked
package gg.hcfrevival.revival.auth.replicate;

import org.jspecify.annotations.NullMarked;
