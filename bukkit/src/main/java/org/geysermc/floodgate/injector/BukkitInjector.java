package org.geysermc.floodgate.injector;

import io.netty.channel.*;
import lombok.Getter;
import org.geysermc.floodgate.BukkitPlugin;
import org.geysermc.floodgate.PacketHandler;
import org.geysermc.floodgate.util.ReflectionUtil;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class BukkitInjector {
    private static Logger logger = BukkitPlugin.getInstance().getLogger();
    private static Object serverConnection;
    private static List<ChannelFuture> injectedClients = new ArrayList<>();

    @Getter private static boolean injected = false;
    private static String injectedFieldName;

    public static boolean inject() throws Exception {
        if (getServerConnection() != null) {
            for (Field f : serverConnection.getClass().getDeclaredFields()) {
                if (f.getType() == List.class) {
                    logger.finest("Injector step 0");
                    f.setAccessible(true);
                    boolean rightList = ((ParameterizedType) f.getGenericType()).getActualTypeArguments()[0] == ChannelFuture.class;
                    if (!rightList) continue;
                    logger.finest("Injector step 1");

                    injectedFieldName = f.getName();
                    List<?> newList = new CustomList((List<?>) f.get(serverConnection)) {
                        @Override
                        public void onAdd(Object o) {
                            try {
                                logger.finest("Injector step 2");
                                injectClient((ChannelFuture) o);
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }
                    };

                    synchronized (newList) {
                        for (Object o : newList) {
                            try {
                                logger.finest("Injector step 2");
                                injectClient((ChannelFuture) o);
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }
                    }

                    f.set(serverConnection, newList);
                    injected = true;
                    return true;
                }
            }
        }
        return false;
    }

    public static void injectClient(ChannelFuture future) {
        logger.finest("Injector step 3");
        future.channel().pipeline().addFirst("floodgate-init", new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                logger.finest("Injector step 4");
                super.channelRead(ctx, msg);

                Channel channel = (Channel) msg;
                channel.pipeline().addLast(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel channel) throws Exception {
                        logger.finest("Injector step 5");
                        channel.pipeline().addBefore("packet_handler", "floodgate_handler", new PacketHandler(future));
                    }
                });
            }
        });
        injectedClients.add(future);
    }

    public static void removeInjectedClient(ChannelFuture future, Channel channel) {
        future.channel().pipeline().remove("floodgate-init");
        if (channel != null) channel.pipeline().remove("floodgate_handler");
        injectedClients.remove(future);
    }

    public static boolean removeInjection() throws Exception {
        if (!isInjected()) return true;
        for (ChannelFuture future : injectedClients) {
            removeInjectedClient(future, null);
        }

        Object serverConnection = getServerConnection();
        if (serverConnection != null) {
            Field field = ReflectionUtil.getField(serverConnection.getClass(), injectedFieldName);
            List<?> list = (List<?>) ReflectionUtil.getValue(serverConnection, field);
            if (list instanceof CustomList) {
                ReflectionUtil.setValue(serverConnection, field, ((CustomList) list).getOriginalList());
            }
        }
        injectedFieldName = null;
        injected = false;
        return true;
    }

    public static Object getServerConnection() throws NoSuchMethodException, IllegalAccessException, InvocationTargetException {
        if (serverConnection != null) return serverConnection;
        Class<?> minecraftServer = ReflectionUtil.getNMSClass("MinecraftServer");
        assert minecraftServer != null;

        Object minecraftServerInstance = ReflectionUtil.invokeStatic(minecraftServer, "getServer");
        for (Method m : minecraftServer.getDeclaredMethods()) {
            if (m.getReturnType() != null) {
                if (m.getReturnType().getSimpleName().equals("ServerConnection")) {
                    if (m.getParameterTypes().length == 0) {
                        serverConnection = m.invoke(minecraftServerInstance);
                    }
                }
            }
        }

        return serverConnection;
    }
}
