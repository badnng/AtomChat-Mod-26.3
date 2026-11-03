package com.atom.chat.miuix.api;

import java.util.List;

/**
 * Mapping-neutral boundary between the real Compose Miuix surface and Minecraft.
 * All calls run on the client thread. A renderer reads one State per frame;
 * callbacks resolve stable ids against the live stores before applying an action.
 * No Minecraft, Skija, Compose, or platform types cross this boundary.
 */
public interface MiuixHost {
    State snapshot();

    /** About supplies its own opaque surface; hosts may retain the world during a page transition. */
    default boolean worldBackdropRequired(State state) { return !state.hasOpaquePageBackground(); }

    /** Last route change; retained until the next change, independently of legacy navigation. */
    default Navigation navigation() { return new Navigation(0L, "NONE"); }

    /** Configured page transition family: SLIDE or ZOOM. */
    default String pageNavigationStyle() { return "SLIDE"; }

    /** Called after the native page transition reaches its destination, including instant changes. */
    default void navigationTransitionFinished(long sequence) {}

    /** motion: NONE, ROOT_FORWARD, ROOT_BACKWARD, PUSH, or POP. */
    record Navigation(long sequence, String motion) {}

    /** All user actions return through the existing client domain services. */
    void perform(Action action);

    /** Replaces the hidden EditBox value and selection, preserving its IME bridge. */
    void setText(String text, int cursor, int anchor);

    /** Focus and IME anchor coordinates are scene-local physical pixels. */
    void requestTextInput(boolean focused, float x, float y, float width, float height);

    /** Identifies the chat composer separately from text fields inside dialogs. */
    default void composerFocusChanged(boolean focused) {}

    /** Encoded PNG for a stable image key, or null while loading/unavailable. */
    byte[] image(String key);

    String text(String translationKey, Object... arguments);

    /** Original AtomChat kaomoji catalog; returned as immutable display data. */
    default List<String> kaomoji() { return List.of(); }

    String readClipboard();
    void writeClipboard(String text);

    /** Timestamp of the existing domain's avatar nudge; zero means no pulse. */
    default long pokeTime(String playerKey) { return 0L; }

    /** Plain tooltip text for a rich run's stable interaction token. */
    default String hoverText(String interaction) { return ""; }

    /** Optional original-style metrics; stock Miuix uses its own component sizing. */
    default UiMetrics uiMetrics() {
        return new UiMetrics(55f, 5f, 15f, 17.5f, 25f, 25f, 21.25f, 20f,
                23.75f, 20f, 50f, 10f, 45f, 70f, 37.5f, 2);
    }

    record UiMetrics(float headerHeight, float edgeInset, float panelInset, float outerGap,
                     float bodyFont, float inputFont, float nameFont, float quoteFont,
                     float titleFont, float timeFont, float avatarSize, float avatarGap,
                     float actionSize, float sendWidth, float sendHeight, int inputMaxLines) {}

    /** Optional original-style palette; stock Miuix uses its upstream semantic palette. */
    default UiPalette uiPalette() {
        return new UiPalette(0xFF4D5055, 0xFFFFFFFF, 0xDCAAAABA,
                0x962C3E50, 0xDCAAAABA, 0xFFFFFFFF, true);
    }

    record UiPalette(int card, int primaryText, int secondaryText, int capsuleBackground,
                     int capsuleText, int outline, boolean outlineEnabled) {}

    record State(Route route, List<Conversation> conversations, List<Message> messages,
                 Composer composer, Settings settings, Profile profile, Extras extras,
                 Appearance appearance, boolean animations, String navigationStyle) {
        public boolean hasOpaquePageBackground() {
            return "settings_section".equals(route.page()) && "about".equals(route.section());
        }
    }

    record Route(String page, String targetKey, String title, String section,
                 boolean canGoBack, boolean readOnly, String readOnlyHint) {}

    record Player(String key, String uuid, String name, String displayName,
                  boolean online, boolean blocked, String avatarKey, String hatKey) {}

    record Conversation(String key, String kind, Player player, String title,
                        String preview, long timestamp, int unread, boolean online,
                        boolean blocked) {}

    /** Nullable explicit colors remain nullable, so theme defaults still apply. */
    record Run(String text, Integer argb, boolean bold, boolean italic, boolean underlined,
               boolean strikethrough, boolean obfuscated, String interaction) {}

    record Message(long id, long timestamp, Player sender, boolean own, boolean system,
                   String rawText, String content, List<Run> senderRuns, List<Run> bodyRuns,
                   String quoteName, String quoteText, List<Run> quoteNameRuns,
                   List<Run> quoteTextRuns, int duplicates, String imageKey,
                   boolean reactionsAvailable, List<Reaction> reactions, boolean grouped,
                   String timestampLabel, boolean animateEntry) {}

    record Reaction(String emoji, int count, boolean own, List<String> members,
                    long arrivedAt) {}

    record Composer(String text, int cursor, int anchor, boolean focused, boolean enabled,
                    Long replyId, String replyName, String replyText, boolean partnerTyping,
                    String mode) {}

    /** Row ids are stable catalog ids; kind: switch, slider, color, action, info, label. */
    record Setting(String id, String kind, String title, String description, String group,
                   boolean available, boolean checked, float value, float min, float max,
                   float step, String displayValue, int color, List<Integer> swatches,
                   String uri, boolean destructive, List<String> options, int selectedIndex) {}

    record Settings(String section, List<Section> sections, List<Setting> rows,
                    List<Preset> presets, List<Player> blockedPlayers) {}

    record Section(String id, String title) {}
    record Preset(String id, String title, int accentColor, int panelColor, boolean wallpaper) {}
    record Fact(String label, String value, String copy) {}

    record Profile(Player player, boolean self, String signature, String bannerKey,
                   boolean customAvatar, boolean customBanner, boolean syncing,
                   boolean unsynced, List<Fact> statistics, List<Fact> facts) {}

    record Sticker(String key, String name, boolean server) {}
    record Phrase(int index, String text, boolean server) {}
    record Extras(List<String> emoji, List<Sticker> stickers, List<Phrase> phrases,
                  int earlierMessages, String feedback, boolean feedbackSuccess, CropEditor cropEditor,
                  List<Suggestion> suggestions, List<Notice> notices, Jump jump, String worldFilter) {
        public Extras(List<String> emoji, List<Sticker> stickers, List<Phrase> phrases,
                      int earlierMessages, String feedback, boolean feedbackSuccess, CropEditor cropEditor,
                      List<Suggestion> suggestions, List<Notice> notices, Jump jump) {
            this(emoji, stickers, phrases, earlierMessages, feedback, feedbackSuccess, cropEditor,
                    suggestions, notices, jump, "ALL");
        }
    }

    record Suggestion(int start, int end, String text, String tooltip) {}
    record Notice(String id, String type, String sender, String content, Player player,
                  List<Run> senderRuns, List<Run> contentRuns, long born) {}
    record Jump(long messageId, long sequence) {}

    record CropEditor(String sourceKey, int sourceWidth, int sourceHeight, boolean circle,
                      float aspect, float zoom, float offsetX, float offsetY) {}

    record Appearance(int accent, int panel, int primary, int secondary, int ownBubble,
                      int ownText, int otherBubble, int otherText, float opacity,
                      float cornerRadius, boolean blur, String wallpaperKey,
                      String avatarStyle) {}

    /**
     * target is a stable player/message/setting/preset/image key. value carries
     * an inserted string, a chosen color/number, or action-specific argument.
     * commit is false only for an in-progress slider drag.
     */
    record Action(Command command, String target, String value, boolean commit) {
        public Action(Command command) { this(command, "", "", true); }
        public Action(Command command, String target) { this(command, target, "", true); }
        public Action(Command command, String target, String value) { this(command, target, value, true); }
    }

    enum Command {
        ROOT, BACK, CLOSE, WORLD_CHAT, PRIVATE_CHAT, PROFILE, SETTINGS_SECTION,
        SEND, APPEND, REPLY, CANCEL_REPLY, FILTER, LOAD_EARLIER, JUMP_LATEST,
        COPY_MESSAGE, COPY_TEXT, INTERACT, MENTION, TELEPORT, BLOCK, POKE, AVATAR_CLICK,
        REACT, PICK_IMAGE, SAVE_IMAGE, PREVIEW_IMAGE,
        SET_SWITCH, SET_SLIDER, SET_COLOR, SET_CHOICE, SETTINGS_ACTION, OPEN_LINK,
        PICK_AVATAR, CLEAR_AVATAR, PICK_BANNER, CLEAR_BANNER,
        EDIT_SIGNATURE, SAVE_SIGNATURE, CANCEL_SIGNATURE,
        ADD_STICKER, SEND_STICKER, DELETE_STICKER,
        ADD_PHRASE, EDIT_PHRASE, DELETE_PHRASE, INSERT_PHRASE,
        SAVE_PHRASE, CANCEL_PHRASE, CROP_TRANSFORM, CROP_CONFIRM, CROP_CANCEL, COMPLETE,
        NOTICE_OPEN, NOTICE_REPLY
    }
}
