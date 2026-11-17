package id.alwination.slimefunpet.packet.display;

import com.github.retrooper.packetevents.protocol.color.Color;
import com.github.retrooper.packetevents.protocol.particle.Particle;
import com.github.retrooper.packetevents.protocol.particle.data.ParticleDustData;
import com.github.retrooper.packetevents.protocol.particle.type.ParticleTypes;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerParticle;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;

/**
 * The particle packets a model pet's particle cubes give off. Builds packets
 * only, safe from the async pet ticker.
 */
public final class PetParticles {

    private PetParticles() {
    }

    /**
     * Whether this server's PacketEvents knows the particle, checked once when
     * the pets load so a puff never has to.
     */
    public static boolean isSendable(org.bukkit.Particle particle) {
        return particle == org.bukkit.Particle.DUST || SpigotConversionUtil.fromBukkitParticle(particle) != null;
    }

    public static PacketWrapper<?> puff(PetModels.Effect effect, double x, double y, double z) {
        org.bukkit.Particle.DustOptions dust = effect.dust();
        Particle<?> particle = dust != null
                ? new Particle<>(ParticleTypes.DUST, new ParticleDustData(dust.getSize(), new Color(dust.getColor().asRGB())))
                : new Particle<>(SpigotConversionUtil.fromBukkitParticle(effect.type()));
        float spread = effect.spread();
        return new WrapperPlayServerParticle(particle, false, new Vector3d(x, y, z), new Vector3f(spread, spread, spread),
                effect.speed(), effect.count());
    }

}
