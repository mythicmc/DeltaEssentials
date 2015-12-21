/*
 * This file is part of DeltaEssentials.
 *
 * DeltaEssentials is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * DeltaEssentials is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with DeltaEssentials.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.yahoo.tracebachi.DeltaEssentials.Commands;

import com.yahoo.tracebachi.DeltaEssentials.CallbackUtil;
import com.yahoo.tracebachi.DeltaEssentials.DeltaEssentialsPlugin;
import com.yahoo.tracebachi.DeltaEssentials.Prefixes;
import com.yahoo.tracebachi.DeltaRedis.Shared.Redis.Channels;
import com.yahoo.tracebachi.DeltaRedis.Spigot.DeltaRedisApi;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * Created by Trace Bachi (tracebachi@yahoo.com, BigBossZee) on 12/4/15.
 */
public class MoveToCommand implements CommandExecutor
{
    public static final String MOVE_CHANNEL = "DE-Move";

    private DeltaEssentialsPlugin essentialsPlugin;
    private DeltaRedisApi deltaRedisApi;

    public MoveToCommand(DeltaEssentialsPlugin essentialsPlugin, DeltaRedisApi deltaRedisApi)
    {
        this.essentialsPlugin = essentialsPlugin;
        this.deltaRedisApi = deltaRedisApi;
    }

    public void shutdown()
    {
        this.deltaRedisApi = null;
        this.essentialsPlugin = null;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String s, String[] args)
    {
        Set<String> servers = new HashSet<>(deltaRedisApi.getCachedServers());
        Set<String> blockedServers = essentialsPlugin.getBlockedServers();
        String currentServer = deltaRedisApi.getServerName();

        // Remove the Bungeecord server as players cannot move to it
        servers.remove(Channels.BUNGEECORD);

        if(args.length == 0)
        {
            sender.sendMessage(Prefixes.INFO + "You are currently in " +
                ChatColor.WHITE + currentServer);
            sender.sendMessage(Prefixes.INFO + "/moveto <server>");

            List<String> sorted = Arrays.asList(servers.toArray(new String[servers.size()]));
            Collections.sort(sorted);
            String joined = String.join(ChatColor.GRAY + ", " + ChatColor.WHITE, sorted);

            sender.sendMessage(Prefixes.INFO + "Online servers: " + ChatColor.WHITE + joined);
        }
        else if(args.length == 1)
        {
            if(!(sender instanceof Player))
            {
                sender.sendMessage(Prefixes.FAILURE +
                    "Consoles cannot move servers.");
            }
            else if(!sender.hasPermission("DeltaEss.MoveTo.Self"))
            {
                sender.sendMessage(Prefixes.FAILURE +
                    "You do not have permission to use that command.");
            }
            else if(currentServer.equals(args[0]))
            {
                sender.sendMessage(Prefixes.FAILURE +
                    "You are already connected to that server.");
            }
            else if(blockedServers.contains(args[0]) &&
                !sender.hasPermission("DeltaEss.MoveTo.Bypass"))
            {
                sender.sendMessage(Prefixes.FAILURE +
                    "That server is blocked and you do not have permission to bypass it.");
            }
            else if(!servers.contains(args[0]))
            {
                sender.sendMessage(Prefixes.FAILURE +
                    "There is no server online named " + ChatColor.WHITE + args[0]);
            }
            else
            {
                sender.sendMessage(Prefixes.SUCCESS +
                    "Attempting to switch servers ...");
                essentialsPlugin.sendToServer((Player) sender, args[0]);
            }
        }
        else
        {
            if(!sender.hasPermission("DeltaEss.MoveTo.Other"))
            {
                sender.sendMessage(Prefixes.FAILURE +
                    "You do not have permission to use that command.");
            }
            else if(currentServer.equals(args[0]))
            {
                sender.sendMessage(Prefixes.FAILURE +
                    "You are already connected to that server.");
            }
            else if(blockedServers.contains(args[0]) &&
                !sender.hasPermission("DeltaEss.MoveTo.Bypass"))
            {
                sender.sendMessage(Prefixes.FAILURE +
                    "That server is blocked and you do not have permission to bypass it.");
            }
            else if(!servers.contains(args[0]))
            {
                sender.sendMessage(Prefixes.FAILURE +
                    "There is no server online named " + ChatColor.WHITE + args[0]);
            }
            else
            {
                String senderName = sender.getName();
                String targetName = args[1].toLowerCase();
                Player target = Bukkit.getPlayer(targetName);

                if(target != null && target.isOnline())
                {
                    sender.sendMessage(Prefixes.SUCCESS +
                        "Attempting to switch servers ...");
                    essentialsPlugin.sendToServer(target, args[0]);
                }
                else
                {
                    deltaRedisApi.findPlayer(targetName, cachedPlayer ->
                    {
                        if(cachedPlayer != null)
                        {
                            if(!cachedPlayer.getServer().equals(args[0]))
                            {
                                deltaRedisApi.publish(cachedPlayer.getServer(), MOVE_CHANNEL,
                                    senderName + "/\\" + targetName + "/\\" + args[0]);

                                CallbackUtil.sendMessage(senderName,
                                    Prefixes.SUCCESS + "Sending player to " + args[0]);
                            }
                            else
                            {
                                CallbackUtil.sendMessage(senderName,
                                    Prefixes.FAILURE + "Player is already in that server.");
                            }
                        }
                        else
                        {
                            CallbackUtil.sendMessage(senderName,
                                Prefixes.FAILURE + "Player not found.");
                        }
                    });
                }
            }
        }
        return true;
    }
}