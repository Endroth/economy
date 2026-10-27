package br.economia;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.StonecuttingRecipe;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Precos de venda (precos.yml). Quanto mais dificil o item, mais caro.
 *
 * Como os precos nascem:
 *  1) tabela base (itens naturais, minerios, drops, raros...)
 *  2) itens craftaveis = soma dos ingredientes (usa as receitas reais do servidor)
 *  3) regras por nome (cobre oxidado, descascado, concreto, discos, templates...)
 *  4) o que sobrar recebe o preco padrao
 */
public class Precos {

    private static final double PADRAO = 2.0;

    private static final Set<String> EXCLUIDOS = new HashSet<>(Arrays.asList(
            "BEDROCK", "BARRIER", "LIGHT", "STRUCTURE_BLOCK", "STRUCTURE_VOID", "JIGSAW",
            "DEBUG_STICK", "KNOWLEDGE_BOOK", "REINFORCED_DEEPSLATE", "END_PORTAL_FRAME",
            "SPAWNER", "TRIAL_SPAWNER", "VAULT", "BUDDING_AMETHYST", "PETRIFIED_OAK_SLAB",
            "FARMLAND", "CHORUS_PLANT", "TEST_BLOCK", "TEST_INSTANCE_BLOCK",
            // sempre tem meta, nunca seriam vendidos
            "ENCHANTED_BOOK", "POTION", "SPLASH_POTION", "LINGERING_POTION", "TIPPED_ARROW",
            "SUSPICIOUS_STEW", "FILLED_MAP", "WRITTEN_BOOK"));

    private final EconomiaPlugin pl;
    private final Map<Material, Double> mapa = new HashMap<>();
    private final Map<Material, Double> memo = new HashMap<>();
    private File file;

    public Precos(EconomiaPlugin pl) {
        this.pl = pl;
    }

    public void carregar() {
        file = new File(pl.getDataFolder(), "precos.yml");
        mapa.clear();
        if (!file.exists()) {
            int n = preencherFaltando();
            salvar();
            pl.getLogger().info("precos.yml criado com " + n + " itens.");
            return;
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection sec = y.getConfigurationSection("precos");
        if (sec == null) return;
        for (String k : sec.getKeys(false)) {
            Material m = Material.matchMaterial(k);
            if (m != null && m.isItem() && !m.isAir()) {
                mapa.put(m, sec.getDouble(k));
            } else {
                pl.getLogger().warning("Item invalido em precos.yml: " + k);
            }
        }
    }

    public void salvar() {
        YamlConfiguration y = new YamlConfiguration();
        y.options().setHeader(List.of(
                "Precos de venda (por unidade).",
                "Edite aqui ou use /setpreco no jogo.",
                "Se editar na mao, use /eco reload para aplicar."));
        for (Map.Entry<Material, Double> e : lista()) {
            y.set("precos." + e.getKey().name(), e.getValue());
        }
        try {
            pl.getDataFolder().mkdirs();
            y.save(file);
        } catch (IOException ex) {
            pl.getLogger().severe("Nao consegui salvar precos.yml: " + ex.getMessage());
        }
    }

    public Double get(Material m) {
        return mapa.get(m);
    }

    public void set(Material m, double v) {
        mapa.put(m, EconomiaPlugin.arredondar(v));
        salvar();
    }

    public boolean remover(Material m) {
        boolean r = mapa.remove(m) != null;
        if (r) salvar();
        return r;
    }

    /** Ordenado do mais barato ao mais caro. */
    public List<Map.Entry<Material, Double>> lista() {
        List<Map.Entry<Material, Double>> l = new ArrayList<>(mapa.entrySet());
        l.sort(Map.Entry.<Material, Double>comparingByValue().thenComparing(e -> e.getKey().name()));
        return l;
    }

    // ---------------- preenchimento automatico ----------------

    /**
     * Da preco a todo item que ainda nao tem (nao mexe nos que ja tem).
     * Retorna quantos itens foram adicionados.
     */
    public int preencherFaltando() {
        int antes = mapa.size();
        memo.clear();
        padroes();

        List<String> semRegra = new ArrayList<>();
        for (Material m : Material.values()) {
            if (m.isLegacy() || !m.isItem() || m.isAir() || excluido(m) || mapa.containsKey(m)) continue;
            Double v = null;
            try {
                v = calcular(m, new HashSet<>());
            } catch (Exception ex) {
                pl.getLogger().warning("Erro calculando preco de " + m.name() + ": " + ex.getMessage());
            }
            if (v == null) {
                v = PADRAO;
                semRegra.add(m.name());
            }
            mapa.put(m, Math.max(0.1, EconomiaPlugin.arredondar(v)));
        }
        if (!semRegra.isEmpty()) {
            pl.getLogger().info(semRegra.size() + " itens sem regra receberam o preco padrao (" + PADRAO
                    + "). Ajuste com /setpreco se quiser: "
                    + String.join(", ", semRegra.subList(0, Math.min(40, semRegra.size()))));
        }
        memo.clear();
        return mapa.size() - antes;
    }

    private boolean excluido(Material m) {
        String n = m.name();
        return n.endsWith("_SPAWN_EGG") || n.startsWith("INFESTED_") || n.contains("COMMAND_BLOCK")
                || EXCLUIDOS.contains(n);
    }

    private Double calcular(Material m, Set<Material> pila) {
        Double ex = mapa.get(m);
        if (ex != null) return ex;
        Double guardado = memo.get(m);
        if (guardado != null) return guardado;
        if (!pila.add(m)) return null; // ciclo de receitas

        Double melhor = null;
        try {
            for (Recipe r : Bukkit.getRecipesFor(new ItemStack(m))) {
                Double v = precoReceita(r, pila);
                if (v != null && (melhor == null || v < melhor)) melhor = v;
            }
            if (melhor == null) melhor = fallback(m, pila);
        } finally {
            pila.remove(m);
        }
        if (melhor != null) memo.put(m, melhor);
        return melhor;
    }

    private Double precoReceita(Recipe r, Set<Material> pila) {
        int qtd = Math.max(1, r.getResult().getAmount());
        double soma = 0;

        if (r instanceof ShapedRecipe sr) {
            Map<Character, RecipeChoice> escolhas = sr.getChoiceMap();
            for (String linha : sr.getShape()) {
                for (char c : linha.toCharArray()) {
                    if (c == ' ') continue;
                    RecipeChoice rc = escolhas.get(c);
                    if (rc == null) continue;
                    Double v = precoChoice(rc, pila);
                    if (v == null) return null;
                    soma += v;
                }
            }
            return soma / qtd;
        }
        if (r instanceof ShapelessRecipe sl) {
            for (RecipeChoice rc : sl.getChoiceList()) {
                Double v = precoChoice(rc, pila);
                if (v == null) return null;
                soma += v;
            }
            return soma / qtd;
        }
        if (r instanceof CookingRecipe<?> cr) {
            Double v = precoChoice(cr.getInputChoice(), pila);
            return v == null ? null : (v * 1.25) / qtd; // um pouco mais caro por ter gasto combustivel
        }
        if (r instanceof StonecuttingRecipe st) {
            Double v = precoChoice(st.getInputChoice(), pila);
            return v == null ? null : v / qtd;
        }
        return null;
    }

    /** Preco do ingrediente mais barato entre as opcoes aceitas. */
    private Double precoChoice(RecipeChoice rc, Set<Material> pila) {
        List<Material> opcoes = new ArrayList<>();
        if (rc instanceof RecipeChoice.MaterialChoice mc) {
            opcoes.addAll(mc.getChoices());
        } else if (rc instanceof RecipeChoice.ExactChoice ec) {
            for (ItemStack it : ec.getChoices()) opcoes.add(it.getType());
        }
        Double min = null;
        for (Material op : opcoes) {
            Double v = calcular(op, pila);
            if (v != null && (min == null || v < min)) min = v;
        }
        return min;
    }

    private Double porNome(String nome, Set<Material> pila) {
        Material m = Material.matchMaterial(nome);
        return m == null ? null : calcular(m, pila);
    }

    /** Regras por nome para o que nao tem receita. */
    private Double fallback(Material m, Set<Material> pila) {
        String n = m.name();

        for (String pre : new String[]{"STRIPPED_", "EXPOSED_", "WEATHERED_", "OXIDIZED_"}) {
            if (n.startsWith(pre)) return porNome(n.substring(pre.length()), pila);
        }
        if (n.startsWith("NETHERITE_")) {
            Double d = porNome("DIAMOND_" + n.substring(10), pila);
            if (d != null) return d + 1000; // item de diamante + lingote + template
        }
        if (n.startsWith("CHAINMAIL_")) return porNome("IRON_" + n.substring(10), pila);
        if (n.endsWith("_CONCRETE")) return porNome(n + "_POWDER", pila);
        if (n.endsWith("_BUCKET") && !n.equals("BUCKET")) {
            Double b = calcular(Material.BUCKET, pila);
            if (b != null) return b + 10;
        }
        if (n.contains("CORAL")) return n.startsWith("DEAD_") ? 3.0 : 4.0;
        if (n.endsWith("_SAPLING") || n.equals("MANGROVE_PROPAGULE")) return 1.0;
        if (n.endsWith("_LEAVES")) return 0.2;
        if (n.startsWith("MUSIC_DISC_")) return 150.0;
        if (n.endsWith("_SMITHING_TEMPLATE")) return 250.0;
        if (n.endsWith("_POTTERY_SHERD")) return 60.0;
        if (n.endsWith("_BANNER_PATTERN")) return 30.0;
        if (n.endsWith("FROGLIGHT")) return 60.0;
        if (n.endsWith("MUSHROOM_BLOCK")) return 5.0;
        return null;
    }

    // ---------------- tabela base ----------------

    private void p(double valor, String... nomes) {
        for (String n : nomes) {
            Material m = Material.matchMaterial(n);
            if (m != null && m.isItem() && !m.isAir()) mapa.putIfAbsent(m, valor);
        }
    }

    private void padroes() {
        // ===== Comuns (baratos) =====
        p(0.1, "DIRT", "COARSE_DIRT", "NETHERRACK", "SNOWBALL", "SHORT_GRASS", "GRASS", "TALL_GRASS",
                "FERN", "LARGE_FERN");
        p(0.2, "COBBLESTONE", "STONE", "SAND", "RED_SAND", "GRAVEL", "GRANITE", "DIORITE", "ANDESITE",
                "GRASS_BLOCK", "MUD", "DEAD_BUSH");
        p(0.3, "COBBLED_DEEPSLATE", "DEEPSLATE", "TUFF", "BASALT", "BLACKSTONE", "WHEAT_SEEDS",
                "PUMPKIN_SEEDS", "MELON_SEEDS", "BEETROOT_SEEDS");
        p(0.5, "OAK_LOG", "SPRUCE_LOG", "BIRCH_LOG", "JUNGLE_LOG", "ACACIA_LOG", "DARK_OAK_LOG",
                "MANGROVE_LOG", "CHERRY_LOG", "PALE_OAK_LOG", "END_STONE", "FLINT", "CLAY_BALL",
                "ROTTEN_FLESH", "FEATHER", "ROOTED_DIRT", "VINE", "SEAGRASS", "MANGROVE_ROOTS",
                // flores
                "DANDELION", "POPPY", "BLUE_ORCHID", "ALLIUM", "AZURE_BLUET", "RED_TULIP", "ORANGE_TULIP",
                "WHITE_TULIP", "PINK_TULIP", "OXEYE_DAISY", "CORNFLOWER", "LILY_OF_THE_VALLEY", "LILAC",
                "ROSE_BUSH", "PEONY", "SUNFLOWER", "PINK_PETALS", "WILDFLOWERS", "LEAF_LITTER");
        p(1, "PODZOL", "SOUL_SAND", "SOUL_SOIL", "CALCITE", "DRIPSTONE_BLOCK", "GLOW_LICHEN",
                "HANGING_ROOTS", "NETHER_SPROUTS", "CRIMSON_ROOTS", "WARPED_ROOTS", "SCULK_VEIN",
                "ICE", "LILY_PAD", "POISONOUS_POTATO");
        p(1.5, "CRIMSON_STEM", "WARPED_STEM", "MUDDY_MANGROVE_ROOTS");
        p(2, "MOSS_BLOCK", "AZALEA", "SMALL_DRIPLEAF", "BIG_DRIPLEAF", "WEEPING_VINES", "TWISTING_VINES",
                "POINTED_DRIPSTONE");
        p(3, "MYCELIUM", "CRIMSON_NYLIUM", "WARPED_NYLIUM", "CRIMSON_FUNGUS", "WARPED_FUNGUS",
                "FLOWERING_AZALEA", "PACKED_ICE", "TROPICAL_FISH", "GLOWSTONE_DUST");
        p(4, "PUFFERFISH", "WARPED_WART_BLOCK", "CLOSED_EYEBLOSSOM");
        p(5, "SCULK", "OPEN_EYEBLOSSOM");

        // ===== Drops de mobs =====
        p(1, "BONE", "STRING");
        p(1.5, "LEATHER", "RABBIT_HIDE");
        p(2, "SPIDER_EYE");
        p(3, "GUNPOWDER", "PRISMARINE_SHARD", "INK_SAC");
        p(4, "SLIME_BALL");
        p(6, "MAGMA_CREAM");
        p(10, "BLAZE_ROD", "FROGSPAWN");
        p(12, "WIND_CHARGE");
        p(15, "ENDER_PEARL", "PHANTOM_MEMBRANE", "TURTLE_SCUTE", "SCUTE", "ARMADILLO_SCUTE", "SHROOMLIGHT",
                "CHORUS_FLOWER");
        p(20, "GLOW_INK_SAC", "SPORE_BLOSSOM", "TORCHFLOWER_SEEDS", "PITCHER_POD", "AMETHYST_CLUSTER");
        p(10, "LARGE_AMETHYST_BUD", "MEDIUM_AMETHYST_BUD", "SMALL_AMETHYST_BUD");
        p(30, "TURTLE_EGG", "SUSPICIOUS_SAND", "SUSPICIOUS_GRAVEL");
        p(40, "GHAST_TEAR", "NAUTILUS_SHELL", "TORCHFLOWER", "PITCHER_PLANT", "BEE_NEST", "TRIAL_KEY",
                "CRYING_OBSIDIAN", "DISC_FRAGMENT_5", "SCULK_SENSOR");
        p(60, "SHULKER_SHELL", "RABBIT_FOOT", "BREEZE_ROD", "EXPERIENCE_BOTTLE", "SCULK_CATALYST");
        p(80, "GOAT_HORN", "NAME_TAG", "SCULK_SHRIEKER");
        p(100, "SADDLE", "OMINOUS_TRIAL_KEY", "PLAYER_HEAD", "ZOMBIE_HEAD", "CREEPER_HEAD", "SKELETON_SKULL");
        p(120, "ECHO_SHARD", "WITHER_ROSE");
        p(150, "OMINOUS_BOTTLE", "DRAGON_BREATH", "PIGLIN_HEAD");

        // ===== Farms (um pouco acima dos comuns) =====
        p(1.5, "MELON_SLICE", "BAMBOO", "KELP");
        p(2, "WHEAT", "CARROT", "POTATO", "BEETROOT", "SWEET_BERRIES", "GLOW_BERRIES",
                "BROWN_MUSHROOM", "RED_MUSHROOM", "EGG", "BEEF", "PORKCHOP", "CHICKEN",
                "MUTTON", "RABBIT", "COD", "SALMON");
        p(3, "SUGAR_CANE", "CACTUS", "COCOA_BEANS", "CHORUS_FRUIT", "APPLE");
        p(4, "PUMPKIN", "SEA_PICKLE", "NETHER_WART");
        p(6, "HONEYCOMB");
        p(8, "HONEY_BOTTLE");
        p(12, "MELON");

        // ===== Minerios =====
        p(2, "COAL", "RAW_COPPER");
        p(3, "COPPER_INGOT", "REDSTONE", "COAL_ORE");
        p(3.5, "DEEPSLATE_COAL_ORE");
        p(1.5, "CHARCOAL");
        p(4, "LAPIS_LAZULI", "QUARTZ", "AMETHYST_SHARD");
        p(5, "COPPER_ORE", "NETHER_QUARTZ_ORE");
        p(5.5, "DEEPSLATE_COPPER_ORE");
        p(6, "RAW_IRON", "NETHER_GOLD_ORE", "REDSTONE_ORE");
        p(6.5, "DEEPSLATE_REDSTONE_ORE");
        p(8, "IRON_INGOT", "OBSIDIAN", "IRON_ORE", "LAPIS_ORE");
        p(9, "RAW_GOLD", "DEEPSLATE_IRON_ORE", "DEEPSLATE_LAPIS_ORE");
        p(12, "GOLD_INGOT");
        p(14, "GOLD_ORE");
        p(15, "DEEPSLATE_GOLD_ORE");
        p(18, "COAL_BLOCK");
        p(25, "EMERALD");
        p(27, "COPPER_BLOCK", "REDSTONE_BLOCK");
        p(30, "EMERALD_ORE", "SPONGE", "WET_SPONGE");
        p(35, "DEEPSLATE_EMERALD_ORE");
        p(36, "LAPIS_BLOCK");
        p(72, "IRON_BLOCK");
        p(80, "DIAMOND");
        p(90, "DIAMOND_ORE");
        p(100, "DEEPSLATE_DIAMOND_ORE");
        p(108, "GOLD_BLOCK");
        p(225, "EMERALD_BLOCK");
        p(720, "DIAMOND_BLOCK");

        // ===== Itens raros / dificeis =====
        p(100, "GOLDEN_APPLE", "ANCIENT_DEBRIS", "NETHERITE_SCRAP");
        p(150, "GILDED_BLACKSTONE");
        p(200, "TOTEM_OF_UNDYING", "GOLDEN_HORSE_ARMOR");
        p(120, "IRON_HORSE_ARMOR");
        p(40, "LEATHER_HORSE_ARMOR");
        p(500, "DIAMOND_HORSE_ARMOR", "HEART_OF_THE_SEA");
        p(300, "WITHER_SKELETON_SKULL", "CREAKING_HEART");
        p(400, "TRIDENT", "NETHERITE_UPGRADE_SMITHING_TEMPLATE", "SNIFFER_EGG");
        p(600, "NETHERITE_INGOT");
        p(800, "ENCHANTED_GOLDEN_APPLE");
        p(1200, "DRAGON_HEAD");
        p(2000, "HEAVY_CORE");
        p(5400, "NETHERITE_BLOCK");
        p(6000, "NETHER_STAR");
        p(20000, "DRAGON_EGG");
        p(25000, "ELYTRA");
    }
}
