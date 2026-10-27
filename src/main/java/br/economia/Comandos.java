package br.economia;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public class Comandos implements CommandExecutor, TabCompleter {

    private final EconomiaPlugin pl;
    private List<String> cacheMateriais;

    public Comandos(EconomiaPlugin pl) {
        this.pl = pl;
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        switch (c.getName().toLowerCase(Locale.ROOT)) {
            case "saldo" -> saldo(s, a);
            case "pagar" -> pagar(s, a);
            case "rank" -> rank(s);
            case "vender" -> vender(s, a);
            case "preco" -> preco(s);
            case "precos" -> precos(s, a);
            case "setpreco" -> setpreco(s, a);
            case "eco" -> eco(s, a);
            case "loja" -> loja(s, a);
            default -> {
            }
        }
        return true;
    }

    // ---------------- helpers ----------------

    private Player jogador(CommandSender s) {
        if (s instanceof Player p) return p;
        pl.msg(s, "&cApenas jogadores podem usar esse comando.");
        return null;
    }

    private boolean admin(CommandSender s) {
        if (s.hasPermission("economia.admin")) return true;
        pl.msg(s, "&cVoce nao tem permissao.");
        return false;
    }

    /** Aceita 10, 10.5, 10,5, 2k, 1.5m */
    private Double parse(String t) {
        t = t.toLowerCase(Locale.ROOT).replace(",", ".");
        double mult = 1;
        if (t.endsWith("k")) {
            mult = 1_000;
            t = t.substring(0, t.length() - 1);
        } else if (t.endsWith("m")) {
            mult = 1_000_000;
            t = t.substring(0, t.length() - 1);
        }
        try {
            double v = Double.parseDouble(t) * mult;
            if (Double.isNaN(v) || Double.isInfinite(v) || v > 1_000_000_000_000.0) return null;
            return EconomiaPlugin.arredondar(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Material itemValido(String nome) {
        Material m = Material.matchMaterial(nome);
        if (m == null || m.isAir() || !m.isItem()) return null;
        return m;
    }

    // ---------------- comandos de jogador ----------------

    private void saldo(CommandSender s, String[] a) {
        if (a.length == 0) {
            Player p = jogador(s);
            if (p == null) return;
            pl.msg(p, "&7Seu saldo: &a" + pl.dinheiro(pl.getSaldo(p.getUniqueId())));
            return;
        }
        UUID id = pl.buscarUuid(a[0]);
        if (id == null) {
            pl.msg(s, "&cJogador nao encontrado.");
            return;
        }
        pl.msg(s, "&7Saldo de &f" + pl.nomeDe(id) + "&7: &a" + pl.dinheiro(pl.getSaldo(id)));
    }

    private void pagar(CommandSender s, String[] a) {
        Player p = jogador(s);
        if (p == null) return;
        if (a.length < 2) {
            pl.msg(p, "&cUse: /pagar <jogador> <valor>");
            return;
        }
        UUID alvo = pl.buscarUuid(a[0]);
        if (alvo == null) {
            pl.msg(p, "&cJogador nao encontrado.");
            return;
        }
        if (alvo.equals(p.getUniqueId())) {
            pl.msg(p, "&cVoce nao pode pagar a si mesmo.");
            return;
        }
        Double valor = parse(a[1]);
        if (valor == null || valor < 0.01) {
            pl.msg(p, "&cValor invalido.");
            return;
        }
        if (!pl.remove(p.getUniqueId(), valor)) {
            pl.msg(p, "&cVoce nao tem saldo suficiente.");
            return;
        }
        pl.add(alvo, valor);
        pl.msg(p, "&aVoce enviou &e" + pl.dinheiro(valor) + " &apara &f" + pl.nomeDe(alvo) + "&a.");
        Player online = Bukkit.getPlayer(alvo);
        if (online != null) {
            pl.msg(online, "&aVoce recebeu &e" + pl.dinheiro(valor) + " &ade &f" + p.getName() + "&a.");
        }
    }

    private void rank(CommandSender s) {
        List<Map.Entry<UUID, Double>> top = pl.ranking(10);
        pl.msg(s, "&6&lJogadores mais ricos");
        int i = 1;
        for (Map.Entry<UUID, Double> e : top) {
            pl.msg(s, "&e#" + i + " &f" + pl.nomeDe(e.getKey()) + " &7- &a" + pl.dinheiro(e.getValue()));
            i++;
        }
    }

    private void vender(CommandSender s, String[] a) {
        Player p = jogador(s);
        if (p == null) return;
        String modo = a.length == 0 ? "gui" : a[0].toLowerCase(Locale.ROOT);
        switch (modo) {
            case "gui", "bau" -> pl.vender.abrir(p);
            case "mao", "hand" -> venderMao(p);
            case "tudo", "all" -> venderTudo(p);
            default -> pl.msg(p, "&cUse: /vender, /vender mao ou /vender tudo");
        }
    }

    private void venderMao(Player p) {
        ItemStack it = p.getInventory().getItemInMainHand();
        if (it.getType().isAir()) {
            pl.msg(p, "&cSegure um item na mao.");
            return;
        }
        Double preco = pl.precos.get(it.getType());
        if (preco == null || it.hasItemMeta()) {
            pl.msg(p, "&cEsse item nao pode ser vendido.");
            return;
        }
        int qtd = it.getAmount();
        double total = EconomiaPlugin.arredondar(preco * qtd);
        p.getInventory().setItemInMainHand(null);
        pl.add(p.getUniqueId(), total);
        pl.msg(p, "&aVoce vendeu &f" + qtd + "x " + pl.nomeItem(it.getType()) + " &apor &e" + pl.dinheiro(total) + "&a.");
    }

    private void venderTudo(Player p) {
        PlayerInventory inv = p.getInventory();
        ItemStack[] conteudo = inv.getStorageContents();
        double total = 0;
        int itens = 0;
        for (int i = 0; i < conteudo.length; i++) {
            ItemStack it = conteudo[i];
            if (it == null || it.getType().isAir() || it.hasItemMeta()) continue;
            Double preco = pl.precos.get(it.getType());
            if (preco == null) continue;
            total += preco * it.getAmount();
            itens += it.getAmount();
            conteudo[i] = null;
        }
        if (itens == 0) {
            pl.msg(p, "&cNenhum item vendavel no seu inventario.");
            return;
        }
        total = EconomiaPlugin.arredondar(total);
        inv.setStorageContents(conteudo);
        pl.add(p.getUniqueId(), total);
        pl.msg(p, "&aVoce vendeu &f" + itens + " itens &apor &e" + pl.dinheiro(total) + "&a.");
    }

    private void preco(CommandSender s) {
        Player p = jogador(s);
        if (p == null) return;
        Material m = p.getInventory().getItemInMainHand().getType();
        if (m.isAir()) {
            pl.msg(p, "&cSegure um item na mao.");
            return;
        }
        Double venda = pl.precos.get(m);
        Double compra = pl.loja.getPreco(m);
        pl.msg(p, "&7Item: &f" + pl.nomeItem(m));
        pl.msg(p, "&7Venda: " + (venda == null ? "&cnao pode ser vendido" : "&a" + pl.dinheiro(venda)));
        pl.msg(p, "&7Na loja: " + (compra == null ? "&cnao esta a venda" : "&a" + pl.dinheiro(compra)));
    }

    private void precos(CommandSender s, String[] a) {
        List<Map.Entry<Material, Double>> lista = pl.precos.lista();
        int porPagina = 12;
        int paginas = Math.max(1, (lista.size() + porPagina - 1) / porPagina);
        int pagina = 1;
        if (a.length > 0) {
            try {
                pagina = Integer.parseInt(a[0]);
            } catch (NumberFormatException ignored) {
            }
        }
        pagina = Math.max(1, Math.min(pagina, paginas));
        pl.msg(s, "&6&lPrecos de venda &7(pagina " + pagina + "/" + paginas + ")");
        for (int i = (pagina - 1) * porPagina; i < Math.min(lista.size(), pagina * porPagina); i++) {
            Map.Entry<Material, Double> e = lista.get(i);
            pl.msg(s, "&f" + pl.nomeItem(e.getKey()) + " &7- &a" + pl.dinheiro(e.getValue()));
        }
        if (pagina < paginas) pl.msg(s, "&7Use /precos " + (pagina + 1) + " para ver mais.");
    }

    // ---------------- comandos de admin ----------------

    private void setpreco(CommandSender s, String[] a) {
        if (!admin(s)) return;
        Material m;
        String v;
        if (a.length == 1) {
            Player p = jogador(s);
            if (p == null) return;
            m = p.getInventory().getItemInMainHand().getType();
            v = a[0];
        } else if (a.length == 2) {
            m = itemValido(a[0]);
            v = a[1];
        } else {
            pl.msg(s, "&cUse: /setpreco <valor> (item na mao) ou /setpreco <item> <valor>");
            pl.msg(s, "&7Para tirar o preco: /setpreco <item> remover");
            return;
        }
        if (m == null || m.isAir() || !m.isItem()) {
            pl.msg(s, "&cItem invalido.");
            return;
        }
        if (v.equalsIgnoreCase("remover")) {
            if (pl.precos.remover(m)) pl.msg(s, "&aPreco de &f" + pl.nomeItem(m) + " &aremovido (nao pode mais ser vendido).");
            else pl.msg(s, "&cEsse item ja nao tinha preco.");
            return;
        }
        Double valor = parse(v);
        if (valor == null || valor <= 0) {
            pl.msg(s, "&cValor invalido.");
            return;
        }
        pl.precos.set(m, valor);
        pl.msg(s, "&aPreco de venda de &f" + pl.nomeItem(m) + " &adefinido para &e" + pl.dinheiro(valor) + "&a.");
    }

    private void eco(CommandSender s, String[] a) {
        if (!admin(s)) return;
        if (a.length == 1 && a[0].equalsIgnoreCase("reload")) {
            pl.recarregar();
            pl.msg(s, "&aConfig, precos e loja recarregados.");
            return;
        }
        if (a.length == 1 && a[0].equalsIgnoreCase("preencher")) {
            int n = pl.precos.preencherFaltando();
            pl.precos.salvar();
            pl.msg(s, "&aForam adicionados preco a &f" + n + " &aitens que nao tinham.");
            return;
        }
        if (a.length < 3) {
            pl.msg(s, "&cUse: /eco <give|take|set> <jogador> <valor>, /eco preencher ou /eco reload");
            return;
        }
        UUID alvo = pl.buscarUuid(a[1]);
        if (alvo == null) {
            pl.msg(s, "&cJogador nao encontrado.");
            return;
        }
        Double valor = parse(a[2]);
        if (valor == null || valor < 0) {
            pl.msg(s, "&cValor invalido.");
            return;
        }
        String acao = a[0].toLowerCase(Locale.ROOT);
        switch (acao) {
            case "give" -> pl.add(alvo, valor);
            case "take" -> pl.setSaldo(alvo, pl.getSaldo(alvo) - valor);
            case "set" -> pl.setSaldo(alvo, valor);
            default -> {
                pl.msg(s, "&cUse: /eco <give|take|set> <jogador> <valor>");
                return;
            }
        }
        pl.msg(s, "&aSaldo de &f" + pl.nomeDe(alvo) + " &aagora: &e" + pl.dinheiro(pl.getSaldo(alvo)));
        Player online = Bukkit.getPlayer(alvo);
        if (online != null && !online.equals(s)) {
            pl.msg(online, "&7Seu saldo foi alterado por um admin. Agora: &a" + pl.dinheiro(pl.getSaldo(alvo)));
        }
    }

    private void loja(CommandSender s, String[] a) {
        if (a.length == 0) {
            Player p = jogador(s);
            if (p == null) return;
            pl.loja.abrir(p, 0);
            return;
        }
        if (!admin(s)) return;
        String sub = a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "add", "setpreco" -> {
                // /loja add <preco> [item]
                if (a.length < 2) {
                    pl.msg(s, "&cUse: /loja " + sub + " <preco> [item]  (sem item = item na mao)");
                    return;
                }
                Double valor = parse(a[1]);
                if (valor == null || valor <= 0) {
                    pl.msg(s, "&cPreco invalido.");
                    return;
                }
                Material m = itemDoComando(s, a, 2);
                if (m == null) return;
                if (sub.equals("setpreco") && pl.loja.getPreco(m) == null) {
                    pl.msg(s, "&cEsse item nao esta na loja. Use /loja add.");
                    return;
                }
                pl.loja.set(m, valor);
                pl.msg(s, "&f" + pl.nomeItem(m) + " &aesta na loja por &e" + pl.dinheiro(valor) + "&a.");
            }
            case "remove", "remover" -> {
                // /loja remove [item]
                Material m = itemDoComando(s, a, 1);
                if (m == null) return;
                if (pl.loja.remover(m)) pl.msg(s, "&f" + pl.nomeItem(m) + " &aremovido da loja.");
                else pl.msg(s, "&cEsse item nao esta na loja.");
            }
            default -> pl.msg(s, "&cUse: /loja, /loja add <preco> [item], /loja remove [item], /loja setpreco <preco> [item]");
        }
    }

    /** Item citado no indice idx dos argumentos, ou o item da mao se nao foi citado. */
    private Material itemDoComando(CommandSender s, String[] a, int idx) {
        Material m;
        if (a.length > idx) {
            m = itemValido(a[idx]);
        } else {
            Player p = jogador(s);
            if (p == null) return null;
            m = p.getInventory().getItemInMainHand().getType();
        }
        if (m == null || m.isAir() || !m.isItem()) {
            pl.msg(s, "&cItem invalido. Segure um item ou escreva o nome dele.");
            return null;
        }
        return m;
    }

    // ---------------- tab complete ----------------

    private List<String> materiais() {
        if (cacheMateriais == null) {
            cacheMateriais = Arrays.stream(Material.values())
                    .filter(m -> !m.isLegacy() && m.isItem() && !m.isAir())
                    .map(m -> m.name().toLowerCase(Locale.ROOT))
                    .collect(Collectors.toList());
        }
        return cacheMateriais;
    }

    private List<String> jogadoresOnline() {
        return Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList());
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] a) {
        String cmd = c.getName().toLowerCase(Locale.ROOT);
        boolean adm = s.hasPermission("economia.admin");
        List<String> out = new ArrayList<>();

        switch (cmd) {
            case "saldo", "pagar" -> {
                if (a.length == 1) out = jogadoresOnline();
            }
            case "vender" -> {
                if (a.length == 1) out = List.of("mao", "tudo");
            }
            case "setpreco" -> {
                if (adm && a.length == 1) out = materiais();
                else if (adm && a.length == 2) out = List.of("remover");
            }
            case "eco" -> {
                if (adm) {
                    if (a.length == 1) out = List.of("give", "take", "set", "preencher", "reload");
                    else if (a.length == 2 && !a[0].equalsIgnoreCase("reload") && !a[0].equalsIgnoreCase("preencher")) out = jogadoresOnline();
                }
            }
            case "loja" -> {
                if (adm) {
                    if (a.length == 1) out = List.of("add", "remove", "setpreco");
                    else if (a.length == 2 && (a[0].equalsIgnoreCase("remove") || a[0].equalsIgnoreCase("remover"))) out = materiais();
                    else if (a.length == 3 && (a[0].equalsIgnoreCase("add") || a[0].equalsIgnoreCase("setpreco"))) out = materiais();
                }
            }
            default -> {
            }
        }

        String atual = a[a.length - 1].toLowerCase(Locale.ROOT);
        return out.stream().filter(x -> x.toLowerCase(Locale.ROOT).startsWith(atual)).collect(Collectors.toList());
    }
}
