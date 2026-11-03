package com.atom.chat.screen;

import com.atom.chat.miuix.api.MiuixHost;
import com.mojang.brigadier.StringReader;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Live Brigadier and online-player completions, independent of legacy popup geometry. */
public final class NativeMiuixSuggestions {
    private static final int MAX_VISIBLE = 20;
    private String input = "";
    private int cursor = -1;
    private Object connection;
    private int generation;
    private List<MiuixHost.Suggestion> suggestions = List.of();

    public List<MiuixHost.Suggestion> snapshot(Minecraft client, String text, int position) {
        String value = text == null ? "" : text;
        int caret = Math.max(0, Math.min(position, value.length()));
        Object current = client == null ? null : client.getConnection();
        if (current != connection || !value.equals(input) || caret != cursor) {
            connection = current;
            input = value;
            cursor = caret;
            suggestions = List.of();
            int request = ++generation;
            if (current != null && client.player != null) {
                if (value.startsWith("/")) queryCommand(client, value, caret, request);
                else queryPlayers(client, value, caret);
            }
        } else if (current != null && !value.startsWith("/") && client.player != null) {
            // The same partial name can acquire or lose a match after a player joins/leaves.
            queryPlayers(client, value, caret);
        }
        return suggestions;
    }

    private void queryCommand(Minecraft client, String text, int caret, int request) {
        if (caret < 1) return;
        StringReader reader = new StringReader(text);
        reader.skip();
        var commands = client.getConnection().getCommands();
        var parsed = commands.parse(reader, client.getConnection().getSuggestionsProvider());
        commands.getCompletionSuggestions(parsed, caret).whenComplete((result, failure) -> client.execute(() -> {
            if (request != generation || client.getConnection() != connection) return;
            if (failure != null || result == null) {
                suggestions = List.of();
                return;
            }
            suggestions = result.getList().stream().limit(MAX_VISIBLE)
                    .map(item -> new MiuixHost.Suggestion(item.getRange().getStart(), item.getRange().getEnd(),
                            item.getText(), item.getTooltip() == null ? "" : item.getTooltip().getString()))
                    .toList();
        }));
    }

    private void queryPlayers(Minecraft client, String text, int caret) {
        int start = caret;
        while (start > 0 && !Character.isWhitespace(text.charAt(start - 1))) start--;
        if (start == caret) {
            suggestions = List.of();
            return;
        }
        String token = text.substring(start, caret);
        boolean mention = token.startsWith("@");
        String prefix = (mention ? token.substring(1) : token).toLowerCase(Locale.ROOT);
        int begin = start;
        ArrayList<MiuixHost.Suggestion> matches = new ArrayList<>();
        client.getConnection().getOnlinePlayers().stream()
                .sorted(Comparator.comparing(info -> info.getProfile().name(), String.CASE_INSENSITIVE_ORDER))
                .forEach(info -> {
                    String name = info.getProfile().name();
                    if (matches.size() < MAX_VISIBLE && name.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                        String completion = (mention ? "@" : "") + name;
                        if (!completion.equals(token)) matches.add(new MiuixHost.Suggestion(begin, caret, completion, ""));
                    }
                });
        suggestions = List.copyOf(matches);
    }
}
