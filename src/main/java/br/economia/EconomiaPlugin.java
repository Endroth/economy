package br.economia;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.text.NumberFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class EconomiaPlugin extends JavaPlugin implements Listener {

    private final Map<UUID, Double> saldos = new HashMap<>();
    private final Map<UUID, String> nomes = new HashMap<>();
    private File dataFile;
    private boolean sujo = false;

    private NumberFormat fmt;
    private String simbolo = "$";
    private String prefixo = "&6[Economia] &f";
    private double saldoInicial = 100;

    public Precos precos;
    public LojaGui loja;
    public VenderGui vender;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        precos = new Precos(this);
        loja = new LojaGui(this);
        vender = new VenderGui(this);
        carregarDados();
        recarregar();

        Comandos cmd = new Comandos(this);
        for (String nome : List.of("saldo", "pagar", "rank", "vender", "preco", "precos", "setpreco", "eco", "loja")) {
            var pc = getCommand(nome);
            if (pc != null) {
                pc.setExecutor(cmd);
                pc.setTabCompleter(cmd);
            }
        }

        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getPluginManager().registerEvents(loja, this);
        Bukkit.getPluginManager().registerEvents(vender, this);

        // salva a cada 60 segundos se houve mudanca
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (sujo) salvar();
        }, 1200L, 1200L);
    }

    @Override
    public void onDisable() {
        salvar();
    }

    /** Recarrega config, precos e loja (nao mexe nos saldos). */
    public void recarregar() {
        reloadConfig();
        simbolo = getConfig().getString("simbolo", "$");
        prefixo = getConfig().getString("prefixo", "&6[Economia] &f");
        saldoInicial = getConfig().getDouble("saldo-inicial", 100);
        fmt = NumberFormat.getNumberInstance(Locale.forLanguageTag("pt-BR"));
        fmt.setMinimumFractionDigits(2);
        fmt.setMaximumFractionDigits(2);
        precos.carregar();
        loja.carregar();
    }

    // ---------------- dados ----------------

    private void carregarDados() {
        dataFile = new File(getDataFolder(), "data.yml");
        saldos.clear();
        nomes.clear();
        if (!dataFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection sec = y.getConfigurationSection("jogadores");
        if (sec == null) return;
        for (String k : sec.getKeys(false)) {
            try {
                UUID id = UUID.fromString(k);
                saldos.put(id, sec.getDouble(k + ".saldo"));
                String n = sec.getString(k + ".nome");
                if (n != null) nomes.put(id, n);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    public void salvar() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, Double> e : saldos.entrySet()) {
            String k = "jogadores." + e.getKey();
            y.set(k + ".saldo", e.getValue());
            y.set(k + ".nome", nomes.get(e.getKey()));
        }
        try {
            getDataFolder().mkdirs();
            y.save(dataFile);
            sujo = false;
        } catch (IOException ex) {
            getLogger().severe("Nao consegui salvar data.yml: " + ex.getMessage());
        }
    }

    @EventHandler
    public void aoEntrar(PlayerJoinEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        nomes.put(id, e.getPlayer().getName());
        if (!saldos.containsKey(id)) saldos.put(id, arredondar(saldoInicial));
        sujo = true;
    }

    // ---------------- API de dinheiro ----------------

    public static double arredondar(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    public double getSaldo(UUID id) {
        return saldos.getOrDefault(id, 0.0);
    }

    public void setSaldo(UUID id, double valor) {
        saldos.put(id, arredondar(Math.max(0, valor)));
        sujo = true;
    }

    public boolean tem(UUID id, double valor) {
        return getSaldo(id) + 0.0001 >= valor;
    }

    public void add(UUID id, double valor) {
        setSaldo(id, getSaldo(id) + valor);
    }

    public boolean remove(UUID id, double valor) {
        if (!tem(id, valor)) return false;
        setSaldo(id, getSaldo(id) - valor);
        return true;
    }

    public UUID buscarUuid(String nome) {
        Player p = Bukkit.getPlayerExact(nome);
        if (p != null) return p.getUniqueId();
        for (Map.Entry<UUID, String> e : nomes.entrySet()) {
            if (e.getValue().equalsIgnoreCase(nome)) return e.getKey();
        }
        return null;
    }

    public String nomeDe(UUID id) {
        return nomes.getOrDefault(id, "?");
    }

    public List<Map.Entry<UUID, Double>> ranking(int limite) {
        return saldos.entrySet().stream()
                .sorted(Map.Entry.<UUID, Double>comparingByValue().reversed())
                .limit(limite)
                .toList();
    }

    // ---------------- utilidades ----------------

    public String dinheiro(double v) {
        return simbolo + fmt.format(v);
    }

    public String nomeItem(Material m) {
        return m.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    public void msg(CommandSender s, String texto) {
        s.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(prefixo + texto));
    }
}
