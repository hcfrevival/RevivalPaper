package gg.hcfrevival.revival;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import net.minecraft.world.flag.FeatureFlags;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.CraftRegistry;
import org.bukkit.support.DummyServerHelper;
import org.bukkit.support.RegistryHelper;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Bootstraps Minecraft's registries and tags (all feature flags) for a {@link RevivalTest} that needs them.
 * Paper's {@code @AllFeatures} only runs inside Paper's own suites, so RevivalTestSuite needs its own.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(RevivalRegistries.Extension.class)
public @interface RevivalRegistries {

    final class Extension implements BeforeAllCallback {

        private static boolean ready;

        @Override
        public void beforeAll(final ExtensionContext context) {
            synchronized (Extension.class) {
                if (ready) {
                    return;
                }
                // Same setup as Paper's AllFeaturesExtension
                RegistryHelper.setup(FeatureFlags.REGISTRY.allFlags());
                Bukkit.setServer(DummyServerHelper.setup());
                CraftRegistry.setMinecraftRegistry(RegistryHelper.registryAccess());
                ready = true;
            }
        }
    }
}
