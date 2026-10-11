package dev.claudeingame;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class Config {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("claude-in-game.json");

	public String apiKey = "";
	public String model = "claude-sonnet-5-5";
	public int maxTokens = 8000;
	public int historyMessages = 20;

	public static Config load() {
		try {
			if (Files.exists(PATH)) {
				Config c = GSON.fromJson(Files.readString(PATH), Config.class);
				if (c != null) return c;
			}
		} catch (Exception e) {
			ClaudeInGame.LOGGER.error("Не удалось прочитать конфиг", e);
		}
		Config c = new Config();
		c.save();
		return c;
	}

	public void save() {
		try {
			Files.writeString(PATH, GSON.toJson(this));
		} catch (IOException e) {
			ClaudeInGame.LOGGER.error("Не удалось сохранить конфиг", e);
		}
	}
}
