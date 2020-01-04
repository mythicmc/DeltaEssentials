/*
 * DeltaEssentials - Player data, chat, and teleport plugin for BungeeCord and Spigot servers
 * Copyright (C) 2017 tracebachi@gmail.com (GeeItsZee)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
 package com.gmail.tracebachi.DeltaEssentials.Spigot.Teleport;

import com.gmail.tracebachi.DeltaEssentials.DeltaEssentialsConstants;
import com.gmail.tracebachi.DeltaEssentials.Spigot.DeltaEssentialsPlugin;
import com.gmail.tracebachi.DeltaEssentials.Spigot.PlayerFileIO.DeltaEssPlayerFile;
import com.gmail.tracebachi.SockExchange.Messages.ReceivedMessage;
import com.gmail.tracebachi.SockExchange.Messages.ResponseMessage;
import com.gmail.tracebachi.SockExchange.Spigot.SockExchangeApi;
import com.gmail.tracebachi.SockExchange.SpigotServerInfo;
import com.gmail.tracebachi.SockExchange.Utilities.CaseInsensitiveMap;
import com.gmail.tracebachi.SockExchange.Utilities.ExtraPreconditions;
import com.gmail.tracebachi.SockExchange.Utilities.MessageFormatMap;
import com.gmail.tracebachi.SockExchange.Utilities.Registerable;
import com.google.common.base.Preconditions;
import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class TpAskManager implements Listener, Registerable {

    private static final long CONSUMER_TIMEOUT = TimeUnit.SECONDS.toMillis(5);
    private static final long TP_ASK_HERE_REQUEST_TIMEOUT = TimeUnit.SECONDS.toMillis(15);
    private static final long CLEANUP_REQUEST_PERIOD_MILLIS = TimeUnit.SECONDS.toMillis(20);

    private final DeltaEssentialsPlugin plugin;
    private final SameServerTeleporter sameServerTeleporter;
    private final MessageFormatMap formatMap;
    private final SockExchangeApi api;
    private final CaseInsensitiveMap<TpAskRequest> tpAskRequestMap;
    private final Consumer<ReceivedMessage> tpAskChannelListener;
    private ScheduledFuture<?> cleanupFuture;

    public TpAskManager(
            DeltaEssentialsPlugin plugin, SameServerTeleporter sameServerTeleporter,
            MessageFormatMap formatMap, SockExchangeApi api) {
        Preconditions.checkNotNull(plugin, "plugin");
        Preconditions.checkNotNull(sameServerTeleporter, "sameServerTeleporter");
        Preconditions.checkNotNull(formatMap, "formatMap");
        Preconditions.checkNotNull(api, "api");

        this.plugin = plugin;
        this.sameServerTeleporter = sameServerTeleporter;
        this.formatMap = formatMap;
        this.api = api;
        this.tpAskRequestMap = new CaseInsensitiveMap<>(new HashMap<>());
        this.tpAskChannelListener = this::onTpAskChannelRequest;
    }

    @Override
    public void register() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);

        api.getMessageNotifier().register(DeltaEssentialsConstants.Channels.TP_ASK, tpAskChannelListener);

        cleanupFuture = api.getScheduledExecutorService().scheduleAtFixedRate(
                () -> plugin.executeSync(this::cleanupRequests), CLEANUP_REQUEST_PERIOD_MILLIS,
                CLEANUP_REQUEST_PERIOD_MILLIS, TimeUnit.MILLISECONDS);
    }

    @Override
    public void unregister() {
        if (cleanupFuture != null) {
            cleanupFuture.cancel(false);
            cleanupFuture = null;
        }

        api.getMessageNotifier().unregister(DeltaEssentialsConstants.Channels.TP_ASK, tpAskChannelListener);

        HandlerList.unregisterAll(this);
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerQuit(PlayerQuitEvent event) {
        String playerName = event.getPlayer().getName();
        tpAskRequestMap.remove(playerName);
    }

    public void tpAsk(
            Player startPlayer, String destPlayerName, boolean ignoreBlocking, boolean ignoreVanish) {
        internalTpAsk(startPlayer, destPlayerName, ignoreBlocking, ignoreVanish);
    }

    private void internalTpAsk(Player startPlayer, String destPlayerName, boolean ignoreBlocking, boolean ignoreVanish) {
        String startPlayerName = startPlayer.getName();
        Player destPlayer = plugin.getServer().getPlayerExact(destPlayerName);

        // Check if both players are on the same server
        if (destPlayer != null) {
            onSameServerTpTo(startPlayer, destPlayer, ignoreBlocking, ignoreVanish);
            return;
        }

        // If one of the players is not on the same server, a request must be made.
        ByteArrayDataOutput out = ByteStreams.newDataOutput(128);
        out.writeUTF(startPlayerName);
        out.writeUTF(destPlayerName);
        out.writeUTF(api.getServerName());
        out.writeBoolean(ignoreBlocking);
        out.writeBoolean(ignoreVanish);
        writeStringList(out, getAccessiblePrivateServerNames(startPlayer));

        api.sendToServerOfPlayer(DeltaEssentialsConstants.Channels.TP_ASK, out.toByteArray(), destPlayerName,
                (m) -> onAnyResponseCheckIfOk(startPlayerName, destPlayerName, m), CONSUMER_TIMEOUT);
    }

    public boolean acceptTpAsk(Player startPlayer) {
        String startPlayerName = startPlayer.getName();
        TpAskRequest tpAskHereRequest = tpAskRequestMap.remove(startPlayerName);

        if (tpAskHereRequest == null) {
            // Respond to the acceptor that they have no requests
            /*String message = formatMap.format(DeltaEssentialsConstants.FormatNames.TP_ASK_HERE_NO_REQUESTS);
            api.sendChatMessages(Collections.singletonList(message), startPlayerName, null);*/
            // TpAsk update: Sent on TpAskHere request accept method call.
            return false;
        }

        String destPlayerName = tpAskHereRequest.getDestPlayerName();
        String destServerName = tpAskHereRequest.getDestServerName();
        String currentServerName = api.getServerName();

        if (!destServerName.equalsIgnoreCase(currentServerName)) {
            // Request the destServer to teleport when the player is loaded
            sendRequestToTpAfterLoad(
                    destPlayerName, startPlayerName, currentServerName, PlayerTpEvent.TeleportType.TP_ASK);
            // Move player to destServer
            api.movePlayers(Collections.singleton(destPlayerName), currentServerName);
            return true;
        }
        Player destPlayer = plugin.getServer().getPlayerExact(destPlayerName);

        if (destPlayer == null) {
            /*String message = formatMap.format(DeltaEssentialsConstants.FormatNames.PLAYER_OFFLINE, destPlayerName);
            api.sendChatMessages(Collections.singletonList(message), startPlayerName, null);*/
            // TpAsk update: Sent on TpAskHere request accept method call.
            return false;
        }
        sameServerTeleporter.teleport(destPlayer, startPlayer, PlayerTpEvent.TeleportType.TP_ASK);
        return true;
    }

    public boolean denyTpAsk(Player startPlayer) {
        String startPlayerName = startPlayer.getName();
        TpAskManager.TpAskRequest tpAskHereRequest = tpAskRequestMap.remove(startPlayerName);
        String message;

        if (tpAskHereRequest == null) {
            // Respond to the denier that they have no requests
            /*message = formatMap.format(DeltaEssentialsConstants.FormatNames.TP_ASK_HERE_NO_REQUESTS);
            api.sendChatMessages(Collections.singletonList(message), startPlayerName, null);*/
            return false;
        }

        String destPlayerName = tpAskHereRequest.getDestPlayerName();

        // Respond to the sender and denier that the request was denied
        message = formatMap.format(DeltaEssentialsConstants.FormatNames.TP_ASK_HERE_DENIED, startPlayerName, destPlayerName);
        api.sendChatMessages(Collections.singletonList(message), startPlayerName, null);
        api.sendChatMessages(Collections.singletonList(message), destPlayerName, null);
        return true;
    }

    private void onSameServerTpTo(
            Player startPlayer, Player destPlayer, boolean ignoreBlocking,
            boolean ignoreVanish) {
        String message;
        String startPlayerName = startPlayer.getName();
        String destPlayerName = destPlayer.getName();

        DeltaEssPlayerFile playerFile = plugin.getLoadedPlayerFile(destPlayerName);

        if (playerFile != null) {
            if (!ignoreBlocking && playerFile.isBlockingTeleports()) {
                message = formatMap.format(DeltaEssentialsConstants.FormatNames.BLOCKING_TELEPORTS, destPlayerName);
                api.sendChatMessages(Collections.singletonList(message), startPlayerName, null);
                return;
            }

            if (!ignoreVanish && playerFile.isVanished()) {
                message = formatMap.format(DeltaEssentialsConstants.FormatNames.PLAYER_OFFLINE, destPlayerName);
                api.sendChatMessages(Collections.singletonList(message), startPlayerName, null);
                return;
            }
        }

        String destServerName = api.getServerName();

        saveTpAskRequest(startPlayerName, destPlayerName, destServerName);
        tellPlayersAboutTpAskHereRequest(startPlayerName, destPlayerName);
    }

    private void onDiffServerTpTo(
            String startPlayerName, String destPlayerName, boolean ignoreBlocking,
            boolean ignoreVanish, String destServerName, List<String> accessibleServerNames) {
        String message;
        Player destPlayer = plugin.getServer().getPlayerExact(destPlayerName);

        if (destPlayer == null) {
            message = formatMap.format(DeltaEssentialsConstants.FormatNames.PLAYER_OFFLINE, destPlayerName);
            api.sendChatMessages(Collections.singletonList(message), startPlayerName, null);
            return;
        }

        String currentServerName = api.getServerName();

        DeltaEssPlayerFile playerFile = plugin.getLoadedPlayerFile(destPlayerName);

        if (playerFile != null) {
            if (!ignoreBlocking && playerFile.isBlockingTeleports()) {
                message = formatMap.format(DeltaEssentialsConstants.FormatNames.BLOCKING_TELEPORTS, destPlayerName);
                api.sendChatMessages(Collections.singletonList(message), startPlayerName, null);
                return;
            }

            if (!ignoreVanish && playerFile.isVanished()) {
                message = formatMap.format(DeltaEssentialsConstants.FormatNames.PLAYER_OFFLINE, destPlayerName);
                api.sendChatMessages(Collections.singletonList(message), startPlayerName, null);
                return;
            }
        }

        SpigotServerInfo currentServerInfo = api.getServerInfo(currentServerName);

        if (currentServerInfo != null && currentServerInfo.isPrivate()) {
            if (!accessibleServerNames.contains(currentServerName)) {
                String permission = DeltaEssentialsConstants.Permissions.TP_TO_PRIVATE_SERVER_PERM_PREFIX + currentServerName;

                message = formatMap.format(DeltaEssentialsConstants.FormatNames.NO_PERM, permission);
                api.sendChatMessages(Collections.singletonList(message), startPlayerName, null);
                return;
            }
        }

        saveTpAskRequest(startPlayerName, destPlayerName, destServerName);
        tellPlayersAboutTpAskHereRequest(startPlayerName, destPlayerName);
    }

    private void saveTpAskRequest(
            String startPlayerName, String destPlayerName, String destServerName) {
        TpAskRequest tpAskRequest = new TpAskRequest(startPlayerName, destServerName,
                System.currentTimeMillis() + TP_ASK_HERE_REQUEST_TIMEOUT);
        tpAskRequestMap.put(destPlayerName, tpAskRequest);
    }

    private void tellPlayersAboutTpAskHereRequest(String startPlayerName, String destPlayerName) {
        String message;

        message = formatMap.format(DeltaEssentialsConstants.FormatNames.TP_ASK_SENT, destPlayerName);
        api.sendChatMessages(Collections.singletonList(message), startPlayerName, null);

        message = formatMap.format(DeltaEssentialsConstants.FormatNames.TP_ASK_RECEIVED, startPlayerName);
        api.sendChatMessages(Collections.singletonList(message), destPlayerName, null);
    }

    private void onAnyResponseCheckIfOk(
            String senderName, String otherPlayerName, ResponseMessage responseMessage) {
        if (!responseMessage.getResponseStatus().isOk()) {
            String message = formatMap.format(DeltaEssentialsConstants.FormatNames.PLAYER_OFFLINE, otherPlayerName);
            api.sendChatMessages(Collections.singletonList(message), senderName, null);
        }
    }

    private void onTpAskChannelRequest(ReceivedMessage message) {
        // Respond to confirm message was received
        message.respond();

        ByteArrayDataInput in = message.getDataInput();
        String startPlayerName = in.readUTF();
        String destPlayerName = in.readUTF();
        String destServerName = in.readUTF();
        boolean ignoreBlocking = in.readBoolean();
        boolean ignoreVanish = in.readBoolean();
        List<String> accessibleServerNames = readStringList(in);

        plugin.executeSync(() ->
        {
            onDiffServerTpTo(
                    startPlayerName, destPlayerName, ignoreBlocking, ignoreVanish, destServerName,
                    accessibleServerNames);
        });
    }

    private List<String> getAccessiblePrivateServerNames(Player startPlayer) {
        List<String> usablePrivateServers = new ArrayList<>();
        for (SpigotServerInfo serverInfo : api.getServerInfos()) {
            String serverName = serverInfo.getServerName();
            String permission = DeltaEssentialsConstants.Permissions.TP_TO_PRIVATE_SERVER_PERM_PREFIX + serverName;

            if (serverInfo.isPrivate() && startPlayer.hasPermission(permission)) {
                usablePrivateServers.add(serverName);
            }
        }
        return usablePrivateServers;
    }

    private void sendRequestToTpAfterLoad(
            String startPlayerName, String destPlayerName, String destServerName, PlayerTpEvent.TeleportType teleportType) {
        int estimatedSize = (startPlayerName.length() + destPlayerName.length()) * 2;
        ByteArrayDataOutput out = ByteStreams.newDataOutput(estimatedSize);
        out.writeUTF(startPlayerName);
        out.writeUTF(destPlayerName);
        out.writeByte(teleportType.ordinal());

        // Send a message for dest server to create a TpAfterLoadRequest for nameToTp
        api.sendToServer(DeltaEssentialsConstants.Channels.TP_AFTER_LOAD, out.toByteArray(), destServerName);
    }

    private void writeStringList(ByteArrayDataOutput out, List<String> stringList) {
        out.writeInt(stringList.size());

        for (String str : stringList) {
            Preconditions.checkNotNull(str, "str");
            out.writeUTF(str);
        }
    }

    private List<String> readStringList(ByteArrayDataInput in) {
        int count = in.readInt();
        List<String> stringList = new ArrayList<>(count);

        for (int i = 0; i < count; i++) {
            stringList.add(in.readUTF());
        }

        return stringList;
    }

    private void cleanupRequests() {
        long currentTimeMillis = System.currentTimeMillis();

        tpAskRequestMap.values().removeIf(
                request -> currentTimeMillis > request.getExpiresAtMillis());
    }

    public static class TpAskRequest {
        private final String destPlayerName;
        private final String destServerName;
        private final long expiresAtMillis;

        TpAskRequest(String destPlayerName, String destServerName, long expiresAtMillis) {
            ExtraPreconditions.checkNotEmpty(destPlayerName, "destPlayerName");
            ExtraPreconditions.checkNotEmpty(destServerName, "destServerName");

            this.destPlayerName = destPlayerName;
            this.destServerName = destServerName;
            this.expiresAtMillis = expiresAtMillis;
        }

        String getDestPlayerName() {
            return destPlayerName;
        }

        String getDestServerName() {
            return destServerName;
        }

        long getExpiresAtMillis() {
            return expiresAtMillis;
        }
    }
}
