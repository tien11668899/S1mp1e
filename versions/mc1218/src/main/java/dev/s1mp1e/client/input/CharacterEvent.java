package dev.s1mp1e.client.input;

/** 26.x {@code net.minecraft.client.input.CharacterEvent} look-alike (see {@link Input}). */
public record CharacterEvent(int codepoint, int modifiers) {
    public String codepointAsString() { return Character.toString(codepoint); }
    public boolean isAllowedChatCharacter() { return net.minecraft.util.StringUtil.isAllowedChatCharacter((char) codepoint); }
}
