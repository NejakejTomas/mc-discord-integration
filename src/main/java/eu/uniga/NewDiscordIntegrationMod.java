package eu.uniga;

import eu.uniga.Config.Config;
import eu.uniga.Discord.DiscordBot;
import eu.uniga.EmojiService.EmojiService;
import eu.uniga.MessageTransforms.FormattingContext;
import eu.uniga.MessageTransforms.SurrogatePairsDictionary;
import eu.uniga.MessageTransforms.MessagesTransforms;
import eu.uniga.Utils.TickExecuter;
import eu.uniga.EmojiService.WebServer.SimpleWebServer;
import eu.uniga.MessageEvents.Events;
import eu.uniga.MessageEvents.IMinecraftChatMessage;
import net.dv8tion.jda.api.entities.TextChannel;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.EntityType;
import net.minecraft.network.MessageType;
import net.minecraft.network.packet.s2c.play.GameMessageS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.text.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

public class NewDiscordIntegrationMod implements ModInitializer, IMinecraftChatMessage
{
	private static final UUID SenderUUID = new UUID(0, 666);
	public static final String Name = "DiscordIntegrationMod";
	private final Logger _logger = LogManager.getLogger(Name);
	private MinecraftServer _minecraftServer;
	private final TickExecuter _tickExecuter = new TickExecuter();
	private DiscordBot _discordBot;
	private SimpleWebServer _webServer;
	private SurrogatePairsDictionary _dictionary;
	private MessagesTransforms _messagesTransforms;
	private EmojiService _emojiService;
	
	@Override
	public void onInitialize()
	{
		ServerLifecycleEvents.SERVER_STARTING.register(this::Start);
		ServerLifecycleEvents.SERVER_STOPPED.register(this::Stop);
		ServerTickEvents.START_SERVER_TICK.register(this::Tick);
	}
	
	private void Start(MinecraftServer minecraftServer)
	{
		// Currently does nothing
		if (minecraftServer == null) return;
		
		_minecraftServer = minecraftServer;
		
		try
		{
			// Load the bot configuration and setup Discord connection via JDA
			_discordBot = new DiscordBot(Config.GetConfig(), (member, message) -> _tickExecuter.ExecuteNextTick(() ->
			{
				LiteralText formattedName = (LiteralText)new LiteralText(member.getEffectiveName()).setStyle(Style.EMPTY
								.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_ENTITY, new HoverEvent.EntityContent(EntityType.FISHING_BOBBER, new UUID(0, member.getIdLong()), new LiteralText(member.getEffectiveName()))))
								.withInsertion(member.getAsMention())
								.withColor(TextColor.fromRgb(member.getColorRaw())));
				
				TranslatableText formattedMessage = new TranslatableText(
								"chat.type.text",
								formattedName,
								_messagesTransforms.FromString(message.getContentRaw()));
				
				_minecraftServer.sendSystemMessage(formattedMessage, SenderUUID);
				_minecraftServer.getPlayerManager().sendToAll(new GameMessageS2CPacket(formattedMessage, MessageType.CHAT, SenderUUID));
			}));
			
			// Start translation dictionary
			_dictionary = new SurrogatePairsDictionary();
			
			// Try to start emoji related stuff, if it fails, continue without it
			try
			{
				if (Config.GetConfig().AreEmotesEnabled()) EnableCustomEmotes();
			}
			catch (Exception e)
			{
				_logger.error("Cannot start custom emoji services: {}", e.getLocalizedMessage());
			}
			
			// Create new message transforms
			_messagesTransforms = new MessagesTransforms(_dictionary, new FormattingContext(_discordBot.GetClient(), _dictionary));
			
			// Register Minecraft chat messages callback
			Events.SetMinecraftChatMessageCallback(this);
			
			// Start bot (starts adding channels)
			_discordBot.Start();
		}
		catch (Exception e)
		{
			_logger.error("Cannot start Discord integration mod", e);
		}
	}
	
	private void EnableCustomEmotes() throws IOException
	{
		// Start integrated webserver
		_webServer = new SimpleWebServer(Config.GetConfig().GetWebserverPort(), EmojiService.ResourcePackLocation);
		_webServer.Start();
		
		// Add delegate for registering new channels on the fly
		_emojiService = new EmojiService(new EmojiService.IResourcePackReloadable()
		{
			@Override
			public void Reload(String url, String sha1)
			{
				ChangeResourcePack(url, sha1);
			}
			
			@Override
			public void UpdateDictionary(Map<String, Integer> dictionary)
			{
				// Emotes changed, swap dictionary, on server thread
				_tickExecuter.ExecuteNextTick(() -> _dictionary.Set(dictionary));
			}
		});
		
		// Add delegate, on channel added register channel to emoji service
		// TODO: on channel remove
		_discordBot.SetEmojiCallback(new DiscordBot.EmojiCallback()
		{
			@Override
			public void OnChannelAdded(@NotNull TextChannel channel)
			{
				super.OnChannelAdded(channel);
				_emojiService.AddGuild(channel.getGuild());
			}
			
			@Override
			public void OnChannelRemoved(@NotNull TextChannel channel)
			{
				super.OnChannelRemoved(channel);
				_emojiService.RemoveGuild(channel.getGuild());
			}
		});
		
		// TODO: make on event
		_emojiService.Start(30 * 1000);
	}
	
	private void Stop(MinecraftServer minecraftServer)
	{
		if (_webServer != null) _webServer.Stop();
		if (_emojiService != null) _emojiService.Stop();
		_discordBot.Stop();
	}
	
	private void Tick(MinecraftServer minecraftServer)
	{
		_tickExecuter.RunAll();
	}
	
	// Thread safe
	private void ChangeResourcePack(String url, String sha1)
	{
		// Schedule resource pack change to server thread
		_tickExecuter.ExecuteNextTick(() ->
		{
			_minecraftServer.setResourcePack(url, sha1);
			String fileName = url.substring(url.lastIndexOf('/'));
			_webServer.SetContext(fileName);
			
			// Force reload of resource pack to all players, TODO: move to config
			_minecraftServer.getPlayerManager().getPlayerList().forEach(player -> player.sendResourcePackUrl(url, sha1));
  	});
	}
	
	@Override
	public TranslatableText OnMessageSent(TranslatableText message, UUID sender)
	{
		_discordBot.SendMessage(((LiteralText)message.getArgs()[0]).asString(), (String)message.getArgs()[1]);
		return new TranslatableText("chat.type.text", message.getArgs()[0], _messagesTransforms.FromString((String)message.getArgs()[1]));
	}
}