package eu.uniga.MessageTransforms.MinecraftToMinecraft;

import eu.uniga.MessageTransforms.IMessageTransform;
import eu.uniga.MessageTransforms.SurrogatePairsDictionary;
import eu.uniga.Utils.Codepoints;
import net.minecraft.text.TranslatableText;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class EmojiTransform implements IMessageTransform
{
	private SurrogatePairsDictionary _emojiDictionary;
	private static final Pattern _pattern = Pattern.compile("(<:[a-zA-Z\\-_]+?:\\d+?>+|:[a-zA-Z\\-_]+?:)");
	
	public EmojiTransform(SurrogatePairsDictionary emojiDictionary)
	{
		_emojiDictionary = emojiDictionary;
	}
	
	@Override
	public TranslatableText Transform(TranslatableText text)
	{
		return TransformText(text);
	}
	
	private TranslatableText TransformText(TranslatableText text)
	{
		for (int i = 0; i < text.getArgs().length; i++)
		{
			if (!(text.getArgs()[i] instanceof String)) continue;
			
			int lastIndex = 0;
			String textPart = (String)(text.getArgs()[i]);
			
			Matcher matcher = _pattern.matcher(textPart);
			StringBuilder buffer = new StringBuilder();
			
			while (matcher.find())
			{
				Integer found = _emojiDictionary.GetSurrogatePairFromShortName(matcher.group(1));
				String append;
				
				if (found == null) append = matcher.group(1);
				else append = Codepoints.Utf16ToString(found);
				
				buffer.append(textPart, lastIndex, matcher.start()).append(append);
				lastIndex = matcher.end();
			}
			if (lastIndex < textPart.length())
			{
				buffer.append(textPart, lastIndex, textPart.length());
			}
			text.getArgs()[i] = buffer.toString();
		}
		
		text.getSiblings().forEach(sibling -> TransformText((TranslatableText)sibling));
		
		return text;
	}
}