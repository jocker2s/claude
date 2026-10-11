package dev.claudeingame;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.resource.ResourcePackManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public class ClaudeInGame implements ModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("claude_in_game");

	private static final String SYSTEM_PROMPT = """
			Ты — Claude, встроенный в Minecraft Java Edition 1.20.1 (Fabric). Игрок просит тебя изменить игру, \
			и ты делаешь это через ДАТАПАК (pack_format 15). Твои файлы попадают в датапак world/datapacks/claude_generated \
			и сразу применяются через /reload.

			Что можно: рецепты (data/<ns>/recipes), лут-таблицы, достижения, теги, предикаты, модификаторы предметов, \
			функции .mcfunction (data/<ns>/functions), теги функций (в том числе minecraft:tick и minecraft:load), \
			структуры-настройки, измерения и т.д. — всё, что поддерживают датапаки 1.20.1.
			Чего нельзя: добавлять новые блоки/предметы/мобов с нуля и менять Java-код. Вместо этого используй \
			CustomModelData, теги NBT, команды, scoreboard, marker-сущности, display-сущности и т.п. \
			Если задача невыполнима датапаком, честно скажи об этом в поле message.

			Правила:
			- Используй своё пространство имён "claude" (data/claude/...), кроме случаев, когда нужно переопределить ванильное.
			- Не перезаписывай data/minecraft/tags/functions/tick.json и load.json целиком, если не уверен, что там нет чужого содержимого; \
			  лучше создай файл-тег в своём namespace и вызови его через load/tick с "replace": false.
			- Разрешены только расширения .json, .mcfunction, .txt. Каждый файл — полный, рабочий, без заглушек.
			- Пути строго вида "data/<namespace>/...". Без "..".

			ФОРМАТ ОТВЕТА — строго один JSON-объект и ничего вне него:
			{"message": "краткое объяснение для игрока на языке игрока", "files": [{"path": "data/claude/recipes/example.json", "content": "...содержимое файла строкой..."}]}
			Если файлы не нужны (вопрос или отказ), верни "files": [].
			""";

	private final Map<String, List<JsonObject>> histories = new ConcurrentHashMap<>();
	private Config config;

	@Override
	public void onInitialize() {
		config = Config.load();
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				dispatcher.register(literal("claude")
						.requires(src -> src.hasPermissionLevel(2))
						.then(literal("reset").executes(ctx -> {
							histories.remove(key(ctx.getSource()));
							ctx.getSource().sendFeedback(() -> Text.literal("§7История диалога очищена."), false);
							return 1;
						}))
						.then(literal("key").then(argument("key", StringArgumentType.string()).executes(ctx -> {
							ServerCommandSource src = ctx.getSource();
							if (!src.getServer().isSingleplayer()) {
								src.sendError(Text.literal("На сервере задайте ключ в config/claude-in-game.json, чтобы он не попал в чат и логи."));
								return 0;
							}
							config.apiKey = StringArgumentType.getString(ctx, "key");
							config.save();
							src.sendFeedback(() -> Text.literal("§aAPI-ключ сохранён."), false);
							return 1;
						})))
						.then(literal("model").then(argument("name", StringArgumentType.string()).executes(ctx -> {
							config.model = StringArgumentType.getString(ctx, "name");
							config.save();
							ctx.getSource().sendFeedback(() -> Text.literal("§aМодель: " + config.model), false);
							return 1;
						})))
						.then(argument("prompt", StringArgumentType.greedyString()).executes(this::ask))));
		LOGGER.info("Claude In Game загружен. Команда: /claude <запрос>");
	}

	private static String key(ServerCommandSource src) {
		return src.getEntity() != null ? src.getEntity().getUuidAsString() : "console";
	}

	private int ask(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		MinecraftServer server = src.getServer();
		String prompt = StringArgumentType.getString(ctx, "prompt");
		String hKey = key(src);

		List<JsonObject> history = histories.computeIfAbsent(hKey, k -> new ArrayList<>());
		List<JsonObject> snapshot;
		synchronized (history) {
			history.add(message("user", prompt));
			trim(history, config.historyMessages);
			snapshot = new ArrayList<>(history);
		}

		src.sendFeedback(() -> Text.literal("§7Claude думает..."), false);
		CompletableFuture.runAsync(() -> {
			try {
				String raw = ClaudeClient.send(config, SYSTEM_PROMPT, snapshot);
				synchronized (history) {
					history.add(message("assistant", raw));
					trim(history, config.historyMessages);
				}
				server.execute(() -> handle(src, server, raw));
			} catch (Exception e) {
				synchronized (history) {
					history.removeIf(m -> m == snapshot.get(snapshot.size() - 1));
				}
				LOGGER.error("Ошибка запроса к Claude", e);
				server.execute(() -> src.sendError(Text.literal("Ошибка: " + e.getMessage())));
			}
		});
		return 1;
	}

	private void handle(ServerCommandSource src, MinecraftServer server, String raw) {
		JsonObject json;
		try {
			json = parse(raw);
		} catch (Exception e) {
			src.sendFeedback(() -> Text.literal("§b[Claude] §f" + raw), false);
			return;
		}
		String msg = json.has("message") ? json.get("message").getAsString() : "";
		if (!msg.isEmpty()) src.sendFeedback(() -> Text.literal("§b[Claude] §f" + msg), false);

		JsonArray files = json.has("files") && json.get("files").isJsonArray() ? json.getAsJsonArray("files") : new JsonArray();
		if (files.isEmpty()) return;
		try {
			List<String> written = DatapackWriter.write(server, files);
			for (String p : written) src.sendFeedback(() -> Text.literal("§7 + " + p), false);
			applyPack(src, server);
		} catch (Exception e) {
			LOGGER.error("Ошибка записи датапака", e);
			src.sendError(Text.literal("Не удалось записать датапак: " + e.getMessage()));
		}
	}

	private void applyPack(ServerCommandSource src, MinecraftServer server) {
		String id = "file/" + DatapackWriter.PACK_NAME;
		ResourcePackManager manager = server.getDataPackManager();
		if (manager.getEnabledNames().contains(id)) {
			server.getCommandManager().executeWithPrefix(server.getCommandSource(), "reload");
		} else {
			manager.scanPacks();
			server.getCommandManager().executeWithPrefix(server.getCommandSource(), "datapack enable \"" + id + "\"");
		}
		src.sendFeedback(() -> Text.literal("§aИзменения применены."), false);
	}

	private static JsonObject parse(String raw) {
		String s = raw.trim();
		int a = s.indexOf('{');
		int b = s.lastIndexOf('}');
		if (a < 0 || b < a) throw new IllegalArgumentException("нет JSON");
		JsonElement el = JsonParser.parseString(s.substring(a, b + 1));
		return el.getAsJsonObject();
	}

	private static JsonObject message(String role, String content) {
		JsonObject o = new JsonObject();
		o.addProperty("role", role);
		o.addProperty("content", content);
		return o;
	}

	/** Обрезает историю, сохраняя чередование ролей: первое сообщение всегда от user. */
	private static void trim(List<JsonObject> h, int max) {
		while (h.size() > max) h.remove(0);
		while (!h.isEmpty() && !"user".equals(h.get(0).get("role").getAsString())) h.remove(0);
	}
}
