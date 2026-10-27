package br.economia;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Loja (/loja, /shop) editavel. Os itens ficam em loja.yml. */
public class LojaGui implements Listener {

    private static final int POR_PAGINA = 45;

    private final EconomiaPlugin pl;
    private final Map<Material, Double> itens = new LinkedHashMap<>();
    private File file;

    static class Holder implements InventoryHolder {
        final int pagina;
        Inventory inv;

        Holder(int pagina) {
            this.pagina = pagina;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    public LojaGui(EconomiaPlugin pl) {
        this.pl = pl;
    }

    // ---------------- dados ----------------

    public void carregar() {
        file = new File(pl.getDataFolder(), "loja.yml");
        itens.clear();
        if (!file.exists()) {
            padroes();
            salvar();
            return;
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection sec = y.getConfigurationSection("itens");
        if (sec == null) return;
        for (String k : sec.getKeys(false)) {
            Material m = Material.matchMaterial(k);
            if (m != null && m.isItem() && !m.isAir()) {
                itens.put(m, sec.getDouble(k));
            } else {
                pl.getLogger().warning("Item invalido em loja.yml: " + k);
            }
        }
    }

    public void salvar() {
        YamlConfiguration y = new YamlConfiguration();
        y.options().setHeader(List.of(
                "Itens da loja e preco de COMPRA (por unidade).",
                "Edite aqui ou use /loja add, /loja remove, /loja setpreco no jogo.",
                "Se editar na mao, use /eco reload para aplicar."));
        for (Map.Entry<Material, Double> e : itens.entrySet()) {
            y.set("itens." + e.getKey().name(), e.getValue());
        }
        try {
            pl.getDataFolder().mkdirs();
            y.save(file);
        } catch (IOException ex) {
            pl.getLogger().severe("Nao consegui salvar loja.yml: " + ex.getMessage());
        }
    }

    private void padroes() {
        put("BREAD", 10);
        put("COOKED_BEEF", 15);
        put("TORCH", 5);
        put("OAK_LOG", 8);
        put("ARROW", 2);
        put("SHIELD", 50);
        put("IRON_PICKAXE", 80);
        put("ENDER_PEARL", 40);
    }

    private void put(String nome, double preco) {
        Material m = Material.matchMaterial(nome);
        if (m != null && m.isItem() && !m.isAir()) itens.put(m, preco);
    }

    public Double getPreco(Material m) {
        return itens.get(m);
    }

    public void set(Material m, double preco) {
        itens.put(m, EconomiaPlugin.arredondar(preco));
        salvar();
    }

    public boolean remover(Material m) {
        boolean r = itens.remove(m) != null;
        if (r) salvar();
        return r;
    }

    // ---------------- GUI ----------------

    private static Component linha(String texto) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(texto)
                .decoration(TextDecoration.ITALIC, false);
    }

    private ItemStack itemSaldo(Player p) {
        ItemStack it = new ItemStack(Material.GOLD_INGOT);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(linha("&6Seu saldo: &a" + pl.dinheiro(pl.getSaldo(p.getUniqueId()))));
        it.setItemMeta(meta);
        return it;
    }

    private ItemStack itemNav(String nome) {
        ItemStack it = new ItemStack(Material.ARROW);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(linha(nome));
        it.setItemMeta(meta);
        return it;
    }

    public void abrir(Player p, int pagina) {
        List<Map.Entry<Material, Double>> lista = new ArrayList<>(itens.entrySet());
        int paginas = Math.max(1, (lista.size() + POR_PAGINA - 1) / POR_PAGINA);
        pagina = Math.max(0, Math.min(pagina, paginas - 1));

        Holder h = new Holder(pagina);
        Inventory inv = Bukkit.createInventory(h, 54,
                Component.text("Loja - pagina " + (pagina + 1) + "/" + paginas));
        h.inv = inv;

        for (int i = 0; i < POR_PAGINA; i++) {
            int idx = pagina * POR_PAGINA + i;
            if (idx >= lista.size()) break;
            Map.Entry<Material, Double> e = lista.get(idx);
            ItemStack it = new ItemStack(e.getKey());
            ItemMeta meta = it.getItemMeta();
            meta.lore(List.of(
                    linha("&7Preco: &a" + pl.dinheiro(e.getValue()) + " &7(cada)"),
                    linha(""),
                    linha("&eClique esquerdo: &fcomprar 1"),
                    linha("&eClique direito: &fcomprar 16"),
                    linha("&eShift + clique: &fcomprar 64")));
            it.setItemMeta(meta);
            inv.setItem(i, it);
        }

        if (pagina > 0) inv.setItem(45, itemNav("&e<< Pagina anterior"));
        inv.setItem(49, itemSaldo(p));
        if (pagina < paginas - 1) inv.setItem(53, itemNav("&eProxima pagina >>"));

        p.openInventory(inv);
    }

    @EventHandler
    public void aoClicar(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Holder h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getView().getTopInventory()) return;

        int slot = e.getSlot();
        if (slot == 45) {
            if (h.pagina > 0) Bukkit.getScheduler().runTask(pl, () -> abrir(p, h.pagina - 1));
            return;
        }
        if (slot == 53) {
            ItemStack nav = e.getCurrentItem();
            if (nav != null && nav.getType() == Material.ARROW) {
                Bukkit.getScheduler().runTask(pl, () -> abrir(p, h.pagina + 1));
            }
            return;
        }
        if (slot >= POR_PAGINA) return;

        ItemStack atual = e.getCurrentItem();
        if (atual == null || atual.getType().isAir()) return;
        Double preco = itens.get(atual.getType());
        if (preco == null) return;

        int qtd = e.isShiftClick() ? 64 : (e.isRightClick() ? 16 : 1);
        comprar(p, atual.getType(), preco, qtd);
        e.getView().getTopInventory().setItem(49, itemSaldo(p));
    }

    @EventHandler
    public void aoArrastar(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Holder) e.setCancelled(true);
    }

    private void comprar(Player p, Material m, double preco, int qtd) {
        double total = EconomiaPlugin.arredondar(preco * qtd);
        if (!pl.tem(p.getUniqueId(), total)) {
            pl.msg(p, "&cVoce nao tem dinheiro suficiente. Custa &e" + pl.dinheiro(total) + "&c.");
            return;
        }
        Map<Integer, ItemStack> sobra = p.getInventory().addItem(new ItemStack(m, qtd));
        int sobrou = sobra.values().stream().mapToInt(ItemStack::getAmount).sum();
        int comprou = qtd - sobrou;
        if (comprou <= 0) {
            pl.msg(p, "&cSeu inventario esta cheio.");
            return;
        }
        double custo = EconomiaPlugin.arredondar(preco * comprou);
        pl.remove(p.getUniqueId(), custo);
        pl.msg(p, "&aVoce comprou &f" + comprou + "x " + pl.nomeItem(m) + " &apor &e" + pl.dinheiro(custo) + "&a.");
    }
}
