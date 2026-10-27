package br.economia;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * /vender abre um bau: coloque os itens la dentro.
 * Fechar (ESC) ou clicar no vidro verde vende tudo que tiver preco.
 * Passando o mouse no botao verde aparece quanto voce vai ganhar.
 */
public class VenderGui implements Listener {

    private static final int LIMITE = 45;              // slots 0-44 = itens
    private static final int[] BOTOES = {48, 49, 50};  // vidro verde

    private final EconomiaPlugin pl;

    static class Holder implements InventoryHolder {
        Inventory inv;

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    public VenderGui(EconomiaPlugin pl) {
        this.pl = pl;
    }

    private static Component linha(String texto) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(texto)
                .decoration(TextDecoration.ITALIC, false);
    }

    private boolean vendavel(ItemStack it) {
        return pl.precos.get(it.getType()) != null && !it.hasItemMeta();
    }

    public void abrir(Player p) {
        Holder h = new Holder();
        Inventory inv = Bukkit.createInventory(h, 54, Component.text("Vender itens"));
        h.inv = inv;

        ItemStack cinza = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta m = cinza.getItemMeta();
        m.displayName(linha("&7 "));
        cinza.setItemMeta(m);
        for (int i = LIMITE; i < 54; i++) inv.setItem(i, cinza);

        atualizar(inv);
        p.openInventory(inv);
    }

    /** Recalcula o valor e atualiza o botao verde. */
    private void atualizar(Inventory inv) {
        double total = 0;
        int vender = 0;
        int devolver = 0;
        for (int i = 0; i < LIMITE; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType().isAir()) continue;
            if (vendavel(it)) {
                total += pl.precos.get(it.getType()) * it.getAmount();
                vender += it.getAmount();
            } else {
                devolver += it.getAmount();
            }
        }
        total = EconomiaPlugin.arredondar(total);

        ItemStack botao = new ItemStack(Material.LIME_STAINED_GLASS_PANE);
        ItemMeta meta = botao.getItemMeta();
        meta.displayName(linha("&a&lVENDER"));
        List<Component> lore = new ArrayList<>();
        lore.add(linha(""));
        lore.add(linha("&7Voce vai ganhar: &a" + pl.dinheiro(total)));
        lore.add(linha("&7Itens para vender: &f" + vender));
        if (devolver > 0) {
            lore.add(linha("&c" + devolver + " item(ns) sem preco serao devolvidos"));
        }
        lore.add(linha(""));
        lore.add(linha("&eClique aqui ou feche (ESC) para vender"));
        meta.lore(lore);
        botao.setItemMeta(meta);

        for (int b : BOTOES) inv.setItem(b, botao);
    }

    private void atualizarDepois(Player p, Inventory top) {
        Bukkit.getScheduler().runTask(pl, () -> {
            if (p.getOpenInventory().getTopInventory() == top) atualizar(top);
        });
    }

    @EventHandler
    public void aoClicar(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof Holder)) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;

        // evita puxar os vidros da barra com duplo clique
        if (e.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
            e.setCancelled(true);
            return;
        }

        if (e.getClickedInventory() == top && e.getRawSlot() >= LIMITE) {
            e.setCancelled(true);
            for (int b : BOTOES) {
                if (e.getRawSlot() == b) {
                    Bukkit.getScheduler().runTask(pl, () -> p.closeInventory()); // o fechar vende
                    return;
                }
            }
            return;
        }

        atualizarDepois(p, top);
    }

    @EventHandler
    public void aoArrastar(InventoryDragEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof Holder)) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;
        for (int s : e.getRawSlots()) {
            if (s >= LIMITE && s < top.getSize()) {
                e.setCancelled(true);
                return;
            }
        }
        atualizarDepois(p, top);
    }

    @EventHandler
    public void aoFechar(InventoryCloseEvent e) {
        Inventory inv = e.getInventory();
        if (!(inv.getHolder() instanceof Holder)) return;
        if (!(e.getPlayer() instanceof Player p)) return;

        double total = 0;
        int vendidos = 0;
        List<ItemStack> devolver = new ArrayList<>();
        for (int i = 0; i < LIMITE; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType().isAir()) continue;
            inv.setItem(i, null);
            if (vendavel(it)) {
                total += pl.precos.get(it.getType()) * it.getAmount();
                vendidos += it.getAmount();
            } else {
                devolver.add(it);
            }
        }

        for (ItemStack it : devolver) {
            for (ItemStack sobra : p.getInventory().addItem(it).values()) {
                p.getWorld().dropItemNaturally(p.getLocation(), sobra);
            }
        }

        if (vendidos > 0) {
            total = EconomiaPlugin.arredondar(total);
            pl.add(p.getUniqueId(), total);
            pl.msg(p, "&aVoce vendeu &f" + vendidos + " itens &apor &e" + pl.dinheiro(total) + "&a.");
        }
        if (!devolver.isEmpty()) {
            pl.msg(p, "&7Itens que nao podem ser vendidos foram devolvidos.");
        }
    }
}
