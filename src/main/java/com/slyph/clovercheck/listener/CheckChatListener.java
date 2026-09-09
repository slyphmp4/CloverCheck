package com.slyph.clovercheck.listener;

import com.slyph.clovercheck.chat.CheckChatService;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

public final class CheckChatListener implements Listener {
    private final CheckChatService chat;

    public CheckChatListener(CheckChatService chat) {
        this.chat = chat;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (chat.intercept(event.getPlayer().getUniqueId(), event.message())) {
            event.setCancelled(true);
        }
    }
}
