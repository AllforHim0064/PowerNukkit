package cn.nukkit.network;

import cn.nukkit.Player;
import cn.nukkit.Server;
import cn.nukkit.event.player.PlayerCreationEvent;
import cn.nukkit.event.server.QueryRegenerateEvent;
import cn.nukkit.math.MathHelper;
import cn.nukkit.network.protocol.BatchPacket;
import cn.nukkit.network.protocol.DataPacket;
import cn.nukkit.network.protocol.ProtocolInfo;
import cn.nukkit.utils.Binary;
import cn.nukkit.utils.BinaryStream;
import cn.nukkit.utils.Utils;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.nukkitx.network.raknet.*;
import com.nukkitx.network.util.DisconnectReason;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.concurrent.ScheduledFuture;
import io.netty.util.internal.PlatformDependent;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.zip.Deflater;

/**
 * author: MagicDroidX
 * Nukkit Project
 */
@Log4j2
public class RakNetInterface implements RakNetServerListener, AdvancedSourceInterface {

    private final Server server;

    private Network network;

    private final RakNetServer raknet;

    private final Map<InetSocketAddress, NukkitRakNetSession> sessions = new ConcurrentHashMap<>();

    private final Set<ScheduledFuture<?>> tickFutures = new HashSet<>();

    private final FastThreadLocal<Set<NukkitRakNetSession>> sessionsToTick = new FastThreadLocal<Set<NukkitRakNetSession>>() {
        @Override
        protected Set<NukkitRakNetSession> initialValue() {
            return Collections.newSetFromMap(new IdentityHashMap<>());
        }
    };

    private byte[] advertisement;

    public RakNetInterface(Server server) {
        this.server = server;

        InetSocketAddress bindAddress = new InetSocketAddress(Strings.isNullOrEmpty(this.server.getIp()) ? "0.0.0.0" : this.server.getIp(), this.server.getPort());

        this.raknet = new RakNetServer(bindAddress, Runtime.getRuntime().availableProcessors());
        this.raknet.setProtocolVersion(10);
        this.raknet.bind().join();
        this.raknet.setListener(this);

        for (EventExecutor executor : this.raknet.getBootstrap().config().group()) {
            this.tickFutures.add(executor.scheduleAtFixedRate(() -> {
                for (NukkitRakNetSession session : sessionsToTick.get()) {
                    session.sendOutbound();
                }
            }, 0, 50, TimeUnit.MILLISECONDS));
        }
    }

    @Override
    public void setNetwork(Network network) {
        this.network = network;
    }

    @Override
    public boolean process() {
        Iterator<NukkitRakNetSession> iterator = this.sessions.values().iterator();
        while (iterator.hasNext()) {
            NukkitRakNetSession listener = iterator.next();
            Player player = listener.player;
            if (listener.disconnectReason != null) {
                player.close(player.getLeaveMessage(), listener.disconnectReason, false);
                iterator.remove();
                continue;
            }
            DataPacket packet;
            while ((packet = listener.inbound.poll()) != null) {
                listener.player.handleDataPacket(packet);
            }
        }
        return true;
    }

    @Override
    public int getNetworkLatency(Player player) {
        RakNetServerSession session = this.raknet.getSession(player.getSocketAddress());
        return session == null ? -1 : (int) session.getPing();
    }

    @Override
    public void close(Player player) {
        this.close(player, "unknown reason");
    }

    @Override
    public void close(Player player, String reason) {
        RakNetServerSession session = this.raknet.getSession(player.getSocketAddress());
        if (session != null) {
            session.close();
        }
    }

    @Override
    public void shutdown() {
        this.tickFutures.forEach(future -> future.cancel(false));
        this.raknet.close();
    }

    @Override
    public void emergencyShutdown() {
        this.tickFutures.forEach(future -> future.cancel(true));
        this.raknet.close();
    }

    @Override
    public void blockAddress(InetAddress address) {
        this.raknet.block(address);
    }

    @Override
    public void blockAddress(InetAddress address, int timeout) {
        this.raknet.block(address, timeout, TimeUnit.SECONDS);
    }

    @Override
    public void unblockAddress(InetAddress address) {
        this.raknet.unblock(address);
    }

    @Override
    public void sendRawPacket(InetSocketAddress socketAddress, ByteBuf payload) {
        this.raknet.send(socketAddress, payload);
    }

    @Override
    public void setName(String name) {
        QueryRegenerateEvent info = this.server.getQueryInformation();
        String[] names = name.split("!@#");  //Split double names within the program
        String motd = Utils.rtrim(names[0].replace(";", "\\;"), '\\');
        String subMotd = names.length > 1 ? Utils.rtrim(names[1].replace(";", "\\;"), '\\') : "";
        StringJoiner joiner = new StringJoiner(";")
                .add("MCPE")
                .add(motd)
                .add(Integer.toString(ProtocolInfo.CURRENT_PROTOCOL))
                .add(ProtocolInfo.MINECRAFT_VERSION_NETWORK)
                .add(Integer.toString(info.getPlayerCount()))
                .add(Integer.toString(info.getMaxPlayerCount()))
                .add(Long.toString(this.raknet.getGuid()))
                .add(subMotd)
                .add(Server.getGamemodeString(this.server.getDefaultGamemode(), true))
                .add("1");

        this.advertisement = joiner.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public Integer putPacket(Player player, DataPacket packet) {
        return this.putPacket(player, packet, false);
    }

    @Override
    public Integer putPacket(Player player, DataPacket packet, boolean needACK) {
        return this.putPacket(player, packet, needACK, false);
    }

    @Override
    public Integer putPacket(Player player, DataPacket packet, boolean needACK, boolean immediate) {
        NukkitRakNetSession session = this.sessions.get(player.getSocketAddress());

        if (session != null) {
            session.outbound.offer(packet);
        }

        return null;
    }

    @Override
    public boolean onConnectionRequest(InetSocketAddress inetSocketAddress) {
        return true;
    }

    @Override
    public byte[] onQuery(InetSocketAddress inetSocketAddress) {
        return this.advertisement;
    }

    @Override
    public void onSessionCreation(RakNetServerSession session) {
        PlayerCreationEvent ev = new PlayerCreationEvent(this, Player.class, Player.class, null, session.getAddress());
        this.server.getPluginManager().callEvent(ev);
        Class<? extends Player> clazz = ev.getPlayerClass();

        Player player;
        InetSocketAddress socketAddress = ev.getSocketAddress();
        try {
            Constructor<? extends Player> constructor = clazz.getConstructor(SourceInterface.class, Long.class, InetSocketAddress.class);
            player = constructor.newInstance(this, ev.getClientId(), socketAddress);
        } catch (ReflectiveOperationException e) {
            try {
                Constructor<? extends Player> constructor = clazz.getConstructor(SourceInterface.class, Long.class, String.class, Integer.TYPE);
                player = constructor.newInstance(this, ev.getClientId(), socketAddress.getHostString(), socketAddress.getPort());
            } catch (ReflectiveOperationException e2) {
                e2.addSuppressed(e);
                Server.getInstance().getLogger().logException(e);
                session.disconnect();
                return;
            }
        }

        this.server.addPlayer(session.getAddress(), player);
        NukkitRakNetSession nukkitSession = new NukkitRakNetSession(session, player);
        this.sessions.put(session.getAddress(), nukkitSession);
        this.sessionsToTick.get().add(nukkitSession);
        session.setListener(nukkitSession);
    }

    @Override
    public void onUnhandledDatagram(ChannelHandlerContext ctx, DatagramPacket datagramPacket) {
        this.server.handlePacket(datagramPacket.sender(), datagramPacket.content());
    }

    @RequiredArgsConstructor
    private class NukkitRakNetSession implements RakNetSessionListener {
        private final RakNetServerSession session;
        private final Player player;
        private final Queue<DataPacket> inbound = PlatformDependent.newSpscQueue();
        private final Queue<DataPacket> outbound = PlatformDependent.newSpscQueue();
        private String disconnectReason = null;

        @Override
        public void onSessionChangeState(RakNetState rakNetState) {
        }

        @Override
        public void onDisconnect(DisconnectReason disconnectReason) {
            if (disconnectReason == DisconnectReason.TIMED_OUT) {
                this.disconnectReason = "Timed out";
            } else {
                this.disconnectReason = "Disconnected from Server";
            }
            RakNetInterface.this.sessionsToTick.get().remove(this);
        }

        @Override
        public void onEncapsulated(EncapsulatedPacket packet) {
            ByteBuf buffer = packet.getBuffer();
            short packetId = buffer.readUnsignedByte();
            if (packetId == 0xfe) {
                DataPacket batchPacket = RakNetInterface.this.network.getPacket(ProtocolInfo.BATCH_PACKET);
                if (batchPacket == null) {
                    return;
                }

                byte[] packetBuffer = new byte[buffer.readableBytes()];
                buffer.readBytes(packetBuffer);
                batchPacket.setBuffer(packetBuffer);
                batchPacket.decode();

                this.inbound.offer(batchPacket);
            }
        }

        @Override
        public void onDirect(ByteBuf byteBuf) {
            // We don't allow any direct packets so ignore.
        }

        private void sendOutbound() {
            List<DataPacket> toBatch = new ArrayList<>();
            DataPacket packet;
            while ((packet = this.outbound.poll()) != null) {
                if (packet.pid() == ProtocolInfo.BATCH_PACKET) {
                    if (!toBatch.isEmpty()) {
                        this.sendPackets(toBatch.toArray(new DataPacket[0]));
                        toBatch.clear();
                    }

                    this.sendPacket(((BatchPacket) packet).payload);
                } else {
                    toBatch.add(packet);
                }
            }

            if (!toBatch.isEmpty()) {
                this.sendPackets(toBatch.toArray(new DataPacket[0]));
            }
        }

        private void sendPackets(DataPacket[] packets) {
            BinaryStream batched = new BinaryStream();
            for (DataPacket packet : packets) {
                Preconditions.checkArgument(!(packet instanceof BatchPacket), "Cannot batch BatchPacket");
                if (!packet.isEncoded) packet.encode();
                byte[] buf = packet.getBuffer();
                batched.putUnsignedVarInt(buf.length);
                batched.put(buf);
            }

            try {
                this.sendPacket(Network.deflateRaw(batched.getBuffer(), network.getServer().networkCompressionLevel));
            } catch (IOException e) {
                log.info("Unable to deflate batched packets", e);
            }
        }

        private void sendPacket(byte[] payload) {
            ByteBuf byteBuf = ByteBufAllocator.DEFAULT.ioBuffer(1 + payload.length);
            byteBuf.writeByte(0xfe);
            byteBuf.writeBytes(payload);
            this.session.send(byteBuf);
        }
    }
}
