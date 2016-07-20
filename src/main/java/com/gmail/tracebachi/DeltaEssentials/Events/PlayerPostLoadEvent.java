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
package com.gmail.tracebachi.DeltaEssentials.Events;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Created by Trace Bachi (tracebachi@gmail.com, BigBossZee) on 12/12/15.
 */
public class PlayerPostLoadEvent extends Event
{
    private static final HandlerList handlers = new HandlerList();
    private final boolean firstJoin;
    private final Player player;
    private final ConfigurationSection metaData;

    public PlayerPostLoadEvent(Player player, ConfigurationSection metaData, boolean firstJoin)
    {
        this.player = player;
        this.metaData = metaData;
        this.firstJoin = firstJoin;
    }

    public Player getPlayer()
    {
        return player;
    }

    public ConfigurationSection getMetaData()
    {
        return metaData;
    }

    public boolean isFirstJoin()
    {
        return firstJoin;
    }

    @Override
    public HandlerList getHandlers()
    {
        return handlers;
    }

    public static HandlerList getHandlerList()
    {
        return handlers;
    }
}